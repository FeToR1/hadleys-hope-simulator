package colony.cvm

import java.security.MessageDigest

/** Sections of the .cvm container (docs/cvm-v2.md, section 3). */
object Section {
    const val STRINGS = 1; const val SCHEMAS = 2; const val EVENTS = 3; const val BEHAVIORS = 4; const val HASH = 255
}

const val CVM_FORMAT_VERSION = 2

private fun writeType(out: Sink, type: VType, schemas: List<String>) {
    when (type) {
        VType.Bool -> out.u8(1)
        VType.I64 -> out.u8(2)
        VType.F64 -> out.u8(3)
        VType.Str -> out.u8(4)
        is VType.Opt -> { out.u8(5); writeType(out, type.inner, schemas) }
        is VType.List -> { out.u8(6); writeType(out, type.element, schemas) }
        is VType.Rec -> { out.u8(7); out.uvarint(schemas.indexOf(type.schema).also { require(it >= 0) }) }
        VType.NoneT -> error("The type of `none` never reaches an artifact")
    }
}

/** A record type is stored as a schema index, which may point forward, so it is named in a second pass. */
private fun readType(src: Source): VType = when (val tag = src.u8()) {
    1 -> VType.Bool
    2 -> VType.I64
    3 -> VType.F64
    4 -> VType.Str
    5 -> VType.Opt(readType(src))
    6 -> VType.List(readType(src))
    7 -> VType.Rec("#" + src.uvarintInt())
    else -> error("Unknown type tag $tag")
}

private fun resolve(type: VType, names: List<String>): VType = when (type) {
    is VType.Opt -> VType.Opt(resolve(type.inner, names))
    is VType.List -> VType.List(resolve(type.element, names))
    is VType.Rec -> if (type.schema.startsWith("#")) {
        VType.Rec(names.getOrNull(type.schema.drop(1).toInt()) ?: error("Schema index out of range"))
    } else type
    else -> type
}

/** Writes the binary artifact that `hh-vm` loads. */
object ArtifactWriter {
    fun write(program: Program): ByteArray {
        val schemaNames = program.schemas.map { it.name }
        val body = Sink()
        body.bytes("CVM2".toByteArray(Charsets.US_ASCII))
        body.u16(CVM_FORMAT_VERSION)
        body.u16(program.contract)
        body.u32(4)
        body.u32(0)

        section(body, Section.STRINGS) { out ->
            out.uvarint(program.strings.size)
            for (text in program.strings) { val raw = text.toByteArray(Charsets.UTF_8); out.uvarint(raw.size); out.bytes(raw) }
        }
        section(body, Section.SCHEMAS) { out ->
            out.uvarint(program.schemas.size)
            for (schema in program.schemas) {
                out.uvarint(index(program.strings, schema.name)); out.uvarint(schema.fields.size)
                for (field in schema.fields) { out.uvarint(index(program.strings, field.name)); writeType(out, field.type, schemaNames) }
            }
        }
        section(body, Section.EVENTS) { out ->
            out.uvarint(program.events.size)
            for (event in program.events) { out.uvarint(event.id); out.uvarint(index(program.strings, event.name)); out.uvarint(event.schema) }
        }
        section(body, Section.BEHAVIORS) { out ->
            out.uvarint(index(program.strings, program.stepSeconds))
            out.uvarint(program.behaviors.size)
            for (behavior in program.behaviors) writeBehavior(out, behavior, program.strings, schemaNames)
        }
        val hashed = body.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(hashed)
        val out = Sink()
        out.bytes(hashed)
        out.u8(Section.HASH); out.u32(32); out.bytes(digest)
        return out.toByteArray()
    }

    /** SHA-256 of everything before the hash section: the identity of a program. */
    fun hashOf(artifact: ByteArray): ByteArray = artifact.copyOfRange(artifact.size - 32, artifact.size)

    private fun index(strings: List<String>, text: String) = strings.indexOf(text).also { require(it >= 0) { "String '$text' is not in the table" } }

    private fun section(out: Sink, id: Int, body: (Sink) -> Unit) {
        val inner = Sink()
        body(inner)
        val bytes = inner.toByteArray()
        out.u8(id); out.u32(bytes.size.toLong()); out.bytes(bytes)
    }

    private fun writeSlots(out: Sink, slots: List<Slot>, strings: List<String>, schemas: List<String>) {
        out.uvarint(slots.size)
        for (slot in slots) { out.uvarint(index(strings, slot.name)); writeType(out, slot.type, schemas) }
    }

    private fun writeBehavior(out: Sink, behavior: Behavior, strings: List<String>, schemas: List<String>) {
        out.uvarint(index(strings, behavior.name))
        out.uvarint(index(strings, behavior.kind))
        out.u32(behavior.capabilities.toLong())
        writeSlots(out, behavior.params, strings, schemas)
        writeSlots(out, behavior.state, strings, schemas)
        writeSlots(out, behavior.observes, strings, schemas)
        out.uvarint(behavior.handlers.size)
        for (handler in behavior.handlers) {
            out.uvarint(index(strings, handler.name))
            out.u8(if (handler.isTimer) 1 else 0)
            out.uvarint(handler.eventOrPeriod)
            out.u32(handler.entry.toLong())
            out.uvarint(handler.locals.size)
            for (type in handler.locals) writeType(out, type, schemas)
        }
        out.u32(behavior.initEntry.toLong())
        out.uvarint(behavior.initLocals.size)
        for (type in behavior.initLocals) writeType(out, type, schemas)
        out.uvarint(behavior.maxStack)
        out.u32(behavior.code.size.toLong())
        out.bytes(behavior.code)
        out.uvarint(behavior.sourceMap.size)
        var previous = 0
        for ((offset, line, column) in behavior.sourceMap) {
            out.uvarint(offset - previous); out.uvarint(line); out.uvarint(column); previous = offset
        }
    }
}

