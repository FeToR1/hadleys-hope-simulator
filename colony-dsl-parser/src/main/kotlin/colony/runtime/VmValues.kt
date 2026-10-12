package colony.runtime

import colony.compiler.Op
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

@Serializable data class DeliveredEvent(val eventId: Int, val fields: JsonObject, val sender: String = "scenario", val sequence: Long = 0)
@Serializable data class OutgoingEvent(val target: String, val eventId: Int, val fields: JsonObject, val sender: String, val sequence: Long)
@Serializable data class VmIntent(val source: String, val operation: Op, val arguments: List<JsonElement>)
@Serializable data class VmFrame(val tick: Long, val view: JsonObject, val events: List<DeliveredEvent> = emptyList())
@Serializable data class VmResult(val intents: List<VmIntent>, val events: List<OutgoingEvent>, val state: JsonObject)

/** The bytes hashed for one draw: seed, four length-prefixed UTF-8 keys, counter; big-endian, as documented. */
internal fun randomInput(seed: Long, entityId: String, behavior: String, rule: String, site: String, counter: Long): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeLong(seed)
        for (value in listOf(entityId, behavior, rule, site)) {
            val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded)
        }
        output.writeLong(counter)
    }
    return bytes.toByteArray()
}

/** The first eight bytes of the SHA-256 digest as a big-endian long. */
internal fun randomDigestHead(input: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(input)).long

/** SHA-256 counter stream: the top 53 bits of the digest head divided by 2^53, a number in [0, 1). */
internal fun randomUnit(seed: Long, entityId: String, behavior: String, rule: String, site: String, counter: Long): Double =
    (randomDigestHead(randomInput(seed, entityId, behavior, rule, site, counter)) ushr 11).toDouble() / 9007199254740992.0

internal fun number(value: JsonElement): Double {
    val primitive = value.jsonPrimitive
    require(!primitive.isString) { "Expected a number" }
    return primitive.double.also { require(it.isFinite()) { "Non-finite number" } }
}
