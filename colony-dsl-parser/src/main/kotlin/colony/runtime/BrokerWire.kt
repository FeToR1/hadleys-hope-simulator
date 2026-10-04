package colony.runtime

import colony.bytecode.Op
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded binary protocol for the private JVM kernel/broker pipe. */
internal object BrokerWire {
    private const val MAGIC = 0x48484257 // HHBW
    private const val VERSION = 1
    private const val CONFIG = 1
    private const val REQUEST = 2
    private const val REPLY = 3
    private const val MAX_MESSAGE = 64 * 1024 * 1024
    private const val MAX_STRING = 1024 * 1024
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_PACKET_ENTITIES = 512

    fun writeConfig(out: DataOutputStream, value: BrokerConfig) = write(out, CONFIG) { config(value) }
    fun readConfig(input: DataInputStream): BrokerConfig = read(input, CONFIG) { config() }
    fun writeRequest(out: DataOutputStream, value: BrokerRequest) = write(out, REQUEST) { request(value) }
    fun readRequest(input: DataInputStream): BrokerRequest = read(input, REQUEST) { request() }
    fun writeReply(out: DataOutputStream, value: BrokerReply) = write(out, REPLY) { reply(value) }
    fun readReply(input: DataInputStream): BrokerReply = read(input, REPLY) { reply() }

    private fun write(out: DataOutputStream, kind: Int, body: BodyWriter.() -> Unit) {
        val buffer = CappedBuffer(MAX_MESSAGE).also { buffer ->
            DataOutputStream(buffer).use { data ->
                val writer = BodyWriter(data)
                data.writeInt(MAGIC); data.writeInt(VERSION); data.writeInt(kind)
                writer.body()
                data.flush()
            }
        }
        require(buffer.size() in 12..MAX_MESSAGE)
        out.writeInt(buffer.size()); buffer.writeTo(out); out.flush()
    }

    private fun <T> read(input: DataInputStream, kind: Int, body: BodyReader.() -> T): T {
        val size = input.readInt()
        require(size in 12..MAX_MESSAGE) { "Invalid broker message length" }
        val bytes = ByteArray(size); input.readFully(bytes)
        val data = DataInputStream(ByteArrayInputStream(bytes))
        require(data.readInt() == MAGIC) { "Invalid broker wire magic" }
        require(data.readInt() == VERSION) { "Unsupported broker wire version" }
        require(data.readInt() == kind) { "Unexpected broker message kind" }
        val reader = BodyReader(data)
        val result = reader.body()
        require(data.available() == 0) { "Trailing broker message data" }
        return result
    }

