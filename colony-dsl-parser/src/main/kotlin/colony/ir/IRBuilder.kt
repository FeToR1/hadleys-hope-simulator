package colony.ir

import colony.ast.*
import colony.semantics.*

/** Low-level typed IR builder. It consumes only AST + SemanticModel; ANTLR is not referenced. */
class IRBuilder(
    private val model: SemanticModel,
    private val environment: SemanticEnvironment = SemanticEnvironment(),
) {
    private var nextTemp = 0
    private var nextBlock = 0
    private var nextHandler = 0
    private var nextEvent = 0
    private var nextRandomSite = 0
    private val eventIds = linkedMapOf<String, Int>()
    private val eventTypes = linkedMapOf<String, Type.Event>()

    private data class BuildState(
        val behavior: BehaviorDecl,
        val stateSlots: Map<String, IRStateSlot>,
        val params: Map<String, IRSlot>,
        val messageFields: MutableMap<String, IRField> = linkedMapOf(),
        val locals: MutableMap<Symbol.Local, IRLocalSlot> = linkedMapOf(),
    )

    private class BlockBuilder(val label: IRLabel) {
        val instructions = mutableListOf<IRInstruction>()
        var terminator: IRTerminator? = null
    }

    fun build(program: Program): IRProgram {
        collectEvents(program)
        val behaviors = program.declarations.filterIsInstance<BehaviorDecl>().map(::buildBehavior)
        val events = eventTypes.entries.map { (name, type) ->
            IREventSchema(eventIds.getValue(name), name, type.fields.map { IRField(it.key, it.value) })
        }
        return IRProgram(events, behaviors)
    }

    private fun collectEvents(program: Program) {
        for (decl in program.declarations.filterIsInstance<EventDecl>()) {
            val id = eventIds.getOrPut(decl.name) { nextEvent++ }
            val fields = linkedMapOf<String, Type>()
            for (field in decl.fields) fields[field.name] = resolveTypeRef(field.type, "<event>", emptyMap())
            eventTypes[decl.name] = Type.Event(decl.name, fields)
            if (id < 0) error("unreachable")
        }
    }

    private fun buildBehavior(behavior: BehaviorDecl): IRBehavior {
        val stateSlots = behavior.members.filterIsInstance<StateDecl>().mapIndexed { index, state ->
            IRStateSlot(index, state.name, resolveTypeRef(state.type, behavior.name, enumTypes(behavior)))
        }.associateBy(IRStateSlot::name)
        val paramSlots = behavior.members.filterIsInstance<ParamDecl>().mapIndexed { index, param ->
            IRSlot(index, param.name, resolveTypeRef(param.type, behavior.name, enumTypes(behavior)))
        }.associateBy(IRSlot::name)

        val blocks = mutableListOf<IRBlock>()
        val subscriptions = mutableListOf<IRSubscription>()
        val timers = mutableListOf<IRTimer>()
        val localsByHandler = linkedMapOf<Int, List<IRLocalSlot>>()
        val entries = linkedMapOf<Int, IRLabel>()
        val state = BuildState(behavior, stateSlots, paramSlots)

        for (rule in behavior.members.filterIsInstance<RuleDecl>()) {
            val handlerId = nextHandler++
            val entry = newBlock()
            entries[handlerId] = entry.label
            state.locals.clear()
            state.messageFields.clear()
            when (rule) {
                is OnRule -> {
                    val eventId = eventIds[rule.eventType] ?: error("Unknown event in IR: ${rule.eventType}")
                    val event = eventTypes[rule.eventType] ?: error("Unknown event type in IR: ${rule.eventType}")
                    state.messageFields.putAll(event.fields.mapValues { IRField(it.key, it.value) })
                    subscriptions += IRSubscription(handlerId, eventId, rule.ruleName, rule.messageName)
                    buildRuleBody(rule.body, state, entry, blocks)
                    localsByHandler[handlerId] = state.locals.values.toList()
                }
                is EveryRule -> {
                    val ctx = model.ruleContext(rule) ?: error("Missing semantic rule context")
                    val period = ctx.periodSeconds ?: error("Timer without period")
                    val ticks = ctx.periodTicks ?: error("Timer without period ticks")
                    timers += IRTimer(handlerId, rule.ruleName, period, ticks)
                    buildRuleBody(rule.body, state, entry, blocks)
                    localsByHandler[handlerId] = state.locals.values.toList()
                }
            }
        }

        return IRBehavior(
            name = behavior.name,
            targetKind = behavior.targetType,
            params = paramSlots.values.toList(),
            state = IRStateLayout(stateSlots.values.toList()),
            subscriptions = subscriptions,
            timers = timers,
            localSlotsByHandlerId = localsByHandler,
            blocks = blocks,
            entryByHandlerId = entries,
        )
    }

    private fun buildRuleBody(
        astBlock: Block,
        state: BuildState,
        entry: BlockBuilder,
        blocks: MutableList<IRBlock>,
    ) {
        val end = emitStatements(astBlock.statements, state, entry, blocks)
        if (end.terminator == null) {
            end.terminator = IRTerminator.Return(astBlock.span)
            blocks += freeze(end)
        }
    }

    private fun emitStatements(
        statements: List<Stmt>,
        state: BuildState,
        entry: BlockBuilder,
        blocks: MutableList<IRBlock>,
    ): BlockBuilder {
        var current = entry
        for (stmt in statements) {
            if (current.terminator != null) break
            current = emitStatement(stmt, state, current, blocks)
        }
        return current
    }

    private fun emitStatement(stmt: Stmt, state: BuildState, current: BlockBuilder, blocks: MutableList<IRBlock>): BlockBuilder = when (stmt) {
        is LetStmt -> {
            val value = emitExpr(stmt.value, state, current)
            val symbol = model.localSymbol(stmt) ?: error("Missing local symbol for '${stmt.name}'")
            val slot = state.locals.getOrPut(symbol) { IRLocalSlot(state.locals.size, symbol.name, symbol.type) }
            val coerced = coerce(value, slot.type, stmt.value.span, current)
            current.instructions += IRInstruction.StoreLocal(slot, coerced, stmt.span)
            current
        }
        is AssignStmt -> {
            val value = emitExpr(stmt.value, state, current)
            val symbol = model.resolved(stmt.target) ?: error("Missing resolved lvalue")
            if (stmt.target.parts.size != 1) error("Only single-name assignments reach IR")
            when (symbol) {
                is Symbol.State -> {
                    val slot = state.stateSlots.getValue(symbol.name)
                    val coerced = coerce(value, slot.type, stmt.value.span, current)
                    current.instructions += IRInstruction.StoreState(slot, coerced, stmt.span)
                }
                is Symbol.Local -> {
                    val slot = state.locals.getOrPut(symbol) { IRLocalSlot(state.locals.size, symbol.name, symbol.type) }
                    val coerced = coerce(value, slot.type, stmt.value.span, current)
                    current.instructions += IRInstruction.StoreLocal(slot, coerced, stmt.span)
                }
                else -> error("Read-only assignment reached IR: ${symbol.name}")
            }
            current
        }
        is ExprStmt -> {
            emitExpr(stmt.expression, state, current)
            current
        }
        is SendStmt -> {
            val target = emitExpr(stmt.target, state, current)
            val eventId = eventIds[stmt.eventType] ?: error("Unknown event '${stmt.eventType}'")
            val event = eventTypes[stmt.eventType] ?: error("Unknown event '${stmt.eventType}'")
            val fields = stmt.fields.map { field ->
                val raw = emitExpr(field.value, state, current)
                val expected = event.fields[field.name] ?: raw.type
                field.name to coerce(raw, expected, field.value.span, current)
            }
            current.instructions += IRInstruction.SendEvent(eventId, target, fields, stmt.span)
            current
        }
        is IfStmt -> emitIf(stmt, state, current, blocks)
    }

    private fun emitIf(
        stmt: IfStmt,
        state: BuildState,
        current: BlockBuilder,
        blocks: MutableList<IRBlock>,
    ): BlockBuilder {
        val conditionValue = when (val condition = stmt.condition) {
            is ExprCondition -> emitExpr(condition.expression, state, current)
            is LetCondition -> emitExpr(condition.value, state, current)
        }
        val conditionBool = if (stmt.condition is LetCondition) {
            temp(Type.Bool).also { current.instructions += IRInstruction.OptionIsSome(it, conditionValue, stmt.condition.span) }
        } else conditionValue

        val thenEntry = newBlock()
        val elseEntry = newBlock()
        val join = newBlock()
        current.terminator = IRTerminator.Branch(conditionBool, thenEntry.label, elseEntry.label, stmt.span)
        blocks += freeze(current)

        if (stmt.condition is LetCondition) {
            val localSymbol = model.localSymbol(stmt.condition)
            val inner = (model.typeOf(stmt.condition.value) as? Type.Option)?.inner ?: Type.Unknown
            if (localSymbol != null) {
                val slot = state.locals.getOrPut(localSymbol) { IRLocalSlot(state.locals.size, localSymbol.name, inner) }
                val unwrapped = temp(inner)
                thenEntry.instructions += IRInstruction.OptionUnwrap(unwrapped, conditionValue, stmt.condition.span)
                thenEntry.instructions += IRInstruction.StoreLocal(slot, unwrapped, stmt.condition.span)
            }
        }

        val thenEnd = emitStatements(stmt.thenBranch.statements, state, thenEntry, blocks)
        if (thenEnd.terminator == null) thenEnd.terminator = IRTerminator.Jump(join.label, stmt.thenBranch.span)
        blocks += freeze(thenEnd)

        when (val elseBranch = stmt.elseBranch) {
            null -> {
                elseEntry.terminator = IRTerminator.Jump(join.label, stmt.span)
                blocks += freeze(elseEntry)
            }
            is ElseBlock -> {
                val elseEnd = emitStatements(elseBranch.block.statements, state, elseEntry, blocks)
                if (elseEnd.terminator == null) elseEnd.terminator = IRTerminator.Jump(join.label, elseBranch.block.span)
                blocks += freeze(elseEnd)
            }
            is ElseIfBranch -> emitElseIf(elseBranch.condition, elseBranch.block, state, elseEntry, join, blocks, elseBranch.span)
        }
        return join
    }

    private fun emitElseIf(
        condition: Condition,
        block: Block,
        state: BuildState,
        entry: BlockBuilder,
        outerJoin: BlockBuilder,
        blocks: MutableList<IRBlock>,
        span: SourceSpan,
    ) {
        val value = when (condition) {
            is ExprCondition -> emitExpr(condition.expression, state, entry)
            is LetCondition -> emitExpr(condition.value, state, entry)
        }
        val bool = if (condition is LetCondition) temp(Type.Bool).also { entry.instructions += IRInstruction.OptionIsSome(it, value, condition.span) } else value
        val thenEntry = newBlock()
        val nestedElse = newBlock()
        entry.terminator = IRTerminator.Branch(bool, thenEntry.label, nestedElse.label, span)
        blocks += freeze(entry)

        val thenEnd = emitStatements(block.statements, state, thenEntry, blocks)
        if (thenEnd.terminator == null) thenEnd.terminator = IRTerminator.Jump(outerJoin.label, block.span)
        blocks += freeze(thenEnd)
        nestedElse.terminator = IRTerminator.Jump(outerJoin.label, span)
        blocks += freeze(nestedElse)
    }

    private fun emitExpr(expr: Expr, state: BuildState, current: BlockBuilder): IRTemp = when (expr) {
        is BoolLiteral -> temp(model.typeOf(expr)).also { current.instructions += IRInstruction.Const(it, ConstantValue.Bool(expr.value), expr.span) }
        is NoneLiteral -> temp(model.typeOf(expr)).also { current.instructions += IRInstruction.Const(it, ConstantValue.None, expr.span) }
        is StringLiteral -> temp(model.typeOf(expr)).also { current.instructions += IRInstruction.Const(it, ConstantValue.Text(expr.value), expr.span) }
        is NumberLiteral -> {
            val constant = LiteralEvaluator.evaluate(expr) ?: error("Invalid numeric literal")
            temp(model.typeOf(expr)).also { current.instructions += IRInstruction.Const(it, constant.value, expr.span) }
        }
        is NameExpr -> emitName(expr, state, current)
        is ParenExpr -> emitExpr(expr.expression, state, current)
        is UnaryExpr -> {
            val operand = emitExpr(expr.operand, state, current)
            temp(model.typeOf(expr)).also { current.instructions += IRInstruction.Unary(it, expr.operator.name, operand, expr.span) }
        }
        is BinaryExpr -> {
            var left = emitExpr(expr.left, state, current)
            var right = emitExpr(expr.right, state, current)
            val resultType = model.typeOf(expr)
            if (resultType == Type.Real64) {
                if (left.type == Type.Int64) left = coerce(left, Type.Real64, expr.left.span, current)
                if (right.type == Type.Int64) right = coerce(right, Type.Real64, expr.right.span, current)
            }
            temp(resultType).also { current.instructions += IRInstruction.Binary(it, expr.operator.name, left, right, expr.span) }
        }
        is MemberExpr -> emitMember(expr, state, current)
        is CallExpr -> emitCall(expr, state, current)
        is IndexExpr -> {
            val receiver = emitExpr(expr.receiver, state, current)
            val index = emitExpr(expr.index, state, current)
            temp(model.typeOf(expr)).also { current.instructions += IRInstruction.CallPure(it, IRIntrinsicId.INDEX, listOf(receiver, index), expr.span) }
        }
        is RecordExpr -> {
            val fields = expr.fields.map { it.name to emitExpr(it.value, state, current) }
            temp(model.typeOf(expr)).also { current.instructions += IRInstruction.MakeRecord(it, fields, expr.span) }
        }
    }

    private fun emitName(expr: NameExpr, state: BuildState, current: BlockBuilder): IRTemp {
        val symbol = model.resolved(expr) ?: error("Unresolved name '${expr.name}'")
        return when (symbol) {
            is Symbol.State -> temp(symbol.type).also { current.instructions += IRInstruction.LoadState(it, state.stateSlots.getValue(symbol.name), expr.span) }
            is Symbol.Param -> temp(symbol.type).also { current.instructions += IRInstruction.LoadParam(it, state.params.getValue(symbol.name), expr.span) }
            is Symbol.Local -> {
                val slot = state.locals.getOrPut(symbol) { IRLocalSlot(state.locals.size, symbol.name, symbol.type) }
                temp(symbol.type).also { current.instructions += IRInstruction.LoadLocal(it, slot, expr.span) }
            }
            is Symbol.Message -> temp(symbol.type).also { current.instructions += IRInstruction.LoadMessage(it, eventIdOf(symbol.type.name), symbol.type, expr.span) }
            is Symbol.EnumValueSymbol -> temp(symbol.type).also { current.instructions += IRInstruction.Const(it, ConstantValue.EnumValue(symbol.type.enumType, symbol.type.value), expr.span) }
            is Symbol.Implicit -> when (val implicitType = symbol.type) {
                Type.Duration -> temp(Type.Duration).also { current.instructions += IRInstruction.CallPure(it, IRIntrinsicId.LOAD_TIME, emptyList(), expr.span) }
                is Type.View -> temp(implicitType).also { current.instructions += IRInstruction.CallPure(it, IRIntrinsicId.LOAD_VIEW, emptyList(), expr.span) }
                is Type.Ref -> temp(implicitType).also { current.instructions += IRInstruction.CallPure(it, IRIntrinsicId.LOAD_THIS, emptyList(), expr.span) }
                Type.IntrinsicNamespace -> error("Intrinsic namespace is not a runtime value")
                else -> error("Unsupported implicit symbol ${symbol.name}")
            }
        }
    }

    private fun emitMember(expr: MemberExpr, state: BuildState, current: BlockBuilder): IRTemp {
        val receiverType = model.typeOf(expr.receiver)
        return when {
            receiverType is Type.View -> {
                val fieldType = environment.kindContract(receiverType.kind)?.viewFields?.get(expr.member) ?: Type.Unknown
                temp(fieldType).also { current.instructions += IRInstruction.LoadView(it, receiverType.kind, IRField(expr.member, fieldType), expr.span) }
            }
            receiverType is Type.Event -> {
                val fieldType = receiverType.fields[expr.member] ?: Type.Unknown
                temp(fieldType).also { current.instructions += IRInstruction.LoadMessageField(it, IRField(expr.member, fieldType), expr.span) }
            }
            receiverType is Type.Ref && expr.member == "id" -> {
                val receiver = emitExpr(expr.receiver, state, current)
                temp(Type.String).also { current.instructions += IRInstruction.LoadRefId(it, receiver, expr.span) }
            }
            else -> error("Unsupported member ${expr.member} on ${receiverType.render()}")
        }
    }

    private fun emitCall(expr: CallExpr, state: BuildState, current: BlockBuilder): IRTemp {
        val path = intrinsicPath(expr.callee) ?: error("Unknown call target")
        val intrinsic = model.intrinsic(expr) ?: Intrinsics.resolve(path) ?: error("Unknown intrinsic '$path'")
        val args = expr.arguments.map { emitExpr(it, state, current) }
        val intrinsicId = intrinsicId(path)
        val loweredArgs = if (path == "chance" &&
            args.isNotEmpty() &&
            (args[0].type == Type.Int64 || args[0].type == Type.Real64)
        ) {
            args.toMutableList().also { it[0] = coerce(it[0], Type.Probability, expr.arguments[0].span, current) }
        } else args
        return when (intrinsic.effect) {
            EffectClass.PURE -> temp(model.typeOf(expr)).also { current.instructions += IRInstruction.CallPure(it, intrinsicId, loweredArgs, expr.span) }
            EffectClass.RANDOM -> temp(model.typeOf(expr)).also { current.instructions += IRInstruction.RandomDecision(it, intrinsicId, loweredArgs, nextRandomSite++, expr.span) }
            EffectClass.EXTERNAL -> {
                current.instructions += IRInstruction.CallEffect(intrinsicId, loweredArgs, expr.span)
                temp(Type.Void)
            }
            EffectClass.STATE -> error("State effect is represented by STORE_STATE")
        }
    }

    private fun coerce(value: IRTemp, expected: Type, span: SourceSpan, current: BlockBuilder): IRTemp {
        if (value.type == expected) return value
        val isProbabilityLiteralCoercion = expected == Type.Probability &&
            (value.type == Type.Int64 || value.type == Type.Real64)
        val isNoneOptionCoercion = expected is Type.Option &&
            value.type is Type.Option &&
            value.type.inner == Type.Unknown
        if (!isProbabilityLiteralCoercion && !isNoneOptionCoercion && !expected.isAssignableFrom(value.type)) {
            error("IR type mismatch: ${value.type.render()} -> ${expected.render()}")
        }
        val result = temp(expected)
        current.instructions += IRInstruction.Convert(result, value.type, expected, value, span)
        return result
    }

    private fun intrinsicId(path: String): IRIntrinsicId = when (path) {
        "load_time" -> IRIntrinsicId.LOAD_TIME
        "load_view" -> IRIntrinsicId.LOAD_VIEW
        "load_this" -> IRIntrinsicId.LOAD_THIS
        "index" -> IRIntrinsicId.INDEX
        "make_record" -> IRIntrinsicId.MAKE_RECORD
        "some" -> IRIntrinsicId.SOME
        "clamp" -> IRIntrinsicId.CLAMP
        "nearest" -> IRIntrinsicId.NEAREST
        "chance" -> IRIntrinsicId.CHANCE
        "hazard" -> IRIntrinsicId.HAZARD
        "power.request" -> IRIntrinsicId.POWER_REQUEST
        "damage.request" -> IRIntrinsicId.DAMAGE_REQUEST
        "motion.request" -> IRIntrinsicId.MOTION_REQUEST
        "repair.request" -> IRIntrinsicId.REPAIR_REQUEST
        else -> error("No IR intrinsic id for '$path'")
    }

    private fun intrinsicPath(expr: Expr): String? = when (expr) {
        is NameExpr -> expr.name
        is MemberExpr -> intrinsicPath(expr.receiver)?.let { "$it.${expr.member}" }
        else -> null
    }

    private fun freeze(block: BlockBuilder): IRBlock =
        IRBlock(block.label, block.instructions.toList(), block.terminator ?: error("IR block without terminator"))

    private fun temp(type: Type): IRTemp = IRTemp(nextTemp++, type)
    private fun newBlock(): BlockBuilder = BlockBuilder(IRLabel(nextBlock++))


    private fun eventIdOf(name: String): Int = eventIds[name] ?: error("Unknown message event '$name'")
    private fun enumTypes(behavior: BehaviorDecl): Map<String, Type.Enum> =
        behavior.members.filterIsInstance<EnumDecl>().associate { it.name to Type.Enum(behavior.name, it.name) }
}
