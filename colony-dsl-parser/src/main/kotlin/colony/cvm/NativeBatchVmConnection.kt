package colony.cvm

import colony.runtime.VmFrame
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One native process hosts many isolated VM contexts while sharing its loaded, verified program image. */
class NativeBatchVmConnection(
    executable: Path,
    artifact: Path,
    private val program: Program,
    contexts: List<ContextInit>,
    runId: String,
    private val timeoutMillis: Long = 10_000,
    private val startupTimeoutMillis: Long = 120_000,
    onProcess: (Process) -> Unit = {},
) : AutoCloseable {
    data class ContextInit(val entityId: String, val behaviorName: String, val seed: Long, val params: JsonObject)
    data class ContextReady(val pid: Int, val initialState: JsonObject)
    private val definitions = contexts.associateBy { it.entityId }
    private val behaviors = contexts.associate { context -> context.entityId to program.behaviors.single { it.name == context.behaviorName } }
    private val eventDefinitions = program.events.associateBy { it.id }
    private val process = ProcessBuilder(executable.toString(), "--artifact", artifact.toString(), "--stdio", "--batch")
        .redirectError(ProcessBuilder.Redirect.INHERIT).start().also(onProcess)
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = process.outputStream.buffered()
    private val closed = AtomicBoolean(false)
    val pid: Int get() = process.pid().toInt()
    val processHandle: ProcessHandle get() = process.toHandle()
    val contextReady: Map<String, ContextReady>

    init {
        try {
            require(contexts.isNotEmpty() && contexts.map { it.entityId }.toSet().size == contexts.size)
            require(timeoutMillis > 0 && startupTimeoutMillis > 0)
            val hash = ArtifactWriter.hashOf(Files.readAllBytes(artifact))
            contextReady = timed(startupTimeoutMillis) { contexts.chunked(MAX_BATCH).flatMap { batch ->
                val body = Sink().also { out ->
                    out.u16(Protocol.VERSION); out.text(runId); out.bytes(hash); out.uvarint(batch.size)
                    batch.forEach { context ->
                        val behavior = behaviors.getValue(context.entityId)
                        out.text(context.entityId); out.text(context.behaviorName); out.u64(context.seed)
                        behavior.params.forEach { slot -> writeValue(out, program, slot.type, context.params[slot.name] ?: error("Missing parameter ${slot.name}")) }
                    }
                }
                send(Protocol.BATCH_INIT, body)
                val ready = receive(Protocol.BATCH_READY)
                require(ready.u16() == Protocol.VERSION)
                val count = ready.uvarintInt()
                require(count == batch.size)
                List(count) {
                    val id = ready.text()
                    val context = definitions[id] ?: error("Unexpected context $id in BATCH_READY")
                    val behavior = behaviors.getValue(id)
                    val contextPid = ready.u32().toInt()
                    require(contextPid == pid) { "Context $id returned an unexpected shared PID" }
                    id to ContextReady(contextPid, buildJsonObject {
                        behavior.state.forEach { slot -> put(slot.name, readValue(ready, program, slot.type)) }
                    })
                }.also {
                    require(it.map { pair -> pair.first }.toSet() == batch.map { it.entityId }.toSet()) { "BATCH_READY IDs do not match the batch" }
                    require(ready.remaining == 0) { "Trailing BATCH_READY data" }
                }
            }.toMap().also { require(it.keys == definitions.keys) { "BATCH_READY does not cover all contexts" } } }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** Frames are sent in bounded packets. No caller-visible results escape until every packet succeeds. */
    fun step(tick: Long, frames: Map<String, VmFrame>): Map<String, NativeVmConnection.StepOutcome> = timed {
        check(!closed.get()) { "Native shared process is closed" }
        require(frames.keys == definitions.keys && frames.values.all { it.tick == tick }) { "Frame set does not match shared contexts" }
        val outcomes = LinkedHashMap<String, NativeVmConnection.StepOutcome>(frames.size)
        frames.entries.toList().chunked(MAX_BATCH).forEach { batch ->
            val body = Sink().also { out ->
                out.uvarint(tick); out.uvarint(batch.size)
                batch.forEach { (id, frame) ->
                    val behavior = behaviors.getValue(id)
                    out.text(id)
                    behavior.observes.forEach { slot -> writeValue(out, program, slot.type, frame.view[slot.name] ?: error("Frame has no observation ${slot.name}")) }
                    out.uvarint(frame.events.size)
                    frame.events.forEach { event ->
                        val definition = eventDefinitions[event.eventId] ?: error("Unknown event ${event.eventId}")
                        val schema = program.schemas[definition.schema]
                        out.uvarint(event.eventId); out.text(event.sender); out.uvarint(event.sequence)
                        schema.fields.forEach { field -> writeValue(out, program, field.type, event.fields[field.name] ?: error("Event ${schema.name} missing ${field.name}")) }
                    }
                }
            }
            send(Protocol.BATCH_FRAME, body)
            val result = receive(Protocol.BATCH_RESULT)
            require(result.uvarintInt() == batch.size) { "Incomplete BATCH_RESULT" }
            val expectedIds = batch.mapTo(HashSet()) { it.key }
            val actualIds = HashSet<String>()
            repeat(batch.size) {
                val id = result.text()
                require(id in expectedIds && actualIds.add(id)) { "Unexpected or duplicate result context $id" }
                val behavior = behaviors.getValue(id)
                val payload = result.sub(result.uvarintInt())
                val status = payload.u8()
                require(status in 0..1) { "Invalid result status for $id" }
                if (status == 1) {
                    val failure = payload.text()
                    val state = readState(payload, behavior)
                    require(payload.remaining == 0)
                    outcomes[id] = NativeVmConnection.StepOutcome(failure, emptyList(), emptyList(), state, 0)
                } else {
                    val intents = List(payload.uvarintInt()) {
                        val operation = Protocol.intentName(payload.u8())
                        val args = List(payload.uvarintInt()) { readDynamic(payload, program) }
                        buildJsonObject { put("operation", operation); put("arguments", JsonArray(args)) }
                    }
                    val events = List(payload.uvarintInt()) {
                        val target = payload.text(); val eventId = payload.uvarintInt(); val sequence = payload.uvarint()
                        val schema = program.schemas[(eventDefinitions[eventId] ?: error("Unknown event $eventId")).schema]
                        val fields = buildJsonObject { schema.fields.forEach { field -> put(field.name, readValue(payload, program, field.type)) } }
                        buildJsonObject { put("target", target); put("eventId", eventId); put("sequence", sequence); put("fields", fields) }
                    }
                    val state = readState(payload, behavior)
                    val instructions = payload.u32().toInt()
                    require(payload.remaining == 0) { "Trailing context result data" }
                    outcomes[id] = NativeVmConnection.StepOutcome(null, intents, events, state, instructions)
                }
            }
            require(actualIds == expectedIds) { "BATCH_RESULT omitted a context" }
            require(result.remaining == 0) { "Trailing BATCH_RESULT data" }
        }
        if (outcomes.values.any { it.failure != null }) close()
        outcomes
    }

    private fun readState(source: Source, behavior: Behavior): JsonObject = buildJsonObject {
        behavior.state.forEach { slot -> put(slot.name, readValue(source, program, slot.type)) }
    }

    private fun send(type: Int, body: Sink) {
        val bytes = body.toByteArray()
        require(bytes.size < MAX_MESSAGE) { "Native batch packet exceeds 64 MiB" }
        val header = Sink(); header.u32((bytes.size + 1).toLong()); header.u8(type)
        output.write(header.toByteArray()); output.write(bytes); output.flush()
    }

    private fun receive(expected: Int): Source {
        val header = ByteArray(4); input.readFully(header)
        var length = 0L
        for (i in 0..3) length = length or ((header[i].toLong() and 0xff) shl (8 * i))
        require(length in 1..MAX_MESSAGE.toLong()) { "Invalid native message length $length" }
        val body = ByteArray(length.toInt()); input.readFully(body)
        val source = Source(body); val type = source.u8()
        if (type == Protocol.FAULT) error("Native VM fault: ${source.text()}")
        require(type == expected) { "Expected native message $expected, received $type" }
        return source
    }

    private fun <T> timed(deadlineMillis: Long = timeoutMillis, block: () -> T): T {
        val expired = AtomicBoolean(false)
        val timer = watchdog.schedule({ expired.set(true); process.destroyForcibly() }, deadlineMillis, TimeUnit.MILLISECONDS)
        try { return block() }
        catch (failure: Exception) {
            val why = if (expired.get()) "response timeout after $deadlineMillis ms" else (failure.message ?: failure.javaClass.simpleName)
            close()
            throw IllegalStateException("Native shared process ${process.pid()}: $why", failure)
        } finally { timer.cancel(false) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        runCatching { output.close() }; runCatching { input.close() }
    }

    companion object {
        const val MAX_BATCH = 512
        private const val MAX_MESSAGE = 64 * 1024 * 1024
        private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "native-batch-deadlines").apply { isDaemon = true } }
        fun executable(): Path? = NativeVmConnection.executable()
    }
}
