package colony.cvm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Growable little-endian byte sink with LEB128 helpers. */
class Sink {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()
    fun u8(v: Int) { out.write(v and 0xFF) }
    fun u16(v: Int) { u8(v); u8(v ushr 8) }
    fun u32(v: Long) { for (i in 0 until 4) u8((v ushr (8 * i)).toInt()) }
    fun u64(v: Long) { for (i in 0 until 8) u8((v ushr (8 * i)).toInt()) }
    fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))
    fun uvarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { u8(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        u8(v.toInt())
    }
    fun uvarint(value: Int) = uvarint(value.toLong() and 0xFFFFFFFFL)
    fun zigzag(v: Long) = uvarint((v shl 1) xor (v shr 63))
    fun bytes(b: ByteArray) { out.write(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Bounds-checked little-endian reader. */
class Source(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    private fun need(n: Int) { require(pos in 0..end && end <= data.size && n >= 0 && n <= end - pos) { "Unexpected end of data at $pos" } }
    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); return v }
    fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); return v }
    fun f64(): Double = java.lang.Double.longBitsToDouble(u64())
    fun uvarint(): Long {
        var shift = 0; var v = 0L
        while (true) {
            val b = u8()
            require(shift != 63 || b and 0xFE == 0) { "Varint overflows 64 bits" }
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
    }
    fun uvarintInt(): Int { val v = uvarint(); require(v in 0..Int.MAX_VALUE) { "Varint out of range" }; return v.toInt() }
    fun zigzag(): Long { val v = uvarint(); return (v ushr 1) xor -(v and 1) }
    fun bytes(n: Int): ByteArray { need(n); val r = data.copyOfRange(pos, pos + n); pos += n; return r }
    fun sub(n: Int): Source { need(n); val s = Source(data, pos, pos + n); pos += n; return s }
    val remaining: Int get() = end - pos
}

/** Interned strings of one artifact, in first-use order. */
class StringPool {
    private val index = LinkedHashMap<String, Int>()
    fun of(text: String): Int = index.getOrPut(text) { index.size }
    val strings: List<String> get() = index.keys.toList()
}

object Codec {
    private const val STACK = 0; private const val IMM_I = 1; private const val IMM_F = 2; private const val IMM_S = 3
    private const val SMALL = 4; private const val VIEW = 5; private const val STATE = 6; private const val LOCAL = 7
    private const val PARAM = 8; private const val MSG = 9; private const val LOCAL_FIELD = 10; private const val VIEW_FIELD = 11
    private const val SPECIAL = 12
    private const val EXT = 15

    private fun spec(out: Sink, mode: Int, index: Int) {
        if (index < EXT) out.u8(mode shl 4 or index) else { out.u8(mode shl 4 or EXT); out.uvarint(index) }
    }

    fun encodeSrc(out: Sink, op: Operand, pool: StringPool) {
        when (op) {
            Operand.Stack -> out.u8(STACK shl 4)
            is Operand.ImmI -> when (op.value) {
                0L -> out.u8(SMALL shl 4 or 3); 1L -> out.u8(SMALL shl 4 or 4); -1L -> out.u8(SMALL shl 4 or 5)
                else -> { out.u8(IMM_I shl 4); out.zigzag(op.value) }
            }
            is Operand.ImmF -> when (op.value.toRawBits()) {
                0L -> out.u8(SMALL shl 4 or 6)
                1.0.toRawBits() -> out.u8(SMALL shl 4 or 7)
                else -> { out.u8(IMM_F shl 4); out.f64(op.value) }
            }
            is Operand.ImmS -> { out.u8(IMM_S shl 4); out.uvarint(pool.of(op.text)) }
            is Operand.ImmB -> out.u8(SMALL shl 4 or (if (op.value) 1 else 0))
            Operand.ImmNone -> out.u8(SMALL shl 4 or 2)
            is Operand.View -> spec(out, VIEW, op.index)
            is Operand.State -> spec(out, STATE, op.index)
            is Operand.Local -> spec(out, LOCAL, op.index)
            is Operand.Param -> spec(out, PARAM, op.index)
            is Operand.Msg -> spec(out, MSG, op.index)
            is Operand.LocalField -> { spec(out, LOCAL_FIELD, op.local); out.u8(op.field) }
            is Operand.ViewField -> { spec(out, VIEW_FIELD, op.view); out.u8(op.field) }
            Operand.Time -> out.u8(SPECIAL shl 4 or 0)
            Operand.Self -> out.u8(SPECIAL shl 4 or 1)
            Operand.MsgRec -> out.u8(SPECIAL shl 4 or 2)
        }
    }

