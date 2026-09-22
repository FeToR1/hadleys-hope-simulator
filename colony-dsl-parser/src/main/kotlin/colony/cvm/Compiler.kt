package colony.cvm

import colony.ir.*
import colony.semantics.*

/** Maps a language type onto the value types the VM knows (docs/cvm-v2.md, section 2). */
fun vtypeOf(type: Type): VType = when (type) {
    Type.Bool -> VType.Bool
    Type.Int64, Type.Money -> VType.I64
    Type.Real64, Type.Duration, Type.Probability, Type.Rate -> VType.F64
    is Type.Physical -> VType.F64
    Type.String -> VType.Str
    is Type.Ref, is Type.Enum, is Type.EnumValue -> VType.Str
    is Type.Option -> if (type.inner == Type.Unknown) VType.NoneT else VType.Opt(vtypeOf(type.inner))
    is Type.List -> VType.List(vtypeOf(type.element))
    is Type.Kind -> VType.Rec(type.name)
    is Type.Event -> VType.Rec(type.name)
    else -> error("Type ${type.render()} has no runtime representation")
}

/**
 * Lowers the typed IR to CVM v2. Two things keep the code short: an operand that is a plain read (state, local,
 * parameter, observation, record field, constant) is addressed inside the consuming instruction instead of being
 * loaded first, and a result that goes straight into a slot is written by the instruction that computes it.
 */
class CvmCompiler(private val environment: SemanticEnvironment = SemanticEnvironment()) {

    fun compile(ir: IRProgram): Program {
        val schemas = LinkedHashMap<String, Schema>()
        for (name in environment.recordNames) {
            schemas[name] = Schema(name, environment.recordFields(name)!!.map { (field, type) -> SchemaField(field, vtypeOf(type)) })
        }
        for (event in ir.events) {
            require(event.name !in schemas) { "Event '${event.name}' collides with the built-in record of the same name" }
            schemas[event.name] = Schema(event.name, event.fields.map { SchemaField(it.name, vtypeOf(it.type)) })
        }
        val order = schemas.keys.toList()
        val events = ir.events.map { EventDef(it.id, it.name, order.indexOf(it.name)) }
        val pool = StringPool()
        val names = ir.events.associate { it.id to it.name }
        val behaviors = ir.behaviors.map { BehaviorCompiler(it, schemas, environment, pool, names).compile() }
        // Every name the artifact stores goes into the one string table, after the strings the code itself uses.
        val step = ir.deltaTimeSeconds.stripTrailingZeros().toPlainString()
        pool.of(step)
        for (schema in schemas.values) { pool.of(schema.name); schema.fields.forEach { pool.of(it.name) } }
        for (event in events) pool.of(event.name)
        for (b in behaviors) {
            pool.of(b.name); pool.of(b.kind)
            (b.params + b.state + b.observes).forEach { pool.of(it.name) }
            b.handlers.forEach { pool.of(it.name) }
        }
        return Program(CONTRACT_VERSION, step, pool.strings, schemas.values.toList(), events, behaviors)
    }
}

private class HandlerDraft(val name: String, val isTimer: Boolean, val eventOrPeriod: Long, val entry: Label, val locals: List<VType>)

