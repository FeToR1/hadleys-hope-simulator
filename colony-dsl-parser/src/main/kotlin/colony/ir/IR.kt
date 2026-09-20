package colony.ir

import colony.ast.SourceSpan
import colony.semantics.ConstantValue
import colony.semantics.Type
import colony.semantics.render
import java.math.BigDecimal


enum class IRIntrinsicId {
    LOAD_TIME, LOAD_VIEW, LOAD_THIS,
    INDEX, MAKE_RECORD,
    SOME, CLAMP, NEAREST,
    CHANCE, HAZARD,
    POWER_REQUEST, DAMAGE_REQUEST, MOTION_REQUEST, REPAIR_REQUEST,
}

data class IRProgram(
    val events: List<IREventSchema>,
    val behaviors: List<IRBehavior>,
)

data class IREventSchema(
    val id: Int,
    val name: String,
    val fields: List<IRField>,
)

data class IRField(
    val name: String,
    val type: Type,
)

data class IRBehavior(
    val name: String,
    val targetKind: String,
    val params: List<IRSlot>,
    val state: IRStateLayout,
    val subscriptions: List<IRSubscription>,
    val timers: List<IRTimer>,
    val localSlotsByHandlerId: Map<Int, List<IRLocalSlot>>,
    val blocks: List<IRBlock>,
    val entryByHandlerId: Map<Int, IRLabel>,
)

data class IRStateLayout(
    val slots: List<IRStateSlot>,
) {
    val slotCount: Int get() = slots.size
}

data class IRStateSlot(
    val index: Int,
    val name: String,
    val type: Type,
)

data class IRSlot(
    val index: Int,
    val name: String,
    val type: Type,
)

data class IRLocalSlot(
    val index: Int,
    val name: String,
    val type: Type,
)

data class IRSubscription(
    val handlerId: Int,
    val eventId: Int,
    val ruleName: String,
    val messageName: String,
)

data class IRTimer(
    val handlerId: Int,
    val ruleName: String,
    val periodSeconds: BigDecimal,
    val periodTicks: Long,
)

data class IRLabel(val id: Int) {
    override fun toString(): String = "B$id"
}

data class IRTemp(
    val id: Int,
    val type: Type,
) {
    override fun toString(): String = "%t$id:${type.renderForIr()}"
}

sealed interface IRInstruction {
    val result: IRTemp?
    val span: SourceSpan

    data class Const(
        override val result: IRTemp,
        val value: ConstantValue,
        override val span: SourceSpan,
    ) : IRInstruction

