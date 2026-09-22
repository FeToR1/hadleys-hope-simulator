package colony.cvm

/** Value types of the VM (docs/cvm-v2.md, section 2). */
sealed interface VType {
    data object Bool : VType
    data object I64 : VType
    data object F64 : VType
    data object Str : VType
    data class Opt(val inner: VType) : VType
    data class List(val element: VType) : VType
    data class Rec(val schema: String) : VType

    /** Static type of the `none` literal; compatible with every Opt. Never appears in an artifact. */
    data object NoneT : VType

    fun render(): String = when (this) {
        Bool -> "Bool"; I64 -> "I64"; F64 -> "F64"; Str -> "Str"; NoneT -> "None"
        is Opt -> "Opt<${inner.render()}>"
        is List -> "List<${element.render()}>"
        is Rec -> schema
    }
}

data class SchemaField(val name: String, val type: VType)
data class Schema(val name: String, val fields: kotlin.collections.List<SchemaField>)

/** Source operand, destination and their addressing modes (docs/cvm-v2.md, section 4). */
sealed interface Operand {
    data object Stack : Operand
    data class ImmI(val value: Long) : Operand
    data class ImmF(val value: Double) : Operand
    data class ImmS(val text: String) : Operand
    data class ImmB(val value: Boolean) : Operand
    data object ImmNone : Operand
    data class View(val index: Int) : Operand
    data class State(val index: Int) : Operand
    data class Local(val index: Int) : Operand
    data class Param(val index: Int) : Operand
    data class Msg(val index: Int) : Operand
    data class LocalField(val local: Int, val field: Int) : Operand
    data class ViewField(val view: Int, val field: Int) : Operand
    data object Time : Operand
    data object Self : Operand
    data object MsgRec : Operand
}

/** Relations of the comparison commands. */
object Rel {
    const val EQ = 0; const val NE = 1; const val LT = 2; const val LE = 3; const val GT = 4; const val GE = 5
    val names = listOf("EQ", "NE", "LT", "LE", "GT", "GE")

    /** The relation that holds exactly when this one does not (numbers are always finite, so this is exact). */
    fun negate(rel: Int): Int = when (rel) { EQ -> NE; NE -> EQ; LT -> GE; LE -> GT; GT -> LE; else -> LT }
}

/** Shape of an instruction's encoding; the opcode table below is the single source for the compiler, writer and readers. */
enum class Op(
    val code: Int,
    val dst: Boolean = false,
    val srcs: Int = 0,
    val rel: Boolean = false,
    val target: Boolean = false,
) {
    MOV(0x01, dst = true, srcs = 1), POP(0x02),
    // Numbers are I64 or F64; the compiler picks the variant from the static types of the operands.
    ADD_I(0x10, true, 2), SUB_I(0x11, true, 2), MUL_I(0x12, true, 2), MOD_I(0x13, true, 2), NEG_I(0x14, true, 1),
    ADD_F(0x18, true, 2), SUB_F(0x19, true, 2), MUL_F(0x1A, true, 2), DIV_F(0x1B, true, 2), NEG_F(0x1C, true, 1),
    TOF(0x1D, true, 1), TOF_OPT(0x1E, true, 1), CLAMP_F(0x1F, true, 3), CLAMP_I(0x20, true, 3),
    CMP_I(0x24, true, 2, rel = true), CMP_F(0x25, true, 2, rel = true), CMP_N(0x26, true, 2, rel = true),
    EQ_S(0x27, true, 2, rel = true), EQ_B(0x28, true, 2, rel = true), EQ_A(0x29, true, 2, rel = true), NOT(0x2A, true, 1),
    JMP(0x30, target = true), BR_T(0x31, srcs = 1, target = true), BR_F(0x32, srcs = 1, target = true),
    BR_CMP_I(0x33, srcs = 2, rel = true, target = true), BR_CMP_F(0x34, srcs = 2, rel = true, target = true),
    BR_CMP_N(0x35, srcs = 2, rel = true, target = true), BR_EQ_S(0x36, srcs = 2, rel = true, target = true),
    BR_EQ_B(0x37, srcs = 2, rel = true, target = true), BR_EQ_A(0x38, srcs = 2, rel = true, target = true),
    BR_SOME(0x39, srcs = 1, target = true), BR_NONE(0x3A, srcs = 1, target = true),
    LET_SOME(0x3B, dst = true, srcs = 1, target = true), RET(0x3C),
    IS_SOME(0x40, true, 1), UNWRAP(0x41, true, 1), SOME(0x42, true, 1),
    GETF(0x43, true, 1), INDEX(0x44, true, 2), NEAREST(0x45, true, 1),
    MKREC(0x46, dst = true), // schema, n, dst, n x (field, src): variable source list
    CHANCE(0x50, true, 2), HAZARD(0x51, true, 2),
    POWER(0x60, srcs = 1), MOTION(0x61, srcs = 2), DAMAGE(0x62, srcs = 3), REPAIR(0x63, srcs = 1),
    SEND(0x64); // event, n, target src, n x (field, src): variable source list

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): Op? = byCode[code]
    }
}

/**
 * One decoded instruction. [srcs] are the source operands in order; for MKREC and SEND they are the field values
 * (SEND: the first is the addressee) and [fields] names the schema field each value belongs to.
 */
class Insn(
    val op: Op,
    val rel: Int = 0,
    val dst: Operand? = null,
    val srcs: kotlin.collections.List<Operand> = emptyList(),
    /** GETF: the field; MKREC and SEND: the field of each value. */
    val fields: kotlin.collections.List<Int> = emptyList(),
    /** MKREC: schema index; SEND: event id. */
    val imm: Int = 0,
    target: Label? = null,
    val line: Int = 0,
    val column: Int = 0,
) {
    var target: Label? = target
        private set

    /** Used by jump threading, which only ever replaces a target with the one it leads to. */
    fun retarget(label: Label) { target = label }

    /** Number of values the instruction takes from the stack. */
    val pops: Int get() = srcs.count { it == Operand.Stack }
    val pushes: Int get() = if (dst == Operand.Stack) 1 else 0
    override fun toString(): String = Disassembler.format(this)
}

/** A jump target; resolved to a byte offset when the code is encoded. */
class Label {
    var offset: Int = -1
    /** Position in the instruction list once placed. */
    var index: Int = -1
}

class Slot(val name: String, val type: VType)
class Handler(val name: String, val isTimer: Boolean, val eventOrPeriod: Long, val entry: Int, val locals: kotlin.collections.List<VType>)
class Behavior(
    val name: String, val kind: String, val capabilities: Int,
    val params: kotlin.collections.List<Slot>, val state: kotlin.collections.List<Slot>, val observes: kotlin.collections.List<Slot>,
    val handlers: kotlin.collections.List<Handler>, val initEntry: Int, val initLocals: kotlin.collections.List<VType>,
    val maxStack: Int, val code: ByteArray, val sourceMap: kotlin.collections.List<Triple<Int, Int, Int>>,
)
class EventDef(val id: Int, val name: String, val schema: Int)
class Program(
    val contract: Int, val stepSeconds: String, val strings: kotlin.collections.List<String>,
    val schemas: kotlin.collections.List<Schema>,
    val events: kotlin.collections.List<EventDef>, val behaviors: kotlin.collections.List<Behavior>,
) {
    fun schema(name: String): Int = schemas.indexOfFirst { it.name == name }.also { require(it >= 0) { "Unknown schema $name" } }
}

object Capabilities {
    const val POWER = 1; const val DAMAGE = 2; const val MOTION = 4; const val REPAIR = 8
}
