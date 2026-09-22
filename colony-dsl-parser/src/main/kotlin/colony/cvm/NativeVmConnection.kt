package colony.cvm

import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives one `hh-vm` process over the host protocol, the way the future broker will. Everything crossing the
 * pipe is binary; JSON appears only so a step can be compared with what the reference runtime produced.
 */
class NativeVmConnection(
    executable: Path,
    artifact: Path,
    private val program: Program,
    private val behaviorName: String,
    val entityId: String,
    seed: Long,
    params: JsonObject,
    /** When set, every message of this session is appended so another build can replay it byte for byte. */
    private val recording: java.io.OutputStream? = null,
    private val timeoutMillis: Long = 10_000,
    runId: String = "conformance",
    onProcess: (Process) -> Unit = {},
) : AutoCloseable {
    private val behavior = program.behaviors.single { it.name == behaviorName }
    private val process = ProcessBuilder(executable.toString(), "--artifact", artifact.toString(), "--stdio")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start().also(onProcess)
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = process.outputStream.buffered()

    /** State right after the initializers, as the process reports it. */
    val initialState: JsonObject
    val pid: Long
    val processHandle: ProcessHandle get() = process.toHandle()

    class StepOutcome(val failure: String?, val intents: List<JsonObject>, val events: List<JsonObject>, val state: JsonObject, val instructions: Int)

    init {
      try {
        require(timeoutMillis > 0)
        val ready = timed {
        val message = Sink()
        message.u16(Protocol.VERSION)
        message.text(runId)
        message.text(entityId)
        message.text(behaviorName)
        message.u64(seed)
        message.bytes(ArtifactWriter.hashOf(Files.readAllBytes(artifact)))
        for (slot in behavior.params) {
            writeValue(message, program, slot.type, params[slot.name] ?: error("Missing parameter ${slot.name}"))
        }
        send(Protocol.INIT, message)
        receive(Protocol.READY)
        }
        require(ready.u16() == Protocol.VERSION) { "The process speaks another protocol version" }
        pid = ready.u32()
        require(pid == process.pid()) { "VM $entityId returned the wrong PID" }
        initialState = readState(ready)
        require(ready.remaining == 0) { "Trailing READY data" }
      } catch (failure: Exception) { close(); throw failure }
    }

    fun step(tick: Long, view: JsonObject, events: List<JsonObject>): StepOutcome = timed {
        val message = Sink()
        message.uvarint(tick)
        for (slot in behavior.observes) {
            writeValue(message, program, slot.type, view[slot.name] ?: error("Frame has no observation ${slot.name}"))
        }
        message.uvarint(events.size)
        for (event in events) {
            val id = event.getValue("eventId").jsonPrimitive.int
            val definition = program.events.single { it.id == id }
            val schema = program.schemas[definition.schema]
            message.uvarint(id)
            message.text(event.getValue("sender").jsonPrimitive.content)
            message.uvarint(event.getValue("sequence").jsonPrimitive.long)
            val fields = event.getValue("fields").jsonObject
            for (field in schema.fields) {
                writeValue(message, program, field.type, fields[field.name] ?: error("Event ${schema.name} has no field ${field.name}"))
            }
        }
        send(Protocol.FRAME, message)

        val result = receive(Protocol.RESULT)
        val status = result.u8()
        require(status in 0..1) { "Invalid RESULT status" }
        if (status == 1) {
            val failure = result.text()
            val state = readState(result)
            require(result.remaining == 0) { "Trailing failed RESULT data" }
            return@timed StepOutcome(failure, emptyList(), emptyList(), state, 0)
        }
        val intents = List(result.uvarintInt()) {
            val operation = Protocol.intentName(result.u8())
            val arguments = List(result.uvarintInt()) { readDynamic(result, program) }
            buildJsonObject { put("operation", operation); put("arguments", JsonArray(arguments)) }
        }
        val outgoing = List(result.uvarintInt()) {
            val target = result.text()
            val id = result.uvarintInt()
            val sequence = result.uvarint()
            val schema = program.schemas[program.events.single { it.id == id }.schema]
            val fields = buildJsonObject { for (field in schema.fields) put(field.name, readValue(result, program, field.type)) }
            buildJsonObject { put("target", target); put("eventId", id); put("sequence", sequence); put("fields", fields) }
        }
        val state = readState(result)
        val instructions = result.u32().toInt()
        require(result.remaining == 0) { "The process sent more than the result" }
        StepOutcome(null, intents, outgoing, state, instructions)
    }

    private fun readState(src: Source): JsonObject =
        buildJsonObject { for (slot in behavior.state) put(slot.name, readValue(src, program, slot.type)) }

    private fun send(type: Int, body: Sink) {
        val bytes = body.toByteArray()
        require(bytes.size < MAX_MESSAGE) { "VM frame exceeds 64 MiB" }
        val header = Sink()
        header.u32((bytes.size + 1).toLong())
        header.u8(type)
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
        record(0, header.toByteArray() + bytes)
    }

    private fun receive(expected: Int): Source {
        val header = ByteArray(4)
        input.readFully(header)
        var length = 0L
        for (i in 0 until 4) length = length or ((header[i].toLong() and 0xFF) shl (8 * i))
        require(length in 1..MAX_MESSAGE.toLong()) { "Invalid VM message length $length" }
        val body = ByteArray(length.toInt())
        input.readFully(body)
        record(1, header + body)
        val source = Source(body)
        val type = source.u8()
        if (type == Protocol.FAULT) error("The VM reported a fault: ${source.text()}")
        require(type == expected) { "Expected message $expected but got $type" }
        return source
    }

    /** A message as it crossed the pipe: direction, length, bytes. */
    private fun record(direction: Int, bytes: ByteArray) {
        val out = recording ?: return
        out.write(direction)
        for (i in 0 until 4) out.write((bytes.size ushr (8 * i)) and 0xFF)
        out.write(bytes)
    }

    private fun <T> timed(action: () -> T): T {
        val expired = AtomicBoolean(false)
        val timer = watchdog.schedule({ expired.set(true); process.destroyForcibly() }, timeoutMillis, TimeUnit.MILLISECONDS)
        try { return action() }
        catch (failure: Exception) {
            val reason = if (expired.get()) "response timeout after $timeoutMillis ms" else (failure.message ?: failure.javaClass.simpleName)
            throw IllegalStateException("VM $entityId (PID ${process.pid()}): $reason", failure)
        } finally { timer.cancel(false) }
    }

    override fun close() {
        // Destroy first: this also unblocks a stuck reader/writer, including a concurrent timeout.
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        runCatching { output.close() }; runCatching { input.close() }
    }

    companion object {
        private const val MAX_MESSAGE = 64 * 1024 * 1024
        private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "native-vm-deadlines").apply { isDaemon = true } }
        /** The built VM, or null when the native build has not run; the tests then report themselves as skipped. */
        fun executable(): Path? {
            // HH_VM lets the same tests drive a sanitizer build, or any other build, without changing them.
            System.getenv("HH_VM")?.let { return File(it).toPath().toAbsolutePath() }
            val candidates = listOf("native/build/hh-vm.exe", "native/build/hh-vm", "../native/build/hh-vm.exe", "../native/build/hh-vm")
            return candidates.map { File(it) }.firstOrNull { it.isFile }?.toPath()?.toAbsolutePath()
        }
    }
}