/** Reads back an artifact; used by tests and the disassembler, and mirrored by the C++ loader. */
object ArtifactReader {
    fun read(bytes: ByteArray): Program {
        require(bytes.size > 37) { "Artifact too small" }
        val hashed = bytes.copyOfRange(0, bytes.size - 37)
        val digest = MessageDigest.getInstance("SHA-256").digest(hashed)
        require(digest.contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))) { "Artifact hash does not match" }
        val src = Source(bytes)
        require(String(src.bytes(4), Charsets.US_ASCII) == "CVM2") { "Not a CVM artifact" }
        require(src.u16() == CVM_FORMAT_VERSION) { "Unsupported artifact format" }
        val contract = src.u16()
        src.u32(); src.u32()

        var strings = emptyList<String>()
        var schemas = emptyList<Schema>()
        var events = emptyList<EventDef>()
        var behaviors = emptyList<Behavior>()
        var step = ""
        while (src.remaining > 0) {
            val id = src.u8()
            val length = src.u32().toInt()
            val body = src.sub(length)
            when (id) {
                Section.STRINGS -> strings = List(body.uvarintInt()) { String(body.bytes(body.uvarintInt()), Charsets.UTF_8) }
                Section.SCHEMAS -> {
                    val raw = List(body.uvarintInt()) {
                        val name = strings[body.uvarintInt()]
                        Schema(name, List(body.uvarintInt()) { SchemaField(strings[body.uvarintInt()], readType(body)) })
                    }
                    val names = raw.map { it.name }
                    schemas = raw.map { schema -> Schema(schema.name, schema.fields.map { SchemaField(it.name, resolve(it.type, names)) }) }
                }
                Section.EVENTS -> events = List(body.uvarintInt()) { EventDef(body.uvarintInt(), strings[body.uvarintInt()], body.uvarintInt()) }
                Section.BEHAVIORS -> {
                    step = strings[body.uvarintInt()]
                    behaviors = List(body.uvarintInt()) { readBehavior(body, strings, schemas.map { it.name }) }
                }
                Section.HASH -> Unit
                else -> error("Unknown section $id")
            }
        }
        return Program(contract, step, strings, schemas, events, behaviors)
    }

    private fun readSlots(src: Source, strings: List<String>, schemas: List<String>) =
        List(src.uvarintInt()) { Slot(strings[src.uvarintInt()], resolve(readType(src), schemas)) }

    private fun readBehavior(src: Source, strings: List<String>, schemas: List<String>): Behavior {
        val name = strings[src.uvarintInt()]
        val kind = strings[src.uvarintInt()]
        val capabilities = src.u32().toInt()
        val params = readSlots(src, strings, schemas)
        val state = readSlots(src, strings, schemas)
        val observes = readSlots(src, strings, schemas)
        val handlers = List(src.uvarintInt()) {
            val handlerName = strings[src.uvarintInt()]
            val timer = src.u8() == 1
            val value = src.uvarint()
            val entry = src.u32().toInt()
            Handler(handlerName, timer, value, entry, List(src.uvarintInt()) { resolve(readType(src), schemas) })
        }
        val initEntry = src.u32().toInt()
        val initLocals = List(src.uvarintInt()) { resolve(readType(src), schemas) }
        val maxStack = src.uvarintInt()
        val code = src.bytes(src.u32().toInt())
        var previous = 0
        val map = List(src.uvarintInt()) {
            val offset = previous + src.uvarintInt(); previous = offset
            Triple(offset, src.uvarintInt(), src.uvarintInt())
        }
        return Behavior(name, kind, capabilities, params, state, observes, handlers, initEntry, initLocals, maxStack, code, map)
    }
}

/** Reading form of a whole artifact, for the CLI and tests. */
fun disassemble(program: Program): List<String> {
    val lines = ArrayList<String>()
    lines += "contract ${program.contract}, step ${program.stepSeconds}s, ${program.strings.size} strings"
    for (schema in program.schemas) lines += "schema ${schema.name} { ${schema.fields.joinToString { it.name + ": " + it.type.render() }} }"
    for (event in program.events) lines += "event #${event.id} ${event.name}"
    for (behavior in program.behaviors) {
        lines += ""
        lines += "behavior ${behavior.name} for ${behavior.kind} (capabilities ${behavior.capabilities}, stack ${behavior.maxStack}, ${behavior.code.size} bytes)"
        lines += "  params ${behavior.params.joinToString { it.name + ": " + it.type.render() }}"
        lines += "  state ${behavior.state.joinToString { it.name + ": " + it.type.render() }}"
        lines += "  observes ${behavior.observes.joinToString { it.name + ": " + it.type.render() }}"
        lines += "  init @${"%04x".format(behavior.initEntry)} with ${behavior.initLocals.size} locals"
        for (handler in behavior.handlers) {
            val trigger = if (handler.isTimer) "every ${handler.eventOrPeriod} ticks" else "on event ${handler.eventOrPeriod}"
            lines += "  handler ${handler.name} @${"%04x".format(handler.entry)} $trigger, ${handler.locals.size} locals"
        }
        val positions = behavior.sourceMap.associate { it.first to (it.second to it.third) }
        for (line in Disassembler.disassemble(behavior.code, program.strings)) {
            val offset = line.substringBefore("  ").toInt(16)
            val where = positions[offset]?.let { "  ; ${it.first}:${it.second}" } ?: ""
            lines += "  $line$where"
        }
    }
    return lines
}
