package colony.bytecode

import colony.ast.SourceSpan
import colony.ir.*
import colony.semantics.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Version 1 is a portable JSON envelope containing stack instructions, not serialized JVM objects. */
@Serializable
data class BytecodeProgram(
    val version: Int = 1,
    val stepSeconds: String,
    val events: List<EventSchema>,
    val behaviors: List<BehaviorCode>,
)

@Serializable data class Slot(val name: String, val type: String)
@Serializable data class EventSchema(val id: Int, val name: String, val fields: List<Slot>)
@Serializable data class Handler(val name: String, val entry: Int, val localCount: Int, val eventId: Int? = null, val periodTicks: Long? = null)
@Serializable data class BehaviorCode(
    val name: String,
    val kind: String,
    val params: List<Slot>,
    val state: List<Slot>,
    val temporaryCount: Int,
    val initialize: Int,
    val handlers: List<Handler>,
    val code: List<Instruction>,
)

@Serializable enum class Op {
    CONST, LOAD_TEMP, STORE_TEMP, LOAD_PARAM, LOAD_STATE, STORE_STATE, LOAD_LOCAL, STORE_LOCAL,
    LOAD_MESSAGE, LOAD_VIEW, LOAD_TIME, LOAD_SELF, LOAD_OBSERVATIONS, GET_FIELD, CONVERT,
    UNARY, BINARY, MAKE_RECORD, SOME, IS_SOME, UNWRAP, INDEX, CLAMP, NEAREST,
    CHANCE, HAZARD, POWER_REQUEST, DAMAGE_REQUEST, MOTION_REQUEST, REPAIR_REQUEST, SEND,
    JUMP, JUMP_IF_FALSE, RETURN,
}

@Serializable data class Instruction(
    val op: Op,
    val arg: Int = 0,
    val text: String = "",
    val type: String = "",
    val names: List<String> = emptyList(),
    val value: JsonElement = JsonNull,
    val line: Int = 0,
    val column: Int = 0,
)

val bytecodeJson = Json { prettyPrint = true; encodeDefaults = false }

class BytecodeCompiler {
    fun compile(ir: IRProgram): BytecodeProgram = BytecodeProgram(
        stepSeconds = ir.deltaTimeSeconds.toPlainString(),
        events = ir.events.map { EventSchema(it.id, it.name, it.fields.map { f -> Slot(f.name, f.type.render()) }) },
        behaviors = ir.behaviors.map(::compileBehavior),
    ).also(BytecodeVerifier::verify)