    fun encodeDst(out: Sink, op: Operand) {
        when (op) {
            Operand.Stack -> out.u8(0)
            is Operand.State -> spec(out, 1, op.index)
            is Operand.Local -> spec(out, 2, op.index)
            else -> error("Not a destination: $op")
        }
    }

    private fun index(src: Source, small: Int): Int = if (small < EXT) small else src.uvarintInt()

    fun decodeSrc(src: Source, strings: List<String>): Operand {
        val b = src.u8(); val mode = b ushr 4; val small = b and 15
        return when (mode) {
            STACK -> Operand.Stack
            IMM_I -> Operand.ImmI(src.zigzag())
            IMM_F -> Operand.ImmF(src.f64())
            IMM_S -> Operand.ImmS(strings.getOrNull(src.uvarintInt()) ?: error("String index out of range"))
            SMALL -> when (small) {
                0 -> Operand.ImmB(false); 1 -> Operand.ImmB(true); 2 -> Operand.ImmNone
                3 -> Operand.ImmI(0); 4 -> Operand.ImmI(1); 5 -> Operand.ImmI(-1)
                6 -> Operand.ImmF(0.0); 7 -> Operand.ImmF(1.0)
                else -> error("Bad small constant $small")
            }
            VIEW -> Operand.View(index(src, small))
            STATE -> Operand.State(index(src, small))
            LOCAL -> Operand.Local(index(src, small))
            PARAM -> Operand.Param(index(src, small))
            MSG -> Operand.Msg(index(src, small))
            LOCAL_FIELD -> Operand.LocalField(index(src, small), src.u8())
            VIEW_FIELD -> Operand.ViewField(index(src, small), src.u8())
            SPECIAL -> when (small) { 0 -> Operand.Time; 1 -> Operand.Self; 2 -> Operand.MsgRec; else -> error("Bad special operand $small") }
            else -> error("Bad operand mode $mode")
        }
    }

    fun decodeDst(src: Source): Operand {
        val b = src.u8(); val mode = b ushr 4; val small = b and 15
        return when (mode) {
            0 -> Operand.Stack
            1 -> Operand.State(index(src, small))
            2 -> Operand.Local(index(src, small))
            else -> error("Bad destination mode $mode")
        }
    }

    /** Encodes one instruction; a branch target is written as a 4-byte placeholder recorded in [fixups]. */
    fun encode(insn: Insn, out: Sink, pool: StringPool, fixups: MutableList<Pair<Int, Label>>) {
        val op = insn.op
        out.u8(op.code)
        when (op) {
            Op.MKREC -> {
                out.uvarint(insn.imm); out.u8(insn.srcs.size); encodeDst(out, insn.dst!!)
                insn.srcs.forEachIndexed { i, s -> out.u8(insn.fields[i]); encodeSrc(out, s, pool) }
            }
            Op.SEND -> {
                out.uvarint(insn.imm); out.u8(insn.srcs.size - 1); encodeSrc(out, insn.srcs[0], pool)
                for (i in 1 until insn.srcs.size) { out.u8(insn.fields[i - 1]); encodeSrc(out, insn.srcs[i], pool) }
            }
            Op.GETF -> { out.u8(insn.fields[0]); encodeDst(out, insn.dst!!); encodeSrc(out, insn.srcs[0], pool) }
            else -> {
                if (op.rel) out.u8(insn.rel)
                if (op.dst) encodeDst(out, insn.dst!!)
                require(insn.srcs.size == op.srcs) { "$op expects ${op.srcs} sources, got ${insn.srcs.size}" }
                insn.srcs.forEach { encodeSrc(out, it, pool) }
                if (op.target) { fixups += out.size to insn.target!!; out.u32(0) }
            }
        }
    }

