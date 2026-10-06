package colony.runtime

import colony.bytecode.Op
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*

class BrokerWireTest {
    private fun <T> roundTrip(value: T, write: (DataOutputStream, T) -> Unit, read: (DataInputStream) -> T): T {
        val bytes = ByteArrayOutputStream().also { write(DataOutputStream(it), value) }.toByteArray()
        return read(DataInputStream(ByteArrayInputStream(bytes)))
    }

    @Test fun `config preserves full manifest including unused projected fields`() {
        val config = BrokerConfig("a", "exe", "run", Long.MAX_VALUE,
            RunManifest(1, Long.MIN_VALUE, "0.25s", listOf(Instance("α", "kind", "move",
                buildJsonObject { put("n", Long.MAX_VALUE) }, buildJsonObject { put("unused", "雪") }, "parent", -0.0, 4.5))),
            123, 3, 456)
        assertEquals(config, roundTrip(config, BrokerWire::writeConfig, BrokerWire::readConfig))
    }

    @Test fun `request and reply preserve tagged JSON trees, operation identity and ordering`() {
        val tree = buildJsonObject {
            put("long", JsonPrimitive(Long.MAX_VALUE)); put("negativeZero", JsonPrimitive(-0.0))
            put("unicode", "🌍\"\n\u0000\\"); put("nested", buildJsonArray { add(JsonNull); add(true); add(buildJsonObject { put("x", 7) }) })
        }
        val req = BrokerRequest(tick = 9, batchIndex = 0, batchCount = 1,
            frames = linkedMapOf("e" to VmFrame(9, tree, listOf(DeliveredEvent(3, tree, "源", 4)))))
        val decoded = roundTrip(req, BrokerWire::writeRequest, BrokerWire::readRequest)
        assertEquals(req, decoded)
        assertEquals(Long.MAX_VALUE, decoded.frames.getValue("e").view.getValue("long").jsonPrimitive.long)
        val result = VmResult(listOf(VmIntent("e", Op.MOTION_REQUEST, listOf(JsonPrimitive(Long.MAX_VALUE), tree))),
            listOf(OutgoingEvent("dest", 8, tree, "e", 12)), tree)
        val reply = BrokerReply(pids = linkedMapOf("e" to 42), results = linkedMapOf("e" to result), batchIndex = 0, tick = 9)
        assertEquals(reply, roundTrip(reply, BrokerWire::writeReply, BrokerWire::readReply))
    }

    @Test fun `rejects invalid envelopes, trailing bytes, oversized lengths and duplicate keys`() {
        fun reject(payload: ByteArray) {
            val framed = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(payload.size); write(payload) } }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { BrokerWire.readRequest(DataInputStream(ByteArrayInputStream(framed))) }
        }
        val valid = ByteArrayOutputStream().also { BrokerWire.writeRequest(DataOutputStream(it), BrokerRequest(tick = 0, batchIndex = 0, batchCount = 1, frames = emptyMap())) }.toByteArray().let { it.copyOfRange(4, it.size) }
        reject(valid.copyOf().also { it[3] = 0 }) // magic
        reject(valid.copyOf().also { it[7] = 2 }) // protocol version
        reject(valid.copyOf().also { it[11] = 3 }) // kind
        reject(valid + byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { BrokerWire.readRequest(DataInputStream(ByteArrayInputStream(byteArrayOf(0x7f, -1, -1, -1).map { it.toByte() }.toByteArray()))) }
        val dup = ByteArrayOutputStream().also { d -> DataOutputStream(d).apply {
            writeInt(0x48484257); writeInt(1); writeInt(2); writeInt(1); writeLong(0); writeInt(0); writeInt(1); writeInt(1)
            writeInt(1); writeByte('e'.code); writeLong(0); writeByte(6); writeInt(2)
            repeat(2) { writeInt(1); writeByte('x'.code); writeByte(0) }
        } }.toByteArray()
        reject(dup)
    }

    @Test fun `rejects truncated and invalid tagged values before allocating collections`() {
        fun requestWith(value: (DataOutputStream) -> Unit): ByteArray {
            val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).apply {
                writeInt(0x48484257); writeInt(1); writeInt(2)
                writeInt(1); writeLong(0); writeInt(0); writeInt(1); writeInt(1)
                writeInt(1); writeByte('e'.code); writeLong(0)
                writeByte(6); writeInt(1); writeInt(1); writeByte('v'.code)
                value(this); writeInt(0)
            } }.toByteArray()
            return ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(body.size); write(body) } }.toByteArray()
        }
        val invalidValues: List<(DataOutputStream) -> Unit> = listOf(
            { it.writeByte(1); it.writeByte(2) }, // invalid boolean
            { it.writeByte(3); it.writeDouble(Double.NaN) },
            { it.writeByte(4); it.writeInt(1); it.writeByte(0xff) }, // malformed UTF-8
            { it.writeByte(4); it.writeInt(1024 * 1024 + 1) },
            { it.writeByte(5); it.writeInt(Int.MAX_VALUE) },
            { it.writeByte(5); it.writeInt(-1) },
            { it.writeByte(99) },
            { out -> repeat(65) { out.writeByte(5); out.writeInt(1) }; out.writeByte(0) },
        )
        for (value in invalidValues) {
            val failure = assertThrows(Exception::class.java) { BrokerWire.readRequest(DataInputStream(ByteArrayInputStream(requestWith(value)))) }
            assertTrue(failure is IllegalArgumentException || failure is java.nio.charset.CharacterCodingException)
        }
        val valid = requestWith { it.writeByte(0) }
        for (length in listOf(0, 3, valid.size - 1)) {
            assertThrows(EOFException::class.java) { BrokerWire.readRequest(DataInputStream(ByteArrayInputStream(valid.copyOf(length)))) }
        }
    }

    @Test fun `writer rejects packet limits without publishing a partial message`() {
        fun rejects(request: BrokerRequest) {
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { BrokerWire.writeRequest(DataOutputStream(output), request) }
            assertEquals(0, output.size(), "An invalid packet must not publish its length or partial body")
        }
        val empty = JsonObject(emptyMap())
        rejects(BrokerRequest(tick = 0, batchIndex = 0, batchCount = 2,
            frames = (0..512).associate { "e$it" to VmFrame(0, empty) }))
        var deep: JsonElement = JsonNull
        repeat(65) { deep = JsonArray(listOf(deep)) }
        fun single(value: JsonElement) = BrokerRequest(tick = 0, batchIndex = 0, batchCount = 1,
            frames = mapOf("e" to VmFrame(0, buildJsonObject { put("v", value) })))
        rejects(single(deep))
        rejects(single(JsonPrimitive(Double.POSITIVE_INFINITY)))
        val megabyte = JsonPrimitive("x".repeat(1024 * 1024))
        rejects(single(JsonArray(List(65) { megabyte })))
    }
}