private class BehaviorCompiler(
    private val behavior: IRBehavior,
    private val schemas: Map<String, Schema>,
    private val environment: SemanticEnvironment,
    private val pool: StringPool,
    private val events: Map<Int, String>,
) {
    private val asm = Assembler()
    private val labels = HashMap<IRLabel, Label>()
    private val blocks = behavior.blocks.associateBy { it.label }
    private val schemaOrder = schemas.keys.toList()

    /** Observations the program reads, in the order of the kind contract: also the order of the frame. */
    private val observes: List<Slot> = run {
        val used = HashSet<String>()
        for (block in behavior.blocks) for (insn in block.instructions) if (insn is IRInstruction.LoadView) used += insn.field.name
        val contract = environment.kindContract(behavior.targetKind) ?: error("Unknown kind ${behavior.targetKind}")
        contract.viewFields.filterKeys { it in used }.map { (name, type) -> Slot(name, vtypeOf(type)) }
    }
    private val viewIndex = observes.withIndex().associate { (i, slot) -> slot.name to i }

    private var locals = ArrayList<VType>()
    private var localOf = HashMap<Int, Int>()
    private val place = HashMap<Int, Operand>()
    private val deferred = LinkedHashMap<Int, Deferred>()
    private var uses = HashMap<Int, Int>()
    private var crossBlock = emptySet<Int>()
    /** Values that outlive their block get one slot for the whole handler, even when two branches write them. */
    private val crossLocal = HashMap<Int, Int>()
    private var skipLeading = HashMap<IRLabel, Int>()

    private class Deferred(val insn: IRInstruction, val readsState: Set<Int>, val readsLocal: Set<Int>, val random: Boolean)

    fun compile(): Behavior {
        val entries = ArrayList<Label>()
        val initEntry = label(behavior.initializationEntry)
        entries += initEntry
        emitHandler(behavior.initializationEntry, emptyList())
        val initLocals = locals.toList()

        val drafts = ArrayList<HandlerDraft>()
        for ((id, entry) in behavior.entryByHandlerId) {
            val subscription = behavior.subscriptions.find { it.handlerId == id }
            val timer = behavior.timers.find { it.handlerId == id }
            entries += label(entry)
            emitHandler(entry, behavior.localSlotsByHandlerId[id].orEmpty())
            drafts += HandlerDraft(subscription?.ruleName ?: timer!!.ruleName, timer != null,
                timer?.periodTicks ?: subscription!!.eventId.toLong(), label(entry), locals.toList())
        }

        val result = asm.finish(pool, entries)
        val capabilities = behavior.blocks.flatMap { it.instructions }.filterIsInstance<IRInstruction.CallEffect>().fold(0) { mask, insn ->
            mask or when (insn.intrinsic) {
                IRIntrinsicId.POWER_REQUEST -> Capabilities.POWER
                IRIntrinsicId.DAMAGE_REQUEST -> Capabilities.DAMAGE
                IRIntrinsicId.MOTION_REQUEST -> Capabilities.MOTION
                IRIntrinsicId.REPAIR_REQUEST -> Capabilities.REPAIR
                else -> 0
            }
        }
        return Behavior(behavior.name, behavior.targetKind, capabilities,
            behavior.params.map { Slot(it.name, vtypeOf(it.type)) },
            behavior.state.slots.map { Slot(it.name, vtypeOf(it.type)) },
            observes, drafts.map { Handler(it.name, it.isTimer, it.eventOrPeriod, it.entry.offset, it.locals) },
            initEntry.offset, initLocals, result.maxStack, result.code, result.sourceMap)
    }

    private fun label(ir: IRLabel): Label = labels.getOrPut(ir) { Label() }

    private fun emitHandler(entry: IRLabel, declared: List<IRLocalSlot>) {
        locals = ArrayList(declared.map { vtypeOf(it.type) })
        localOf = HashMap<Int, Int>().apply { declared.forEachIndexed { i, slot -> put(slot.index, i) } }
        place.clear(); deferred.clear(); skipLeading.clear(); crossLocal.clear()
        val own = reachable(entry)
        crossBlock = crossBlockTemps(own)
        for (label in orderBlocks(entry)) emitBlock(blocks.getValue(label))
    }

    private fun reachable(entry: IRLabel): Set<IRLabel> {
        val seen = LinkedHashSet<IRLabel>()
        val work = ArrayDeque<IRLabel>().also { it += entry }
        while (work.isNotEmpty()) {
            val current = work.removeFirst()
            if (!seen.add(current)) continue
            when (val terminator = blocks.getValue(current).terminator) {
                is IRTerminator.Jump -> work += terminator.target
                is IRTerminator.Branch -> { work += terminator.thenTarget; work += terminator.elseTarget }
                is IRTerminator.Return -> Unit
            }
        }
        return seen
    }

    /** Depth-first order with the false branch first, so a conditional jump falls through to it. */
    private fun orderBlocks(entry: IRLabel): List<IRLabel> {
        val out = ArrayList<IRLabel>()
        val seen = HashSet<IRLabel>()
        val work = ArrayDeque<IRLabel>().also { it += entry }
        while (work.isNotEmpty()) {
            val current = work.removeLast()
            if (!seen.add(current)) continue
            out += current
            when (val terminator = blocks.getValue(current).terminator) {
                is IRTerminator.Jump -> work += terminator.target
                is IRTerminator.Branch -> { work += terminator.thenTarget; work += terminator.elseTarget }
                is IRTerminator.Return -> Unit
            }
        }
        return out
    }

    /** A temp written in one block and read in another needs a slot; inside a block the stack is enough. */
    private fun crossBlockTemps(own: Set<IRLabel>): Set<Int> {
        val definedIn = HashMap<Int, IRLabel>()
        val cross = HashSet<Int>()
        for (label in own) for (insn in blocks.getValue(label).instructions) {
            insn.result?.let { temp -> definedIn.put(temp.id, label)?.let { if (it != label) cross += temp.id } }
        }
        for (label in own) {
            val block = blocks.getValue(label)
            for (insn in block.instructions) for (input in inputsOf(insn)) if (definedIn[input.id] != label) cross += input.id
            (block.terminator as? IRTerminator.Branch)?.let { if (definedIn[it.condition.id] != label) cross += it.condition.id }
        }
        return cross
    }

    private fun inputsOf(insn: IRInstruction): List<IRTemp> = when (insn) {
        is IRInstruction.Const, is IRInstruction.LoadParam, is IRInstruction.LoadState, is IRInstruction.LoadLocal,
        is IRInstruction.LoadMessage, is IRInstruction.LoadView -> emptyList()
        is IRInstruction.Convert -> listOf(insn.value)
        is IRInstruction.Copy -> listOf(insn.value)
        is IRInstruction.StoreState -> listOf(insn.value)
        is IRInstruction.StoreLocal -> listOf(insn.value)
        is IRInstruction.LoadField -> listOf(insn.receiver)
        is IRInstruction.LoadRefId -> listOf(insn.receiver)
        is IRInstruction.Unary -> listOf(insn.operand)
        is IRInstruction.Binary -> listOf(insn.left, insn.right)
        is IRInstruction.CallPure -> insn.arguments
        is IRInstruction.MakeRecord -> insn.fields.map { it.second }
        is IRInstruction.RandomDecision -> insn.arguments
        is IRInstruction.CallEffect -> insn.arguments
        is IRInstruction.SendEvent -> listOf(insn.target) + insn.fields.map { it.second }
        is IRInstruction.OptionIsSome -> listOf(insn.option)
        is IRInstruction.OptionUnwrap -> listOf(insn.option)
    }

    private fun synthetic(type: VType): Int { locals += type; return locals.size - 1 }
    private fun localSlot(slot: IRLocalSlot): Int = localOf.getOrPut(slot.index) { synthetic(vtypeOf(slot.type)) }
    private fun schemaIndex(name: String) = schemaOrder.indexOf(name).also { require(it >= 0) { "Unknown schema $name" } }

    private fun schemaNameOf(type: Type): String = when (type) {
        is Type.Event -> type.name
        is Type.Kind -> type.name
        else -> error("Not a record type: ${type.render()}")
    }

    private fun fieldIndex(recordType: Type, field: String): Int {
        val schema = schemas.getValue(schemaNameOf(recordType))
        return schema.fields.indexOfFirst { it.name == field }.also { require(it >= 0) { "Unknown field $field" } }
    }

    // ---------- block emission ----------

    private fun emitBlock(block: IRBlock) {
        asm.place(label(block.label))
        place.clear(); deferred.clear()
        uses = HashMap()
        for (insn in block.instructions) for (input in inputsOf(insn)) uses.merge(input.id, 1, Int::plus)
        (block.terminator as? IRTerminator.Branch)?.let { uses.merge(it.condition.id, 1, Int::plus) }

        val skip = skipLeading[block.label] ?: 0
        for ((index, insn) in block.instructions.withIndex()) {
            if (index < skip) { insn.result?.let { place[it.id] = Operand.ImmNone }; continue }
            handle(insn)
        }
        flushRandom()
        emitTerminator(block)
    }

    /** A value that is not a plain read is emitted where it is defined, unless it is used exactly once right after. */
    private fun handle(insn: IRInstruction) {
        val result = insn.result
        val direct = directOperand(insn)
        if (direct != null) {
            if (result != null && result.id in crossBlock) {
                val destination = crossSlot(result)
                emit(Insn(Op.MOV, dst = destination, srcs = listOf(direct), line = insn.span.startLine, column = insn.span.startColumn))
            } else if (result != null) place[result.id] = direct
            return
        }
        when (insn) {
            is IRInstruction.StoreState -> {
                val slot = stateSlot(insn.slot.name)
                invalidateState(slot)
                assign(Operand.State(slot), insn.value, insn.span)
            }
            is IRInstruction.StoreLocal -> {
                val slot = localSlot(insn.slot)
                invalidateLocal(slot)
                assign(Operand.Local(slot), insn.value, insn.span)
            }
            is IRInstruction.CallEffect, is IRInstruction.SendEvent -> { flushRandom(); emitCompute(insn, null) }
            else -> {
                requireNotNull(result)
                if (uses[result.id] == 1 && result.id !in crossBlock) {
                    deferred[result.id] = Deferred(insn, readsState(insn), readsLocal(insn), hasRandom(insn))
                } else {
                    emitCompute(insn, crossSlot(result))
                }
            }
        }
    }

    /** Copies a value into a slot, letting the producing instruction write there when it has not been emitted yet. */
    private fun assign(destination: Operand, value: IRTemp, span: colony.ast.SourceSpan) = forward(value, destination, span)

    private fun stateSlot(name: String) = behavior.state.slots.first { it.name == name }.index

    /** The operand of a temp; a deferred expression is emitted here and leaves its value on the stack. */
    private fun operandOf(temp: IRTemp): Operand {
        place[temp.id]?.let { return it }
        deferred.remove(temp.id)?.let { pending -> emitCompute(pending.insn, Operand.Stack); return Operand.Stack }
        crossLocal[temp.id]?.let { return Operand.Local(it) }
        error("Value %t${temp.id} is not available")
    }

    /** The slot of a value that has to survive past its block; the same temp always gets the same one. */
    private fun crossSlot(temp: IRTemp): Operand {
        val slot = crossLocal.getOrPut(temp.id) { synthetic(vtypeOf(temp.type)) }
        place[temp.id] = Operand.Local(slot)
        return Operand.Local(slot)
    }

    private fun emit(insn: Insn) = asm.emit(insn)

    private fun invalidateState(slot: Int) = materialize { it.readsState.contains(slot) }
    private fun invalidateLocal(slot: Int) = materialize { it.readsLocal.contains(slot) }
    private fun flushRandom() = materialize { it.random }

    /** Emits deferred expressions that must not move any further, into synthetic locals. */
    private fun materialize(predicate: (Deferred) -> Boolean) {
        while (true) {
            val entry = deferred.entries.firstOrNull { predicate(it.value) } ?: return
            deferred.remove(entry.key)
            emitCompute(entry.value.insn, crossSlot(entry.value.insn.result!!))
        }
    }

    private fun readsState(insn: IRInstruction): Set<Int> = inputsOf(insn).flatMapTo(HashSet()) { input ->
        (place[input.id] as? Operand.State)?.let { setOf(it.index) } ?: deferred[input.id]?.readsState ?: emptySet()
    }

    private fun readsLocal(insn: IRInstruction): Set<Int> = inputsOf(insn).flatMapTo(HashSet()) { input ->
        when (val operand = place[input.id]) {
            is Operand.Local -> setOf(operand.index)
            is Operand.LocalField -> setOf(operand.local)
            else -> deferred[input.id]?.readsLocal ?: emptySet()
        }
    }

    private fun hasRandom(insn: IRInstruction): Boolean =
        insn is IRInstruction.RandomDecision || inputsOf(insn).any { deferred[it.id]?.random == true }

    // ---------- operands ----------

    private fun constantOperand(value: ConstantValue): Operand = when (value) {
        is ConstantValue.Bool -> Operand.ImmB(value.value)
        is ConstantValue.Int64 -> Operand.ImmI(value.value)
        is ConstantValue.Real64 -> Operand.ImmF(value.value)
        is ConstantValue.Decimal -> Operand.ImmF(value.value.toDouble())
        is ConstantValue.Text -> Operand.ImmS(value.value)
        is ConstantValue.EnumValue -> Operand.ImmS(value.value)
        ConstantValue.None -> Operand.ImmNone
    }

    /** The operand of an instruction that is only a read, or null when it has to be computed. */
    private fun directOperand(insn: IRInstruction): Operand? = when (insn) {
        is IRInstruction.Const -> constantOperand(insn.value)
        is IRInstruction.LoadState -> Operand.State(stateSlot(insn.slot.name))
        is IRInstruction.LoadParam -> Operand.Param(insn.slot.index)
        is IRInstruction.LoadLocal -> Operand.Local(localSlot(insn.slot))
        is IRInstruction.LoadView -> Operand.View(viewIndex.getValue(insn.field.name))
        is IRInstruction.LoadMessage -> Operand.MsgRec
        is IRInstruction.LoadRefId -> place[insn.receiver.id]
        is IRInstruction.CallPure -> when (insn.intrinsic) {
            IRIntrinsicId.LOAD_TIME -> Operand.Time
            IRIntrinsicId.LOAD_THIS -> Operand.Self
            else -> null
        }
        is IRInstruction.LoadField -> {
            val index = fieldIndex(insn.receiver.type, insn.field.name)
            when (val receiver = place[insn.receiver.id]) {
                is Operand.Local -> Operand.LocalField(receiver.index, index)
                is Operand.View -> Operand.ViewField(receiver.index, index)
                Operand.MsgRec -> Operand.Msg(index)
                else -> null
            }
        }
        is IRInstruction.Convert -> if (convertOp(insn.fromType, insn.toType) == null) place[insn.value.id] else null
        is IRInstruction.Copy -> place[insn.value.id]
        else -> null
    }

    /** Null when the conversion changes nothing at runtime. */
    private fun convertOp(from: Type, to: Type): Op? {
        val source = vtypeOf(from)
        val target = vtypeOf(to)
        return when {
            source == VType.I64 && target == VType.F64 -> Op.TOF
            source is VType.Opt && target is VType.Opt && source.inner == VType.I64 && target.inner == VType.F64 -> Op.TOF_OPT
            else -> null
        }
    }

    private fun numericOp(type: Type, integer: Op, real: Op) = if (vtypeOf(type) == VType.I64) integer else real

    private fun relationOf(name: String): Int = when (name) {
        "EQ" -> Rel.EQ; "NEQ" -> Rel.NE; "LT" -> Rel.LT; "LE" -> Rel.LE; "GT" -> Rel.GT; "GE" -> Rel.GE
        else -> error("Not a relation: $name")
    }

    /** The comparison that fits the operand types; ordering is only ever asked for numbers. */
    private fun compareOp(left: Type, right: Type): Op {
        val a = vtypeOf(left)
        val b = vtypeOf(right)
        return when {
            a == VType.I64 && b == VType.I64 -> Op.CMP_I
            a == VType.F64 && b == VType.F64 -> Op.CMP_F
            (a == VType.I64 || a == VType.F64) && (b == VType.I64 || b == VType.F64) -> Op.CMP_N
            a == VType.Bool && b == VType.Bool -> Op.EQ_B
            a == VType.Str && b == VType.Str -> Op.EQ_S
            else -> Op.EQ_A
        }
    }

    private fun isComparison(insn: IRInstruction): Boolean =
        insn is IRInstruction.Binary && insn.op in setOf("EQ", "NEQ", "LT", "LE", "GT", "GE")

    private fun branchOf(op: Op): Op = when (op) {
        Op.CMP_I -> Op.BR_CMP_I; Op.CMP_F -> Op.BR_CMP_F; Op.CMP_N -> Op.BR_CMP_N
        Op.EQ_S -> Op.BR_EQ_S; Op.EQ_B -> Op.BR_EQ_B; else -> Op.BR_EQ_A
    }

    /** A plain move: the producing instruction writes the destination itself when it is still deferred. */
    private fun forward(value: IRTemp, destination: Operand?, at: colony.ast.SourceSpan) {
        val pending = deferred.remove(value.id)
        if (pending != null) emitCompute(pending.insn, destination)
        else emit(Insn(Op.MOV, dst = destination, srcs = listOf(operandOf(value)), line = at.startLine, column = at.startColumn))
    }

    /** Emits one computation with the given destination (null for the commands that produce no value). */
    private fun emitCompute(insn: IRInstruction, destination: Operand?) {
        val at = insn.span
        fun make(op: Op, rel: Int = 0, srcs: List<Operand>, fields: List<Int> = emptyList(), imm: Int = 0) =
            emit(Insn(op, rel, destination, srcs, fields, imm, null, at.startLine, at.startColumn))
        when (insn) {
            // A move of a value that has not been emitted yet is the value computed straight into the destination.
            is IRInstruction.Convert -> {
                val op = convertOp(insn.fromType, insn.toType)
                if (op == null) forward(insn.value, destination, at) else make(op, srcs = listOf(operandOf(insn.value)))
            }
            is IRInstruction.Copy -> forward(insn.value, destination, at)
            is IRInstruction.LoadField ->
                make(Op.GETF, srcs = listOf(operandOf(insn.receiver)), fields = listOf(fieldIndex(insn.receiver.type, insn.field.name)))
            is IRInstruction.LoadRefId -> make(Op.MOV, srcs = listOf(operandOf(insn.receiver)))
            is IRInstruction.Unary -> when (insn.op) {
                "NOT" -> make(Op.NOT, srcs = listOf(operandOf(insn.operand)))
                "MINUS" -> make(numericOp(insn.result.type, Op.NEG_I, Op.NEG_F), srcs = listOf(operandOf(insn.operand)))
                else -> make(Op.MOV, srcs = listOf(operandOf(insn.operand)))
            }
            is IRInstruction.Binary -> if (isComparison(insn)) {
                val op = compareOp(insn.left.type, insn.right.type)
                make(op, relationOf(insn.op), listOf(operandOf(insn.left), operandOf(insn.right)))
            } else {
                val op = when (insn.op) {
                    "ADD" -> numericOp(insn.result.type, Op.ADD_I, Op.ADD_F)
                    "SUB" -> numericOp(insn.result.type, Op.SUB_I, Op.SUB_F)
                    "MUL" -> numericOp(insn.result.type, Op.MUL_I, Op.MUL_F)
                    "DIV" -> Op.DIV_F
                    "MOD" -> Op.MOD_I
                    else -> error("Unsupported operator ${insn.op}")
                }
                make(op, srcs = listOf(operandOf(insn.left), operandOf(insn.right)))
            }
            is IRInstruction.CallPure -> when (insn.intrinsic) {
                IRIntrinsicId.INDEX -> make(Op.INDEX, srcs = insn.arguments.map(::operandOf))
                IRIntrinsicId.SOME -> make(Op.SOME, srcs = listOf(operandOf(insn.arguments.single())))
                IRIntrinsicId.NEAREST -> make(Op.NEAREST, srcs = listOf(operandOf(insn.arguments.single())))
                IRIntrinsicId.CLAMP -> make(numericOp(insn.result.type, Op.CLAMP_I, Op.CLAMP_F), srcs = insn.arguments.map(::operandOf))
                else -> error("Intrinsic ${insn.intrinsic} is not a computation")
            }
            is IRInstruction.RandomDecision ->
                make(if (insn.intrinsic == IRIntrinsicId.HAZARD) Op.HAZARD else Op.CHANCE, srcs = insn.arguments.map(::operandOf))
            is IRInstruction.MakeRecord -> {
                val schema = schemas.getValue(schemaNameOf(insn.result.type))
                val fields = insn.fields.map { (name, _) -> schema.fields.indexOfFirst { it.name == name } }
                require(fields.none { it < 0 }) { "Unknown record field in ${schema.name}" }
                make(Op.MKREC, srcs = insn.fields.map { operandOf(it.second) }, fields = fields, imm = schemaIndex(schema.name))
            }
            is IRInstruction.OptionIsSome -> make(Op.IS_SOME, srcs = listOf(operandOf(insn.option)))
            is IRInstruction.OptionUnwrap -> make(Op.UNWRAP, srcs = listOf(operandOf(insn.option)))
            is IRInstruction.CallEffect -> {
                val op = when (insn.intrinsic) {
                    IRIntrinsicId.POWER_REQUEST -> Op.POWER
                    IRIntrinsicId.DAMAGE_REQUEST -> Op.DAMAGE
                    IRIntrinsicId.MOTION_REQUEST -> Op.MOTION
                    IRIntrinsicId.REPAIR_REQUEST -> Op.REPAIR
                    else -> error("Not an effect: ${insn.intrinsic}")
                }
                emit(Insn(op, srcs = insn.arguments.map(::operandOf), line = at.startLine, column = at.startColumn))
            }
            is IRInstruction.SendEvent -> {
                val schema = schemas.getValue(events.getValue(insn.eventId))
                val fields = insn.fields.map { (name, _) -> schema.fields.indexOfFirst { it.name == name } }
                require(fields.none { it < 0 }) { "Unknown field of event ${schema.name}" }
                val srcs = listOf(operandOf(insn.target)) + insn.fields.map { operandOf(it.second) }
                emit(Insn(Op.SEND, srcs = srcs, fields = fields, imm = insn.eventId, line = at.startLine, column = at.startColumn))
            }
            else -> make(Op.MOV, srcs = listOf(directOperand(insn) ?: error("Cannot compute $insn")))
        }
    }

    // ---------- terminators ----------

    /**
     * A conditional jump takes its comparison with it, and the pattern that `if let` produces (test, jump,
     * unwrap, bind) becomes a single LET_SOME. The false branch is laid out next, so it needs no jump.
     */
    private fun emitTerminator(block: IRBlock) {
        when (val terminator = block.terminator) {
            is IRTerminator.Return -> emit(Insn(Op.RET, line = terminator.span.startLine, column = terminator.span.startColumn))
            is IRTerminator.Jump -> emit(Insn(Op.JMP, target = label(terminator.target), line = terminator.span.startLine, column = terminator.span.startColumn))
            is IRTerminator.Branch -> {
                val line = terminator.span.startLine
                val column = terminator.span.startColumn
                val onTrue = label(terminator.thenTarget)
                val condition = deferred[terminator.condition.id]?.insn
                val binding = bindingOf(terminator, condition)
                when {
                    binding != null -> {
                        deferred.remove(terminator.condition.id)
                        val option = operandOf((condition as IRInstruction.OptionIsSome).option)
                        skipLeading[terminator.thenTarget] = 2
                        emit(Insn(Op.LET_SOME, dst = Operand.Local(binding), srcs = listOf(option),
                            target = label(terminator.elseTarget), line = line, column = column))
                        emit(Insn(Op.JMP, target = onTrue, line = line, column = column))
                        return
                    }
                    condition != null && isComparison(condition) -> {
                        deferred.remove(terminator.condition.id)
                        val binary = condition as IRInstruction.Binary
                        val op = branchOf(compareOp(binary.left.type, binary.right.type))
                        emit(Insn(op, relationOf(binary.op), srcs = listOf(operandOf(binary.left), operandOf(binary.right)),
                            target = onTrue, line = line, column = column))
                    }
                    condition is IRInstruction.OptionIsSome -> {
                        deferred.remove(terminator.condition.id)
                        emit(Insn(Op.BR_SOME, srcs = listOf(operandOf(condition.option)), target = onTrue, line = line, column = column))
                    }
                    else -> emit(Insn(Op.BR_T, srcs = listOf(operandOf(terminator.condition)), target = onTrue, line = line, column = column))
                }
                emit(Insn(Op.JMP, target = label(terminator.elseTarget), line = line, column = column))
            }
        }
    }

    /**
     * The local an `if let` binds, when this branch is exactly that pattern: the condition tests the same option
     * the target block unwraps into a local right away. Returns null when the shape is anything else.
     */
    private fun bindingOf(terminator: IRTerminator.Branch, condition: IRInstruction?): Int? {
        if (condition !is IRInstruction.OptionIsSome) return null
        val target = blocks[terminator.thenTarget] ?: return null
        if (skipLeading.containsKey(terminator.thenTarget)) return null
        val unwrap = target.instructions.getOrNull(0) as? IRInstruction.OptionUnwrap ?: return null
        val store = target.instructions.getOrNull(1) as? IRInstruction.StoreLocal ?: return null
        if (unwrap.option.id != condition.option.id || store.value.id != unwrap.result.id) return null
        val laterUse = target.instructions.drop(2).any { insn -> inputsOf(insn).any { it.id == unwrap.result.id } }
        if (laterUse || unwrap.result.id in crossBlock) return null
        return localSlot(store.slot)
    }
}