    private fun compileBehavior(behavior: IRBehavior): BehaviorCode {
        val code = mutableListOf<Instruction>()
        val labels = mutableMapOf<IRLabel, Int>()
        val jumps = mutableListOf<Pair<Int, IRLabel>>()
        var temporaryCount = 0
        fun emit(op: Op, at: SourceSpan, arg: Int = 0, text: String = "", type: String = "", names: List<String> = emptyList(), value: JsonElement = JsonNull) {
            code += Instruction(op, arg, text, type, names, value, at.startLine, at.startColumn)
        }
        fun load(value: IRTemp, at: SourceSpan) { emit(Op.LOAD_TEMP, at, value.id); temporaryCount = maxOf(temporaryCount, value.id + 1) }
        fun store(value: IRTemp, at: SourceSpan) { emit(Op.STORE_TEMP, at, value.id); temporaryCount = maxOf(temporaryCount, value.id + 1) }
        fun jump(op: Op, target: IRLabel, at: SourceSpan) { jumps += code.size to target; emit(op, at) }
        fun intrinsic(id: IRIntrinsicId): Op = when (id) {
            IRIntrinsicId.LOAD_TIME -> Op.LOAD_TIME
            IRIntrinsicId.LOAD_VIEW -> Op.LOAD_OBSERVATIONS
            IRIntrinsicId.LOAD_THIS -> Op.LOAD_SELF
            else -> Op.valueOf(id.name)
        }
        for (block in behavior.blocks) {
            check(labels.put(block.label, code.size) == null) { "Duplicate IR block ${block.label}" }
            for (instruction in block.instructions) {
                val at = instruction.span
                when (instruction) {
                    is IRInstruction.Const -> emit(Op.CONST, at, type = instruction.result.type.render(), value = constantJson(instruction.value))
                    is IRInstruction.Convert -> { load(instruction.value, at); emit(Op.CONVERT, at, type = instruction.toType.render()) }
                    is IRInstruction.Copy -> load(instruction.value, at)
                    is IRInstruction.LoadParam -> emit(Op.LOAD_PARAM, at, instruction.slot.index)
                    is IRInstruction.LoadState -> emit(Op.LOAD_STATE, at, instruction.slot.index)
                    is IRInstruction.StoreState -> { load(instruction.value, at); emit(Op.STORE_STATE, at, instruction.slot.index) }
                    is IRInstruction.LoadLocal -> emit(Op.LOAD_LOCAL, at, instruction.slot.index)
                    is IRInstruction.StoreLocal -> { load(instruction.value, at); emit(Op.STORE_LOCAL, at, instruction.slot.index) }
                    is IRInstruction.LoadMessage -> emit(Op.LOAD_MESSAGE, at)
                    is IRInstruction.LoadField -> { load(instruction.receiver, at); emit(Op.GET_FIELD, at, text = instruction.field.name) }
                    is IRInstruction.LoadView -> emit(Op.LOAD_VIEW, at, text = instruction.field.name)
                    is IRInstruction.LoadRefId -> load(instruction.receiver, at)
                    is IRInstruction.Unary -> { load(instruction.operand, at); emit(Op.UNARY, at, text = instruction.op, type = instruction.result.type.render()) }
                    is IRInstruction.Binary -> {
                        load(instruction.left, at); load(instruction.right, at)
                        emit(Op.BINARY, at, text = instruction.op, type = instruction.result.type.render())
                    }
                    is IRInstruction.CallPure -> {
                        instruction.arguments.forEach { load(it, at) }; emit(intrinsic(instruction.intrinsic), at)
                    }
                    is IRInstruction.MakeRecord -> {
                        instruction.fields.forEach { load(it.second, at) }
                        emit(Op.MAKE_RECORD, at, names = instruction.fields.map { it.first })
                    }
                    is IRInstruction.RandomDecision -> {
                        instruction.arguments.forEach { load(it, at) }; emit(intrinsic(instruction.intrinsic), at)
                    }
                    is IRInstruction.CallEffect -> {
                        instruction.arguments.forEach { load(it, at) }; emit(intrinsic(instruction.intrinsic), at)
                    }
                    is IRInstruction.SendEvent -> {
                        load(instruction.target, at); instruction.fields.forEach { load(it.second, at) }
                        emit(Op.SEND, at, instruction.eventId, names = instruction.fields.map { it.first })
                    }
                    is IRInstruction.OptionIsSome -> { load(instruction.option, at); emit(Op.IS_SOME, at) }
                    is IRInstruction.OptionUnwrap -> { load(instruction.option, at); emit(Op.UNWRAP, at) }
                }
                instruction.result?.let { store(it, at) }
            }
            when (val terminator = block.terminator) {
                is IRTerminator.Jump -> jump(Op.JUMP, terminator.target, terminator.span)
                is IRTerminator.Branch -> {
                    load(terminator.condition, terminator.span)
                    jump(Op.JUMP_IF_FALSE, terminator.elseTarget, terminator.span)
                    jump(Op.JUMP, terminator.thenTarget, terminator.span)
                }
                is IRTerminator.Return -> emit(Op.RETURN, terminator.span)
            }
        }
        jumps.forEach { (index, label) -> code[index] = code[index].copy(arg = labels.getValue(label)) }
        val handlers = behavior.entryByHandlerId.map { (id, entry) ->
            val subscription = behavior.subscriptions.find { it.handlerId == id }
            val timer = behavior.timers.find { it.handlerId == id }
            Handler(subscription?.ruleName ?: timer!!.ruleName, labels.getValue(entry),
                behavior.localSlotsByHandlerId.getValue(id).size, subscription?.eventId, timer?.periodTicks)
        }
        return BehaviorCode(behavior.name, behavior.targetKind, behavior.params.map { Slot(it.name, it.type.render()) },
            behavior.state.slots.map { Slot(it.name, it.type.render()) }, temporaryCount,
            labels.getValue(behavior.initializationEntry), handlers, code)
    }
}

private fun constantJson(value: ConstantValue): JsonElement = when (value) {
    is ConstantValue.Bool -> JsonPrimitive(value.value)
    is ConstantValue.Int64 -> JsonPrimitive(value.value)
    is ConstantValue.Real64 -> JsonPrimitive(value.value)
    is ConstantValue.Decimal -> JsonPrimitive(value.value)
    is ConstantValue.Text -> JsonPrimitive(value.value)
    is ConstantValue.EnumValue -> JsonPrimitive(value.value)
    ConstantValue.None -> JsonNull
}

/** Structural validation of the portable artifact; runtime also checks values and execution quotas. */
object BytecodeVerifier {
    fun verify(program: BytecodeProgram) {
        require(program.version == 1) { "Unsupported bytecode version ${program.version}" }
        require(program.stepSeconds.toBigDecimal().signum() > 0) { "stepSeconds must be positive" }
        require(program.events.map { it.id }.distinct().size == program.events.size) { "Duplicate event IDs" }
        require(program.events.map { it.name }.distinct().size == program.events.size) { "Duplicate event names" }
        require(program.behaviors.map { it.name }.distinct().size == program.behaviors.size) { "Duplicate behaviors" }
        program.behaviors.forEach { behavior -> verifyBehavior(program, behavior) }
    }