    data class Convert(
        override val result: IRTemp,
        val fromType: Type,
        val toType: Type,
        val value: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction

    data class LoadParam(
        override val result: IRTemp,
        val slot: IRSlot,
        override val span: SourceSpan,
    ) : IRInstruction

    data class LoadState(
        override val result: IRTemp,
        val slot: IRStateSlot,
        override val span: SourceSpan,
    ) : IRInstruction

    data class StoreState(
        val slot: IRStateSlot,
        val value: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction {
        override val result: IRTemp? = null
    }

    data class LoadLocal(
        override val result: IRTemp,
        val slot: IRLocalSlot,
        override val span: SourceSpan,
    ) : IRInstruction

    data class StoreLocal(
        val slot: IRLocalSlot,
        val value: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction {
        override val result: IRTemp? = null
    }

    data class LoadMessage(
        override val result: IRTemp,
        val eventId: Int,
        val eventType: Type.Event,
        override val span: SourceSpan,
    ) : IRInstruction

    data class LoadMessageField(
        override val result: IRTemp,
        val field: IRField,
        override val span: SourceSpan,
    ) : IRInstruction

    data class LoadView(
        override val result: IRTemp,
        val kind: String,
        val field: IRField,
        override val span: SourceSpan,
    ) : IRInstruction

    data class LoadRefId(
        override val result: IRTemp,
        val receiver: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction

    data class Unary(
        override val result: IRTemp,
        val op: String,
        val operand: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction

    data class Binary(
        override val result: IRTemp,
        val op: String,
        val left: IRTemp,
        val right: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction

    data class CallPure(
        override val result: IRTemp,
        val intrinsic: IRIntrinsicId,
        val arguments: List<IRTemp>,
        override val span: SourceSpan,
    ) : IRInstruction

    data class MakeRecord(
        override val result: IRTemp,
        val fields: List<Pair<String, IRTemp>>,
        override val span: SourceSpan,
    ) : IRInstruction

    data class RandomDecision(
        override val result: IRTemp,
        val intrinsic: IRIntrinsicId,
        val arguments: List<IRTemp>,
        val siteId: Int,
        override val span: SourceSpan,
    ) : IRInstruction

    data class CallEffect(
        val intrinsic: IRIntrinsicId,
        val arguments: List<IRTemp>,
        override val span: SourceSpan,
    ) : IRInstruction {
        override val result: IRTemp? = null
    }

    data class SendEvent(
        val eventId: Int,
        val target: IRTemp,
        val fields: List<Pair<String, IRTemp>>,
        override val span: SourceSpan,
    ) : IRInstruction {
        override val result: IRTemp? = null
    }

    data class OptionIsSome(
        override val result: IRTemp,
        val option: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction

    data class OptionUnwrap(
        override val result: IRTemp,
        val option: IRTemp,
        override val span: SourceSpan,
    ) : IRInstruction
}

sealed interface IRTerminator {
    val span: SourceSpan

    data class Jump(val target: IRLabel, override val span: SourceSpan) : IRTerminator
    data class Branch(val condition: IRTemp, val thenTarget: IRLabel, val elseTarget: IRLabel, override val span: SourceSpan) : IRTerminator
    data class Return(override val span: SourceSpan) : IRTerminator
}

data class IRBlock(
    val label: IRLabel,
    val instructions: List<IRInstruction>,
    val terminator: IRTerminator,
)

private fun Type.renderForIr(): String = when (this) {
    Type.Bool -> "Bool"
    Type.Int64 -> "Int64"
    Type.Real64 -> "Real64"
    Type.Duration -> "Duration"
    Type.Probability -> "Probability"
    Type.Rate -> "Rate"
    Type.Money -> "Money"
    Type.String -> "String"
    Type.Void -> "Void"
    Type.Unknown -> "Unknown"
    is Type.Physical -> this.render()
    is Type.Ref -> "Ref<${kind}>"
    is Type.Option -> "Option<${inner.renderForIr()}>"
    is Type.List -> "List<${element.renderForIr()}>"
    is Type.Enum -> render()
    is Type.EnumValue -> render()
    is Type.Event -> "Event<$name>"
    is Type.Kind -> this.name
    is Type.View -> "View<$kind>"
    Type.IntrinsicNamespace -> "Intrinsic"
}

fun IRProgram.prettyPrint(): String = buildString {
    appendLine("IRProgram")
    for (event in events) {
        appendLine("  event #${event.id} ${event.name}(${event.fields.joinToString { "${it.name}: ${it.type.render()}" }})")
    }
    for (behavior in behaviors) {
        appendLine("  behavior ${behavior.name} for ${behavior.targetKind}")
        appendLine("    params: ${behavior.params.joinToString { "${it.index}:${it.name}:${it.type.render()}" }}")
        appendLine("    state: ${behavior.state.slots.joinToString { "${it.index}:${it.name}:${it.type.render()}" }}")
        appendLine("    subscriptions: ${behavior.subscriptions}")
        appendLine("    timers: ${behavior.timers}")
        appendLine("    localsByHandler: ${behavior.localSlotsByHandlerId}")
        for (block in behavior.blocks) {
            appendLine("    ${block.label}:")
            block.instructions.forEachIndexed { index, ins -> appendLine("      $index  $ins") }
            appendLine("      -> ${block.terminator}")
        }
    }
}
