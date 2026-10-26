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
    private val segments = HashMap<String, Pair<Point, Point>>()
    private val segmentCells = HashMap<Long, MutableSet<String>>()
    private val cellsBySegment = HashMap<String, Set<Long>>()

    init { require(cellSize.isFinite() && cellSize > 0.0) { "Spatial cell size must be finite and positive" } }

    private fun cellKey(x: Double, y: Double): Long {
        val cx = floor(x / cellSize).toInt().toLong()
        val cy = floor(y / cellSize).toInt().toLong()
        return (cx shl 32) or (cy and 0xFFFFFFFFL)
    }

    @Synchronized
    fun update(id: String, point: Point) {
        require(point.x.isFinite() && point.y.isFinite()) { "Spatial positions must be finite" }
        val oldKey = entityCells[id]
        val newKey = cellKey(point.x, point.y)
        entityPositions[id] = point

        if (oldKey != newKey) {
            if (oldKey != null) {
                cellMap[oldKey]?.let { ids -> ids.remove(id); if (ids.isEmpty()) cellMap.remove(oldKey) }
            }
            entityCells[id] = newKey
            cellMap.getOrPut(newKey) { ArrayList() }.add(id)
        }
    }

    @Synchronized
    fun remove(id: String) {
        val oldKey = entityCells.remove(id)
        if (oldKey != null) {
            cellMap[oldKey]?.let { ids -> ids.remove(id); if (ids.isEmpty()) cellMap.remove(oldKey) }
        }
        entityPositions.remove(id)
        segments.remove(id)
        cellsBySegment.remove(id)?.forEach { key ->
            segmentCells[key]?.let { ids -> ids.remove(id); if (ids.isEmpty()) segmentCells.remove(key) }
        }
    }

    @Synchronized
    fun updateSegment(id: String, from: Point, to: Point) {
        require(from.x.isFinite() && from.y.isFinite() && to.x.isFinite() && to.y.isFinite()) { "Spatial segment points must be finite" }
        cellsBySegment.remove(id)?.forEach { key ->
            segmentCells[key]?.let { ids -> ids.remove(id); if (ids.isEmpty()) segmentCells.remove(key) }
        }
        segments[id] = from to to
        val steps = maxOf(1, ceil(from.distanceTo(to) / (cellSize / 2.0)).toInt())
        val keys = LinkedHashSet<Long>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            keys += cellKey(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }
        cellsBySegment[id] = keys
        keys.forEach { segmentCells.getOrPut(it) { LinkedHashSet() }.add(id) }
        // Keep the midpoint indexed for ordinary proximity lookups too.
        update(id, Point((from.x + to.x) / 2.0, (from.y + to.y) / 2.0))
    }

    @Synchronized
    fun clear() {
        cellMap.clear()
        entityPositions.clear()
        entityCells.clear()
        segments.clear()
        segmentCells.clear()
        cellsBySegment.clear()
    }

    @Synchronized
    fun positionOf(id: String): Point? = entityPositions[id]

    @Synchronized
    fun queryRadius(center: Point, radius: Double): List<String> {
        require(radius.isFinite() && radius >= 0.0) { "Query radius must be finite and nonnegative" }
        val r2 = radius * radius
        val minCellX = floor((center.x - radius) / cellSize).toInt()
        val maxCellX = floor((center.x + radius) / cellSize).toInt()
        val minCellY = floor((center.y - radius) / cellSize).toInt()
        val maxCellY = floor((center.y + radius) / cellSize).toInt()

        val candidates = LinkedHashSet<String>()
        for (cx in minCellX..maxCellX) {
            for (cy in minCellY..maxCellY) {
                val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
                cellMap[key]?.let(candidates::addAll)
                segmentCells[key]?.let(candidates::addAll)
            }
        }
        val results = ArrayList<String>()
        for (id in candidates) {
            val p = entityPositions[id] ?: continue
            val dx = p.x - center.x
            val dy = p.y - center.y
            val segment = segments[id]
            val distanceSq = if (segment == null) dx * dx + dy * dy else pointSegmentDistanceSquared(center, segment.first, segment.second)
            if (distanceSq <= r2) results.add(id)
        }
        results.sort()
        return results
    }

    private fun pointSegmentDistanceSquared(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val px = a.x + t * dx - p.x
        val py = a.y + t * dy - p.y
        return px * px + py * py
    }

    @Synchronized
    fun queryNearest(center: Point, radius: Double, filter: (String) -> Boolean = { true }): String? {
        val candidates = queryRadius(center, radius)
        var nearestId: String? = null
        var minDistanceSq = Double.MAX_VALUE

        for (id in candidates) {
            if (!filter(id)) continue
            val p = entityPositions[id] ?: continue
            val segment = segments[id]
            val d2 = if (segment == null) {
                val dx = p.x - center.x
                val dy = p.y - center.y
                dx * dx + dy * dy
            } else pointSegmentDistanceSquared(center, segment.first, segment.second)
            if (d2 < minDistanceSq || (d2 == minDistanceSq && (nearestId == null || id < nearestId!!))) {
                minDistanceSq = d2
                nearestId = id
            }
        }
        return nearestId
    }
}
