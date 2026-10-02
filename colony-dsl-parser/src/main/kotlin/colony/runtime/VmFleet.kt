package colony.runtime

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A tick barrier shared by both implementations. World rules and event delivery stay in the kernel. */
interface VmFleet : AutoCloseable {
    val mode: String
    val pids: Map<String, Int>
    val workerCount: Int get() = 1
    fun step(frames: Map<String, VmFrame>): Map<String, VmResult>
    override fun close() {}
}

class ReferenceFleet(prepared: PreparedRun, workers: Int = configuredReferenceWorkers()) : VmFleet {
    override val mode = "reference"
    override val pids = emptyMap<String, Int>()
    override val workerCount = workers.also { require(it > 0) { "HH_REFERENCE_WORKERS must be positive" } }
        .coerceAtMost(prepared.manifest.instances.size.coerceAtLeast(1))
    private val verified = ReferenceVm.VerifiedProgram(prepared.program)
    private val vms = prepared.manifest.instances.map { verified.context(it, prepared.scenario.seed) }
    private val entityIds = vms.mapTo(HashSet()) { it.entityId }
    private val shardSize = maxOf(1, ((vms.size.toLong() + workerCount - 1) / workerCount).toInt())
    private val shards = (vms.indices step shardSize).map { start -> start until minOf(start + shardSize, vms.size) }
    private val executor = if (workerCount == 1) null else Executors.newFixedThreadPool(workerCount) { task ->
        Thread(task, "reference-vm-worker").apply { isDaemon = true }
    }
    private var closed = false

    override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
        check(!closed) { "Reference fleet is closed" }
        require(frames.keys == entityIds) { "Frame set does not match the manifest" }
        val results = LinkedHashMap<String, VmResult>(vms.size)
        if (executor == null) {
            for (vm in vms) results[vm.entityId] = vm.step(frames.getValue(vm.entityId))
            return results
        }
        val slots = arrayOfNulls<VmResult>(vms.size)
        // Join in manifest order. Scheduling cannot change intent, event or floating-point reduction order.
        val jobs = shards.map { shard -> executor.submit(Callable {
            for (index in shard) {
                val vm = vms[index]
                slots[index] = vm.step(frames.getValue(vm.entityId))
            }
        }) }
        try { jobs.forEach { it.get() } }
        catch (failure: Exception) { jobs.forEach { it.cancel(true) }; close(); throw failure }
        for (index in vms.indices) {
            results[vms[index].entityId] = checkNotNull(slots[index])
        }
        return results
    }

    override fun close() {
        if (closed) return
        closed = true
        executor?.shutdownNow()
        executor?.awaitTermination(5, TimeUnit.SECONDS)
    }
}

internal fun configuredReferenceWorkers(): Int = System.getenv("HH_REFERENCE_WORKERS")?.let {
    it.toIntOrNull() ?: error("HH_REFERENCE_WORKERS must be a positive integer")
} ?: 1