    private fun verifyBehavior(program: BytecodeProgram, behavior: BehaviorCode) {
        require(behavior.code.isNotEmpty() && behavior.code.size <= 1_000_000) { "Invalid code size" }
        require(behavior.temporaryCount in 0..100_000) { "Invalid temporary count" }
        require(behavior.handlers.map { it.name }.distinct().size == behavior.handlers.size) { "Duplicate handler names" }
        behavior.handlers.forEach {
            require((it.eventId == null) != (it.periodTicks == null)) { "Handler must subscribe to an event or timer" }
            require(it.periodTicks == null || it.periodTicks > 0) { "Timer period must be positive" }
            require(it.eventId == null || program.events.any { e -> e.id == it.eventId }) { "Unknown subscribed event" }
            require(it.localCount in 0..100_000) { "Invalid local count" }
        }
        val entries = listOf(behavior.initialize to 0) + behavior.handlers.map { it.entry to it.localCount }
        for ((entry, localCount) in entries) {
            val depths = mutableMapOf<Int, Int>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(entry to 0)
            while (queue.isNotEmpty()) {
                val (pc, depth) = queue.removeFirst()
                require(pc in behavior.code.indices) { "Jump/entry outside code: $pc" }
                val previous = depths.putIfAbsent(pc, depth)
                if (previous != null) { require(previous == depth) { "Stack depth mismatch at $pc" }; continue }
                val instruction = behavior.code[pc]
                when (instruction.op) {
                    Op.LOAD_TEMP, Op.STORE_TEMP -> require(instruction.arg in 0 until behavior.temporaryCount) { "Invalid temporary slot" }
                    Op.LOAD_PARAM -> require(instruction.arg in behavior.params.indices) { "Invalid parameter slot" }
                    Op.LOAD_STATE, Op.STORE_STATE -> require(instruction.arg in behavior.state.indices) { "Invalid state slot" }
                    Op.LOAD_LOCAL, Op.STORE_LOCAL -> require(instruction.arg in 0 until localCount) { "Invalid local slot" }
                    Op.SEND -> {
                        val event = program.events.find { it.id == instruction.arg } ?: error("Unknown event ID")
                        require(instruction.names.distinct().size == instruction.names.size && instruction.names.toSet() == event.fields.map { it.name }.toSet()) { "Event schema mismatch" }
                    }
                    else -> Unit
                }
                val (pops, pushes) = instruction.stackEffect()
                require(depth >= pops) { "Stack underflow at $pc (${instruction.op})" }
                val nextDepth = depth - pops + pushes
                require(nextDepth <= 4096) { "Operand stack is too large" }
                when (instruction.op) {
                    Op.RETURN -> require(depth == 0) { "Nonempty stack at RETURN" }
                    Op.JUMP -> queue.add(instruction.arg to nextDepth)
                    Op.JUMP_IF_FALSE -> { queue.add(instruction.arg to nextDepth); queue.add(pc + 1 to nextDepth) }
                    else -> queue.add(pc + 1 to nextDepth)
                }
            }
        }
    }
}

fun Instruction.stackEffect(): Pair<Int, Int> = when (op) {
    Op.CONST, Op.LOAD_TEMP, Op.LOAD_PARAM, Op.LOAD_STATE, Op.LOAD_LOCAL, Op.LOAD_MESSAGE,
    Op.LOAD_VIEW, Op.LOAD_TIME, Op.LOAD_SELF, Op.LOAD_OBSERVATIONS -> 0 to 1
    Op.STORE_TEMP, Op.STORE_STATE, Op.STORE_LOCAL, Op.JUMP_IF_FALSE, Op.POWER_REQUEST, Op.REPAIR_REQUEST -> 1 to 0
    Op.GET_FIELD, Op.CONVERT, Op.UNARY, Op.SOME, Op.IS_SOME, Op.UNWRAP, Op.NEAREST -> 1 to 1
    Op.BINARY, Op.INDEX, Op.CHANCE, Op.HAZARD -> 2 to 1
    Op.CLAMP -> 3 to 1
    Op.MAKE_RECORD -> names.size to 1
    Op.SEND -> names.size + 1 to 0
    Op.DAMAGE_REQUEST -> 3 to 0
    Op.MOTION_REQUEST -> 2 to 0
    Op.JUMP, Op.RETURN -> 0 to 0
}