    private class CappedBuffer(private val max: Int) : ByteArrayOutputStream() {
        override fun write(b: Int) { require(size() < max) { "Broker message exceeds 64 MiB" }; super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { require(len >= 0 && size().toLong() + len <= max) { "Broker message exceeds 64 MiB" }; super.write(b, off, len) }
    }
    private class BodyWriter(private val d: DataOutputStream) {
        fun i(v: Int) = d.writeInt(v)
        fun l(v: Long) = d.writeLong(v)
        fun bool(v: Boolean) = d.writeBoolean(v)
        fun dbl(v: Double) { require(v.isFinite()); d.writeDouble(v) }
        fun str(v: String) { val b = v.toByteArray(Charsets.UTF_8); require(b.size <= MAX_STRING) { "Broker string exceeds 1 MiB" }; i(b.size); d.write(b) }
        fun nullable(v: String?) { bool(v != null); if (v != null) str(v) }
        fun count(v: Int, max: Int = MAX_ITEMS) { require(v in 0..max); i(v) }
        fun json(v: JsonElement, depth: Int = 0) {
            require(depth <= 64) { "JSON tree exceeds depth 64" }
            when (v) {
                JsonNull -> d.writeByte(0)
                is JsonPrimitive -> when {
                    v.isString -> { d.writeByte(4); str(v.content) }
                    v.booleanOrNull != null -> { d.writeByte(1); bool(v.boolean) }
                    v.longOrNull != null -> { d.writeByte(2); l(v.long) }
                    else -> { d.writeByte(3); dbl(v.double) }
                }
                is JsonArray -> { d.writeByte(5); count(v.size); v.forEach { json(it, depth + 1) } }
                is JsonObject -> { d.writeByte(6); count(v.size); v.forEach { (k, value) -> str(k); json(value, depth + 1) } }
            }
        }
        fun obj(v: JsonObject) = json(v)
        fun config(v: BrokerConfig) {
            str(v.artifact); str(v.executable); str(v.runId); l(v.parentPid); l(v.timeoutMillis); i(v.workers); l(v.startupTimeoutMillis)
            i(v.manifest.version); l(v.manifest.seed); str(v.manifest.stepSeconds); count(v.manifest.instances.size)
            v.manifest.instances.forEach { str(it.id); str(it.kind); str(it.behavior); obj(it.params); obj(it.view); nullable(it.parent); dbl(it.x); dbl(it.y) }
        }
        fun request(v: BrokerRequest) {
            i(v.version); l(v.tick); i(v.batchIndex); i(v.batchCount); count(v.frames.size, MAX_PACKET_ENTITIES)
            v.frames.forEach { (id, f) -> str(id); l(f.tick); obj(f.view); count(f.events.size); f.events.forEach { e -> i(e.eventId); obj(e.fields); str(e.sender); l(e.sequence) } }
        }
        fun reply(v: BrokerReply) {
            i(v.version); count(v.pids.size); v.pids.forEach { (id, pid) -> str(id); i(pid) }
            count(v.results.size, MAX_PACKET_ENTITIES); v.results.forEach { (id, r) ->
                str(id); count(r.intents.size); r.intents.forEach { intent -> str(intent.source); i(intent.operation.ordinal); str(intent.operation.name); count(intent.arguments.size); intent.arguments.forEach { json(it) } }
                count(r.events.size); r.events.forEach { e -> str(e.target); i(e.eventId); obj(e.fields); str(e.sender); l(e.sequence) }; obj(r.state)
            }
            nullable(v.error); i(v.batchIndex); i(v.batchCount); bool(v.tick != null); v.tick?.let(::l)
        }
    }
    private class BodyReader(private val d: DataInputStream) {
        fun i() = d.readInt()
        fun l() = d.readLong()
        fun bool(): Boolean = when (d.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid broker boolean") }
        fun dbl(): Double = d.readDouble().also { require(it.isFinite()) { "Non-finite broker number" } }
        fun str(): String { val size = i(); require(size in 0..MAX_STRING && size <= d.available()) { "Invalid broker string length" }; val b = ByteArray(size); d.readFully(b); return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString() }
        fun nullable(): String? = if (bool()) str() else null
        fun count(max: Int = MAX_ITEMS): Int = i().also {
            require(it in 0..max && it <= d.available()) { "Invalid broker collection count" }
        }
        fun json(depth: Int = 0): JsonElement {
            require(depth <= 64) { "JSON tree exceeds depth 64" }
            return when (d.readUnsignedByte()) {
                0 -> JsonNull
                1 -> JsonPrimitive(bool())
                2 -> JsonPrimitive(l())
                3 -> JsonPrimitive(dbl())
                4 -> JsonPrimitive(str())
                5 -> JsonArray(List(count()) { json(depth + 1) })
                6 -> { val n = count(); val m = LinkedHashMap<String, JsonElement>(n); repeat(n) { val k = str(); require(!m.containsKey(k)) { "Duplicate broker object key" }; m[k] = json(depth + 1) }; JsonObject(m) }
                else -> throw IllegalArgumentException("Unknown JSON wire tag")
            }
        }
        fun obj() = json() as? JsonObject ?: throw IllegalArgumentException("Expected broker object")
        fun config(): BrokerConfig {
            val artifact = str(); val executable = str(); val runId = str(); val parentPid = l(); val timeout = l(); val workers = i(); val startup = l()
            val version = i(); val seed = l(); val step = str(); val instances = List(count()) { Instance(str(), str(), str(), obj(), obj(), nullable(), dbl(), dbl()) }
            return BrokerConfig(artifact, executable, runId, parentPid, RunManifest(version, seed, step, instances), timeout, workers, startup)
        }
        fun request(): BrokerRequest {
            val version = i(); val tick = l(); val index = i(); val total = i(); val n = count(MAX_PACKET_ENTITIES); val frames = LinkedHashMap<String, VmFrame>(n)
            repeat(n) { val id = str(); require(!frames.containsKey(id)) { "Duplicate broker frame entity" }; frames[id] = VmFrame(l(), obj(), List(count()) { DeliveredEvent(i(), obj(), str(), l()) }) }
            return BrokerRequest(version, tick, index, total, frames)
        }
        fun reply(): BrokerReply {
            val version = i(); val pidsN = count(); val pids = LinkedHashMap<String, Int>(pidsN); repeat(pidsN) { val id = str(); require(!pids.containsKey(id)) { "Duplicate broker pid entity" }; pids[id] = i() }
            val resultN = count(MAX_PACKET_ENTITIES); val results = LinkedHashMap<String, VmResult>(resultN)
            repeat(resultN) { val id = str(); require(!results.containsKey(id)) { "Duplicate broker result entity" }
                val intents = List(count()) { val source = str(); val ordinal = i(); val name = str(); val op = Op.entries.getOrNull(ordinal); require(op != null && op.name == name) { "Invalid broker operation" }; VmIntent(source, op, List(count()) { json() }) }
                val events = List(count()) { OutgoingEvent(str(), i(), obj(), str(), l()) }; results[id] = VmResult(intents, events, obj())
            }
            val error = nullable(); val batchIndex = i(); val batchCount = i(); val tick = if (bool()) l() else null
            return BrokerReply(version, pids, results, error, batchIndex, batchCount, tick)
        }
    }
}