    /** Decodes one instruction; the target of a branch is returned as a raw offset in [Insn.imm]. */
    fun decode(src: Source, strings: List<String>): Insn {
        val code = src.u8()
        val op = Op.of(code) ?: error("Unknown opcode 0x${code.toString(16)}")
        when (op) {
            Op.MKREC -> {
                val schema = src.uvarintInt(); val n = src.u8(); val dst = decodeDst(src)
                val fields = ArrayList<Int>(); val srcs = ArrayList<Operand>()
                repeat(n) { fields += src.u8(); srcs += decodeSrc(src, strings) }
                return Insn(op, dst = dst, srcs = srcs, fields = fields, imm = schema)
            }
            Op.SEND -> {
                val event = src.uvarintInt(); val n = src.u8(); val target = decodeSrc(src, strings)
                val fields = ArrayList<Int>(); val srcs = arrayListOf(target)
                repeat(n) { fields += src.u8(); srcs += decodeSrc(src, strings) }
                return Insn(op, srcs = srcs, fields = fields, imm = event)
            }
            Op.GETF -> { val f = src.u8(); val dst = decodeDst(src); return Insn(op, dst = dst, srcs = listOf(decodeSrc(src, strings)), fields = listOf(f)) }
            else -> {
                val rel = if (op.rel) src.u8() else 0
                val dst = if (op.dst) decodeDst(src) else null
                val srcs = List(op.srcs) { decodeSrc(src, strings) }
                val target = if (op.target) src.u32().toInt() else 0
                return Insn(op, rel = rel, dst = dst, srcs = srcs, imm = target)
            }
        }
    }
}

/** Collects instructions and labels, removes jumps to the next instruction, encodes, and computes the stack depth. */
class Assembler {
    private val items = ArrayList<Any>() // Insn or Label

    fun emit(insn: Insn) { items += insn }
    fun place(label: Label) { items += label }

    class Result(val code: ByteArray, val maxStack: Int, val sourceMap: List<Triple<Int, Int, Int>>)

    /** The first instruction at a label, skipping labels placed next to each other. */
    private fun instructionAt(label: Label): Insn? {
        val start = items.indexOfFirst { it === label }
        if (start < 0) return null
        var i = start + 1
        while (i < items.size && items[i] is Label) i++
        return items.getOrNull(i) as? Insn
    }

    /** True when [label] is placed directly after position [index], with only labels in between. */
    private fun fallsThrough(index: Int, label: Label): Boolean {
        var i = index + 1
        while (i < items.size && items[i] is Label) { if (items[i] === label) return true; i++ }
        return false
    }

