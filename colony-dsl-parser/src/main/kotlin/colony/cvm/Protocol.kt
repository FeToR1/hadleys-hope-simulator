package colony.cvm

import kotlinx.serialization.json.*

/**
 * Host side of the protocol one VM process speaks (docs/cvm-v2.md, section 8). Values travel by their declared
 * type; JSON is only how this side names them, which keeps the host and the reference runtime comparable.
 */
object Protocol {
    const val VERSION = 1
    const val INIT = 1
    const val READY = 2
    const val FRAME = 3
    const val RESULT = 4
    const val STOP = 5
    const val FAULT = 6

    /** The v1 intrinsic each intent opcode stands for, so intents read the same on both sides. */
    fun intentName(opcode: Int): String = when (opcode) {
        Op.POWER.code -> "POWER_REQUEST"
        Op.MOTION.code -> "MOTION_REQUEST"
        Op.DAMAGE.code -> "DAMAGE_REQUEST"
        Op.REPAIR.code -> "REPAIR_REQUEST"
        else -> error("Not an intent opcode: $opcode")
    }
}

/** Writes a value of a declared type; an optional value is preceded by a presence byte. */
fun writeValue(out: Sink, program: Program, type: VType, value: JsonElement) {
    if (type is VType.Opt) {
        val inner = if (value is JsonObject && value.keys == setOf("some")) value.getValue("some") else null
        if (value == JsonNull || inner == null && value == JsonNull) { out.u8(0); return }
        if (inner == null) {
            // A bare value where an Option is expected: accepted, so scenarios can stay short.
            out.u8(1); writeValue(out, program, type.inner, value); return
        }
        out.u8(1); writeValue(out, program, type.inner, inner); return
    }
    when (type) {
        VType.Bool -> out.u8(if (value.jsonPrimitive.boolean) 1 else 0)
        VType.I64 -> out.u64(value.jsonPrimitive.long)
        VType.F64 -> out.u64(java.lang.Double.doubleToRawLongBits(value.jsonPrimitive.double))
        VType.Str -> out.text(value.jsonPrimitive.content)
        is VType.List -> {
            val array = value.jsonArray
            out.uvarint(array.size)
            array.forEach { writeValue(out, program, type.element, it) }
        }
        is VType.Rec -> {
            val schema = program.schemas.single { it.name == type.schema }
            val record = value.jsonObject
            for (field in schema.fields) {
                writeValue(out, program, field.type, record[field.name] ?: error("Missing field ${field.name} of ${schema.name}"))
            }
        }
        VType.NoneT -> out.u8(0)
        is VType.Opt -> error("handled above")
    }
}

/** Reads a value of a declared type back into the JSON shape the reference runtime uses. */
fun readValue(src: Source, program: Program, type: VType): JsonElement {
    if (type is VType.Opt) {
        if (src.u8() == 0) return JsonNull
        return buildJsonObject { put("some", readValue(src, program, type.inner)) }
    }
    return when (type) {
        VType.Bool -> JsonPrimitive(src.u8() != 0)
        VType.I64 -> JsonPrimitive(src.u64())
        VType.F64 -> JsonPrimitive(java.lang.Double.longBitsToDouble(src.u64()))
        VType.Str -> JsonPrimitive(src.text())
        is VType.List -> JsonArray(List(src.uvarintInt()) { readValue(src, program, type.element) })
        is VType.Rec -> {
            val schema = program.schemas.single { it.name == type.schema }
            buildJsonObject { for (field in schema.fields) put(field.name, readValue(src, program, field.type)) }
        }
        VType.NoneT -> JsonNull
        is VType.Opt -> error("handled above")
    }
}

/** Reads a self-describing value, which is how intent arguments travel. */
fun readDynamic(src: Source, program: Program): JsonElement = when (val tag = src.u8()) {
    0 -> JsonNull
    1 -> JsonPrimitive(src.u8() != 0)
    2 -> JsonPrimitive(src.u64())
    3 -> JsonPrimitive(java.lang.Double.longBitsToDouble(src.u64()))
    4 -> JsonPrimitive(src.text())
    6 -> JsonArray(List(src.uvarintInt()) { readDynamic(src, program) })
    7 -> {
        val name = src.text()
        val schema = program.schemas.single { it.name == name }
        val count = src.uvarintInt()
        require(count == schema.fields.size) { "Record does not match ${schema.name}" }
        buildJsonObject { for (field in schema.fields) put(field.name, readDynamic(src, program)) }
    }
    else -> error("Unknown value tag $tag")
}

/** Strings are length-prefixed UTF-8, as everywhere else in the format. */
fun Sink.text(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    uvarint(bytes.size)
    bytes(bytes)
}

fun Source.text(): String = String(bytes(uvarintInt()), Charsets.UTF_8)
