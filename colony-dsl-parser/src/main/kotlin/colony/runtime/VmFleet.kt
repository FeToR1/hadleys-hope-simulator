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
    private val shards = vms.chunked(maxOf(1, ((vms.size.toLong() + workerCount - 1) / workerCount).toInt()))
    private val executor = if (workerCount == 1) null else Executors.newFixedThreadPool(workerCount) { task ->
        Thread(task, "reference-vm-worker").apply { isDaemon = true }
    }
    private var closed = false

    override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
        check(!closed) { "Reference fleet is closed" }
        require(frames.keys == entityIds) { "Frame set does not match the manifest" }
        fun evaluate(shard: List<ReferenceVm>) = shard.map { vm -> vm.entityId to vm.step(frames.getValue(vm.entityId)) }
        // Join in manifest order. Scheduling cannot change intent, event or floating-point reduction order.
        val results = if (executor == null) listOf(evaluate(vms)) else {
            val jobs = shards.map { shard -> executor.submit(Callable { evaluate(shard) }) }
            try { jobs.map { it.get() } }
            catch (failure: Exception) { jobs.forEach { it.cancel(true) }; close(); throw failure }
        }
        return results.flatten().toMap(LinkedHashMap())
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
