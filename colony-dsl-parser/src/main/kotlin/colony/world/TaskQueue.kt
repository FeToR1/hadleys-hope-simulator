package colony.world

import kotlinx.serialization.Serializable

/**
 * Global task queue for repairing damaged settlement infrastructure,
 * coastal eroded houses, crocodile bombarded buildings, and fog-disabled air defense units.
 */
class TaskQueue(
    private val spatialIndex: SpatialIndex
) {
    private val activeJobs = LinkedHashMap<String, RepairJob>()
    private var nextJobId = 0L

    val size: Int get() = activeJobs.size

    val allJobs: Collection<RepairJob> get() = activeJobs.values

    fun hasJob(targetId: String): Boolean = targetId in activeJobs

    fun getJob(targetId: String): RepairJob? = activeJobs[targetId]

    /**
     * Enqueues or updates a repair task for a damaged target entity.
     */
    fun enqueueOrUpdate(
        targetId: String,
        kind: String,
        position: Point,
        duration: Double,
        availableAtTick: Long = 0L
    ): RepairJob {
        val existing = activeJobs[targetId]
        if (existing != null) return existing

        val job = RepairJob(
            id = "task/${++nextJobId}",
            target = targetId,
            kind = kind,
            at = position,
            done = 0.0,
            duration = duration,
            availableAtTick = availableAtTick
        )
        activeJobs[targetId] = job
        return job
    }

    fun updateProgress(targetId: String, deltaDone: Double): Boolean {
        val job = activeJobs[targetId] ?: return false
        val newDone = job.done + deltaDone
        if (newDone >= job.duration) {
            activeJobs.remove(targetId)
            return true // Completed!
        } else {
            activeJobs[targetId] = job.copy(done = newDone)
            return false
        }
    }

    fun complete(targetId: String) {
        activeJobs.remove(targetId)
    }

    fun findNearestJob(from: Point, maxRadius: Double = 500.0, currentTick: Long = 0L): RepairJob? {
        val candidateTargets = spatialIndex.queryRadius(from, maxRadius)
        var nearest: RepairJob? = null
        var minDistance = Double.MAX_VALUE

        for (id in candidateTargets) {
            val job = activeJobs[id] ?: continue
            if (job.availableAtTick > currentTick) continue
            val dist = from.distanceTo(job.at)
            if (dist < minDistance) {
                minDistance = dist
                nearest = job
            }
        }
        return nearest ?: activeJobs.values
            .filter { it.availableAtTick <= currentTick }
            .minByOrNull { from.distanceTo(it.at) }
    }
}
