package colony.runtime

import colony.compiler.CompiledProgram
import colony.compiler.Op
import colony.cvm.ArtifactWriter
import colony.cvm.NativeVmConnection
import colony.cvm.Program
import kotlinx.serialization.json.*
import java.nio.file.Files

/** A single native context for regression replay. All execution happens in hh-vm. */
class NativeVm(
    val entityId: String,
    program: Program,
    behaviorName: String,
    parameters: JsonObject = JsonObject(emptyMap()),
    seed: Long = 426,
) : AutoCloseable {
    constructor(entityId: String, program: CompiledProgram, behaviorName: String,
                parameters: JsonObject = JsonObject(emptyMap()), seed: Long = 426) :
        this(entityId, program.nativeProgram, behaviorName, parameters, seed)

    private val artifact = Files.createTempFile("colony-context-", ".cvm")
    private val connection: NativeVmConnection
    private var state: JsonObject
    private var closed = false

    init {
        try {
            Files.write(artifact, ArtifactWriter.write(program))
            connection = NativeVmConnection(NativeVmConnection.executable() ?: error("Build native/ or set HH_VM"),
                artifact, program, behaviorName, entityId, seed, parameters)
            state = connection.initialState
            NativeResources.track(this)
        } catch (failure: Exception) { Files.deleteIfExists(artifact); throw failure }
    }

    fun stateSnapshot(): JsonObject = state

    fun step(frame: VmFrame): VmResult {
        check(!closed) { "Native context is closed" }
        val events = frame.events.map { event -> buildJsonObject {
            put("eventId", event.eventId); put("sender", event.sender)
            put("sequence", event.sequence); put("fields", event.fields)
        } }
        val result = connection.step(frame.tick, frame.view, events)
        state = result.state
        check(result.failure == null) { result.failure.orEmpty() }
        return VmResult(result.intents.map { intent -> VmIntent(entityId,
            Op.valueOf(intent.getValue("operation").jsonPrimitive.content), intent.getValue("arguments").jsonArray.toList()) },
            result.events.map { event -> OutgoingEvent(event.getValue("target").jsonPrimitive.content,
                event.getValue("eventId").jsonPrimitive.int, event.getValue("fields").jsonObject,
                entityId, event.getValue("sequence").jsonPrimitive.long) }, state)
    }

    override fun close() {
        if (closed) return
        closed = true
        NativeResources.release(this)
        try { connection.close() } finally { Files.deleteIfExists(artifact) }
    }
}