    /**
     * Jump threading and removal: a jump to a jump goes straight to the final target, a jump to a single RET
     * becomes that RET, and a jump to the next instruction is dropped.
     */
    private fun simplify() {
        for (item in items) {
            if (item !is Insn) continue
            var target = item.target ?: continue
            val seen = HashSet<Label>()
            while (seen.add(target)) {
                val next = instructionAt(target) ?: break
                if (next.op != Op.JMP) break
                target = next.target ?: break
            }
            item.retarget(target)
        }
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < items.size) {
                val item = items[i]
                if (item is Insn && item.op == Op.JMP) {
                    if (fallsThrough(i, item.target!!)) { items.removeAt(i); changed = true; continue }
                    if (instructionAt(item.target!!)?.op == Op.RET) { items[i] = Insn(Op.RET, line = item.line, column = item.column); changed = true }
                }
                i++
            }
        }
    }

    /** [entries] are the roots of the control flow: the initializer and every handler. */
    fun finish(pool: StringPool, entries: List<Label>): Result {
        simplify()
        val out = Sink(); val fixups = ArrayList<Pair<Int, Label>>(); val map = ArrayList<Triple<Int, Int, Int>>()
        val insns = ArrayList<Insn>(); val offsets = ArrayList<Int>()
        for (item in items) when (item) {
            is Label -> { item.offset = out.size; item.index = insns.size }
            is Insn -> { offsets += out.size; insns += item; if (item.line > 0) map += Triple(out.size, item.line, item.column); Codec.encode(item, out, pool, fixups) }
        }
        val bytes = out.toByteArray()
        for ((pos, label) in fixups) {
            require(label.offset >= 0) { "Label was never placed" }
            for (i in 0 until 4) bytes[pos + i] = (label.offset ushr (8 * i)).toByte()
        }
        return Result(bytes, maxDepth(insns, entries), map)
    }

    /** Every path is walked once; a join of two paths with different depths is a compiler bug, not a program error. */
    private fun maxDepth(insns: List<Insn>, entries: List<Label>): Int {
        if (insns.isEmpty()) return 0
        val depth = IntArray(insns.size) { -1 }
        var max = 0
        val work = ArrayDeque<Int>()
        fun flow(to: Int, d: Int) {
            if (to >= insns.size) return
            if (depth[to] == -1) { depth[to] = d; work += to } else check(depth[to] == d) { "Stack depth mismatch at instruction $to" }
        }
        for (entry in entries) flow(entry.index, 0)
        while (work.isNotEmpty()) {
            val i = work.removeFirst(); val insn = insns[i]
            val d = depth[i] - insn.pops + insn.pushes
            check(depth[i] - insn.pops >= 0) { "Stack underflow at instruction $i ($insn)" }
            max = maxOf(max, d)
            when (insn.op) {
                Op.RET -> Unit
                Op.JMP -> flow(insn.target!!.index, d)
                else -> { if (insn.op.target) flow(insn.target!!.index, d); flow(i + 1, d) }
            }
        }
        return max
    }
}

/** Text form of instructions, for tests, diagnostics and the disassembler. */
object Disassembler {
    fun operand(op: Operand): String = when (op) {
        Operand.Stack -> "stack"
        is Operand.ImmI -> "#${op.value}"
        is Operand.ImmF -> "#${op.value}"
        is Operand.ImmS -> "\"${op.text}\""
        is Operand.ImmB -> "#${op.value}"
        Operand.ImmNone -> "#none"
        is Operand.View -> "view[${op.index}]"
        is Operand.State -> "state[${op.index}]"
        is Operand.Local -> "local[${op.index}]"
        is Operand.Param -> "param[${op.index}]"
        is Operand.Msg -> "msg[${op.index}]"
        is Operand.LocalField -> "local[${op.local}].${op.field}"
        is Operand.ViewField -> "view[${op.view}].${op.field}"
        Operand.Time -> "time"
        Operand.Self -> "self"
        Operand.MsgRec -> "msg"
    }

    fun format(insn: Insn): String {
        val parts = ArrayList<String>()
        if (insn.op.rel) parts += Rel.names[insn.rel]
        if (insn.op == Op.GETF) parts += "field${insn.fields[0]}"
        if (insn.op == Op.MKREC) parts += "schema${insn.imm}"
        if (insn.op == Op.SEND) parts += "event${insn.imm}"
        insn.dst?.let { parts += operand(it) + "<-" }
        if (insn.op == Op.MKREC || insn.op == Op.SEND) {
            val first = if (insn.op == Op.SEND) 1 else 0
            if (insn.op == Op.SEND) parts += "to " + operand(insn.srcs[0])
            for (i in first until insn.srcs.size) parts += "f${insn.fields[i - first]}=" + operand(insn.srcs[i])
        } else parts += insn.srcs.map(::operand)
        insn.target?.let { parts += "-> L${it.index}" }
        return insn.op.name + " " + parts.joinToString(" ")
    }

    /** Decodes and prints a whole code block; branch targets are offsets. */
    fun disassemble(code: ByteArray, strings: List<String>): List<String> {
        val src = Source(code); val lines = ArrayList<String>()
        while (src.remaining > 0) {
            val at = src.pos
            val insn = Codec.decode(src, strings)
            lines += "%04x  %s".format(at, format(insn).let { if (insn.op.target) it + " -> @%04x".format(insn.imm) else it })
        }
        return lines
    }
}
