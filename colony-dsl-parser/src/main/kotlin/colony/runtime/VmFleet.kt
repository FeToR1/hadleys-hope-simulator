package colony.runtime


/** A tick barrier between the host and the native fleet. World rules and event delivery stay in the kernel. */
interface VmFleet : AutoCloseable {
    val mode: String
    val pids: Map<String, Int>
    val workerCount: Int get() = 1
    fun step(frames: Map<String, VmFrame>): Map<String, VmResult>
    override fun close() {}
}

/** Default production fleet: the same native broker used by run-native. */
fun NativeFleet(prepared: PreparedRun, workers: Int = configuredNativeWorkers()): VmFleet = ProcessFleet(prepared,
    colony.cvm.NativeVmConnection.executable() ?: error("Build native/ or set HH_VM"), java.util.UUID.randomUUID().toString(), workers = workers)
