package colony.world

import kotlinx.serialization.Serializable
import kotlin.math.max


/**
 * Controls sea coastal erosion and periodically invading localized sea fog.
 * Fog is modeled as a massive drifting cloud (~100x100 houses: ~1200m x ~1400m),
 * entering from the sea on the East boundary and traveling diagonally across the settlement
 * towards the western forest.
 */
class SeaController(
    val seaCoastX: Double,
    val config: SeaConfig = SeaConfig(),
    var minY: Double = 0.0,
    var maxY: Double = 1000.0,
    var minX: Double = 0.0
) {
    var isFogActive: Boolean = false
        private set
    var currentFogDepth: Double = 0.0
        private set
    var totalErosionDamage: Double = 0.0
        private set
    var fogEventsCount: Int = 0
        private set

    val cloudWidth: Double = config.cloudWidth
    val cloudHeight: Double = config.cloudHeight

    var cloudCenter: Point = Point(seaCoastX, (minY + maxY) / 2.0)
        private set

    private var startX: Double = seaCoastX + cloudWidth * 0.25
    private var startY: Double = (minY + maxY) / 2.0
    private var targetX: Double = minX - cloudWidth * 0.4
    private var targetY: Double = (minY + maxY) / 2.0

    fun configureSettlementBounds(minX: Double, maxX: Double, minY: Double, maxY: Double) {
        this.minX = minX
        this.minY = minY
        this.maxY = maxY
        this.startY = (minY + maxY) / 2.0
        this.targetY = (minY + maxY) / 2.0
        this.cloudCenter = Point(seaCoastX + cloudWidth, (minY + maxY) / 2.0)
    }

    /**
     * Checks if a world coordinate is in the coastal erosion danger zone.
     */
    fun isInCoastalZone(point: Point): Boolean {
        return point.x >= (seaCoastX - config.coastalZoneWidth)
    }

    /**
     * Checks if a world coordinate is currently enveloped inside the localized drifting sea fog cloud.
     */
    fun isInFog(point: Point): Boolean {
        if (!isFogActive) return false
        val rx = cloudWidth / 2.0
        val ry = cloudHeight / 2.0
        val dx = (point.x - cloudCenter.x) / rx
        val dy = (point.y - cloudCenter.y) / ry
        return (dx * dx + dy * dy) <= 1.0
    }

    /**
     * Advances sea simulation step: calculates localized fog cloud movement diagonally across settlement.
     */
    fun step(elapsedSeconds: Double, dt: Double): SeaStepResult {
        val cycleTime = elapsedSeconds % config.fogPeriodSeconds
        val shouldFogBeActive = cycleTime < config.fogDurationSeconds

        val wasFogActive = isFogActive
        isFogActive = shouldFogBeActive

        var fogStarted = false
        var fogCleared = false

        if (isFogActive) {
            val progress = (cycleTime / config.fogDurationSeconds).coerceIn(0.0, 1.0)
            if (!wasFogActive) {
                fogStarted = true
                fogEventsCount++

                // Determine diagonal entry trajectory from sea (East) towards forest (West/South-West/North-West)
                startX = seaCoastX + cloudWidth * 0.25
                val rangeY = (maxY - minY).coerceAtLeast(600.0)
                // Alternate entry corridors across cycles
                val entryFraction = when (fogEventsCount % 3) {
                    0 -> 0.35
                    1 -> 0.50
                    else -> 0.65
                }
                startY = minY + rangeY * entryFraction
                targetX = minX - cloudWidth * 0.4
                val diagonalOffset = if (fogEventsCount % 2 == 0) -800.0 else 800.0
                targetY = (startY + diagonalOffset).coerceIn(minY, maxY)
            }

            // Move cloud diagonally across settlement
            val curX = startX + (targetX - startX) * progress
            val curY = startY + (targetY - startY) * progress
            cloudCenter = Point(curX, curY)
            currentFogDepth = (seaCoastX - curX).coerceAtLeast(0.0)
        } else {
            if (wasFogActive) {
                fogCleared = true
            }
            currentFogDepth = 0.0
            // Reset to off-coast position
            cloudCenter = Point(seaCoastX + cloudWidth, (minY + maxY) / 2.0)
        }

        return SeaStepResult(
            fogStarted = fogStarted,
            fogCleared = fogCleared,
            isFogActive = isFogActive,
            currentFogDepth = currentFogDepth,
            erosionDamage = config.erosionDamageRate * dt,
            suffocationDamage = config.fogSuffocationDamageRate * dt,
            cloudCenter = cloudCenter,
            cloudWidth = cloudWidth,
            cloudHeight = cloudHeight
        )
    }

    fun recordErosionDamage(amount: Double) {
        totalErosionDamage += amount
    }
}

data class SeaStepResult(
    val fogStarted: Boolean,
    val fogCleared: Boolean,
    val isFogActive: Boolean,
    val currentFogDepth: Double,
    val erosionDamage: Double,
    val suffocationDamage: Double,
    val cloudCenter: Point = Point(0.0, 0.0),
    val cloudWidth: Double = 1200.0,
    val cloudHeight: Double = 1400.0
)
