package colony.world

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fast 2D spatial hash grid index for $O(1)$ average-time radius and proximity queries.
 * Prevents $O(N^2)$ bottlenecks when scaling up to 5000+ houses and thousands of entities.
 */
class SpatialIndex(val cellSize: Double = 50.0) {
    private val cellMap = HashMap<Long, MutableList<String>>()
    private val entityPositions = HashMap<String, Point>()
    private val entityCells = HashMap<String, Long>()

    private fun cellKey(x: Double, y: Double): Long {
        val cx = floor(x / cellSize).toInt().toLong()
        val cy = floor(y / cellSize).toInt().toLong()
        return (cx shl 32) or (cy and 0xFFFFFFFFL)
    }

    @Synchronized
    fun update(id: String, point: Point) {
        val oldKey = entityCells[id]
        val newKey = cellKey(point.x, point.y)
        entityPositions[id] = point

        if (oldKey != newKey) {
            if (oldKey != null) {
                cellMap[oldKey]?.remove(id)
            }
            entityCells[id] = newKey
            cellMap.getOrPut(newKey) { ArrayList() }.add(id)
        }
    }

    @Synchronized
    fun remove(id: String) {
        val oldKey = entityCells.remove(id)
        if (oldKey != null) {
            cellMap[oldKey]?.remove(id)
        }
        entityPositions.remove(id)
    }

    @Synchronized
    fun clear() {
        cellMap.clear()
        entityPositions.clear()
        entityCells.clear()
    }

    @Synchronized
    fun positionOf(id: String): Point? = entityPositions[id]

    @Synchronized
    fun queryRadius(center: Point, radius: Double): List<String> {
        if (radius <= 0.0) return emptyList()
        val r2 = radius * radius
        val minCellX = floor((center.x - radius) / cellSize).toInt()
        val maxCellX = floor((center.x + radius) / cellSize).toInt()
        val minCellY = floor((center.y - radius) / cellSize).toInt()
        val maxCellY = floor((center.y + radius) / cellSize).toInt()

        val results = ArrayList<String>()
        for (cx in minCellX..maxCellX) {
            for (cy in minCellY..maxCellY) {
                val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
                val cellEntities = cellMap[key] ?: continue
                for (id in cellEntities) {
                    val p = entityPositions[id] ?: continue
                    val dx = p.x - center.x
                    val dy = p.y - center.y
                    if (dx * dx + dy * dy <= r2) {
                        results.add(id)
                    }
                }
            }
        }
        return results
    }

    @Synchronized
    fun queryNearest(center: Point, radius: Double, filter: (String) -> Boolean = { true }): String? {
        val candidates = queryRadius(center, radius)
        var nearestId: String? = null
        var minDistanceSq = Double.MAX_VALUE

        for (id in candidates) {
            if (!filter(id)) continue
            val p = entityPositions[id] ?: continue
            val dx = p.x - center.x
            val dy = p.y - center.y
            val d2 = dx * dx + dy * dy
            if (d2 < minDistanceSq) {
                minDistanceSq = d2
                nearestId = id
            }
        }
        return nearestId
    }
}
