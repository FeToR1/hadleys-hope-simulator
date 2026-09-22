package colony.runtime

/** A tick barrier shared by both implementations. World rules and event delivery stay in the kernel. */
interface VmFleet : AutoCloseable {
    val mode: String
    val pids: Map<String, Int>
    fun step(frames: Map<String, VmFrame>): Map<String, VmResult>
    override fun close() {}
}

class ReferenceFleet(prepared: PreparedRun) : VmFleet {
    override val mode = "reference"
    override val pids = emptyMap<String, Int>()
    private val vms = prepared.manifest.instances.associate { instance -> instance.id to
        ReferenceVm(instance.id, prepared.program, instance.behavior, instance.params, prepared.scenario.seed) }
    override fun step(frames: Map<String, VmFrame>) = vms.mapValues { (id, vm) -> vm.step(frames.getValue(id)) }
}
