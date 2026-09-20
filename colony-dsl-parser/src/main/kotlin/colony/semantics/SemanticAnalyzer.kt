package colony.semantics

import colony.ast.*
import java.math.BigDecimal

/** Compile-time environment for the currently selected simulation step. */
data class SemanticOptions(
    val deltaTime: String = "1s",
    val environment: SemanticEnvironment = SemanticEnvironment(),
)

class SemanticAnalyzer(
    private val options: SemanticOptions = SemanticOptions(),
) {
    private val diagnostics = DiagnosticSink()
    private val model = SemanticModel()
    private val symbols = SymbolTable()
    private val events = linkedMapOf<String, Type.Event>()

    private val deltaTimeSeconds: BigDecimal = parseDuration(options.deltaTime)

    fun analyze(program: Program): SemanticResult {
        collectTopLevel(program)
        program.declarations.filterIsInstance<BehaviorDecl>().forEach(::analyzeBehavior)
        if (deltaTimeSeconds <= BigDecimal.ZERO) {
            diagnostics.error("SEM_BAD_DT", program.span, "Δt должен быть положительным", "Δt must be positive")
        }
        return SemanticResult(model, diagnostics.items.sortedWith(compareBy({ it.span.startLine }, { it.span.startColumn }, { it.code })))
    }

    fun analyzeOrThrow(program: Program): SemanticModel {
        val result = analyze(program)
        return result.requireValid()
    }

    private fun collectTopLevel(program: Program) {
        for (decl in program.declarations) {
            when (decl) {
                is EventDecl -> {
                    if (events.containsKey(decl.name)) {
                        diagnostics.error(
                            "SEM_DUPLICATE_EVENT", decl.span,
                            "Повторное объявление события '${decl.name}'",
                            "Duplicate event declaration '${decl.name}'",
                        )
                        continue
                    }
                    val fields = linkedMapOf<String, Type>()
                    for (field in decl.fields) {
                        if (fields.containsKey(field.name)) {
                            diagnostics.error(
                                "SEM_DUPLICATE_FIELD", field.span,
                                "Поле события '${decl.name}.${field.name}' объявлено повторно",
                                "Event field '${decl.name}.${field.name}' is declared more than once",
                            )
                        } else {
                            fields[field.name] = resolveType(field.type, "<global>", emptyMap(), field.span)
                        }
                    }
                    events[decl.name] = Type.Event(decl.name, fields)
                }
                is BehaviorDecl -> {
                    // Behavior names share one global namespace.
                    val existing = symbols.global.lookupLocal(decl.name)
                    if (existing != null) {
                        diagnostics.error(
                            "SEM_DUPLICATE_BEHAVIOR", decl.span,
                            "Повторное объявление behavior '${decl.name}'",
                            "Duplicate behavior declaration '${decl.name}'",
                        )
                    } else {
                        symbols.global.declare(Symbol.Implicit(decl.name, Type.Kind(decl.targetType), decl.span), diagnostics)
                    }
                }
            }
        }
    }

    private fun analyzeBehavior(behavior: BehaviorDecl) {
        val enums = linkedMapOf<String, Type.Enum>()
        for (member in behavior.members) {
            if (member is EnumDecl) {
                val type = Type.Enum(behavior.name, member.name)
                if (enums.containsKey(member.name)) {
                    diagnostics.error("SEM_DUPLICATE_ENUM", member.span, "Enum '${member.name}' объявлен повторно", "Enum '${member.name}' is declared twice")
                } else {
                    enums[member.name] = type
                }
                val seenValues = mutableSetOf<String>()
                for (value in member.values) {
                    if (!seenValues.add(value)) {
                        diagnostics.error("SEM_DUPLICATE_ENUM_VALUE", member.span, "Значение enum '${member.name}.$value' объявлено повторно", "Enum value '${member.name}.$value' is declared twice")
                    }
                }
            }
        }

        val scope = symbols.createBehaviorScope(behavior.name)
        var nextStateSlot = 0
        scope.declare(Symbol.Implicit("time", Type.Duration), diagnostics)
        scope.declare(Symbol.Implicit("view", Type.View(behavior.targetType)), diagnostics)
        scope.declare(Symbol.Implicit("this", Type.Ref(behavior.targetType)), diagnostics)
        // Intrinsic namespace is recognized semantically; these symbols are only placeholders for name lookup.
        listOf("chance", "hazard", "some", "nearest", "clamp", "power", "damage", "motion", "repair")
            .forEach { scope.declare(Symbol.Implicit(it, Type.IntrinsicNamespace), diagnostics) }

        for (member in behavior.members) {
            when (member) {
                is EnumDecl -> member.values.forEach { value ->
                    val enumType = enums[member.name] ?: return@forEach
                    scope.declare(Symbol.EnumValueSymbol(value, Type.EnumValue(enumType, value), member.span), diagnostics)
                }
                is ParamDecl -> {
                    val type = resolveType(member.type, behavior.name, enums, member.span)
                    scope.declare(Symbol.Param(member.name, type, member.span), diagnostics)
                }
                is StateDecl -> {
                    val type = resolveType(member.type, behavior.name, enums, member.span)
                    val checker = checker(scope, behavior, null, null, enums)
                    val actual = checker.checkExpression(member.initializer)
                    val symbol = Symbol.State(member.name, type, nextStateSlot++, member.span)
                    scope.declare(symbol, diagnostics)
                    if (!type.isAssignableFrom(actual)) {
                        diagnostics.error(
                            "SEM_TYPE_MISMATCH", member.initializer.span,
                            "Инициализатор state имеет тип ${actual.render()}, ожидался ${type.render()}",
                            "State initializer has type ${actual.render()}, expected ${type.render()}",
                        )
                    }
                }
                is RuleDecl -> analyzeRule(behavior, member, scope, enums)
            }
        }
    }

    private fun analyzeRule(
        behavior: BehaviorDecl,
        rule: RuleDecl,
        behaviorScope: Scope,
        enums: Map<String, Type.Enum>,
    ) {
        val scope = behaviorScope.child()
        val periodSeconds: BigDecimal?
        val every: Boolean
        val ruleName: String

        when (rule) {
            is OnRule -> {
                every = false
                periodSeconds = null
                ruleName = rule.ruleName
                val event = events[rule.eventType]
                if (event == null) {
                    diagnostics.error("SEM_UNKNOWN_EVENT", rule.span, "Неизвестное событие '${rule.eventType}'", "Unknown event '${rule.eventType}'")
                } else {
                    scope.declare(Symbol.Message(rule.messageName, event, rule.span), diagnostics)
                }
            }
            is EveryRule -> {
                every = true
                periodSeconds = durationValue(rule.period)
                ruleName = rule.ruleName
                if (periodSeconds <= BigDecimal.ZERO) {
                    diagnostics.error("SEM_BAD_PERIOD", rule.period.span, "Период every должен быть положительным", "every period must be positive")
                } else if (periodSeconds.remainder(deltaTimeSeconds) != BigDecimal.ZERO) {
                    diagnostics.error(
                        "SEM_PERIOD_GRID",
                        rule.period.span,
                        "Период ${periodSeconds.stripTrailingZeros().toPlainString()}s должен быть кратен Δt=${deltaTimeSeconds.stripTrailingZeros().toPlainString()}s",
                        "Period ${periodSeconds.stripTrailingZeros().toPlainString()}s must be a multiple of Δt=${deltaTimeSeconds.stripTrailingZeros().toPlainString()}s",
                    )
                }
            }
        }

        checkDuplicateRuleName(rule, behavior)
        val checker = checker(scope, behavior, periodSeconds, every, enums)
        val hasHazard = containsIntrinsic(ruleBody(rule), "hazard")
        model.record(
            rule,
            RuleContext(
                behaviorName = behavior.name,
                targetKind = behavior.targetType,
                ruleName = ruleName,
                periodSeconds = periodSeconds,
                periodTicks = periodSeconds?.takeIf { it > BigDecimal.ZERO && it.remainder(deltaTimeSeconds) == BigDecimal.ZERO }
                    ?.divide(deltaTimeSeconds)?.longValueExact(),
                isTimer = every,
                hasHazard = hasHazard,
            ),
        )
        checkBlock(ruleBody(rule), checker, scope, enums)
    }

    private val ruleNamesByBehavior = mutableMapOf<String, MutableSet<String>>()

    private fun checkDuplicateRuleName(rule: RuleDecl, behavior: BehaviorDecl) {
        val names = ruleNamesByBehavior.getOrPut(behavior.name) { linkedSetOf() }
        val name = when (rule) {
            is OnRule -> rule.ruleName
            is EveryRule -> rule.ruleName
        }
        if (!names.add(name)) {
            diagnostics.error("SEM_DUPLICATE_RULE", rule.span, "Правило '${behavior.name}.$name' объявлено повторно", "Rule '${behavior.name}.$name' is declared twice")
        }
    }

    private fun checkBlock(block: Block, checker: TypeChecker, parentScope: Scope, enums: Map<String, Type.Enum>) {
        checkBlockInto(block, checker, parentScope.child(), enums)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checkBlockInto(block: Block, checker: TypeChecker, scope: Scope, enums: Map<String, Type.Enum>) {
        for (stmt in block.statements) {
            when (stmt) {
                is LetStmt -> {
                    val valueType = checkerFor(scope, checker, enums).checkExpression(stmt.value)
                    val declared = stmt.declaredType?.let { resolveType(it, checkerBehaviorName(checker), enums, it.span) }
                    val finalType = declared ?: valueType
                    if (declared != null && !declared.isAssignableFrom(valueType)) {
                        diagnostics.error("SEM_TYPE_MISMATCH", stmt.value.span, "let '${stmt.name}' ожидает ${declared.render()}, получено ${valueType.render()}", "let '${stmt.name}' expects ${declared.render()}, got ${valueType.render()}")
                    }
                    val symbol = Symbol.Local(stmt.name, finalType, stmt.span)
                    scope.declare(symbol, diagnostics)
                    model.record(stmt, symbol)
                }
                is AssignStmt -> checkerFor(scope, checker, enums).checkAssignment(stmt)
                is SendStmt -> {
                    val event = events[stmt.eventType]
                    if (event == null) {
                        diagnostics.error("SEM_UNKNOWN_EVENT", stmt.span, "Неизвестное событие '${stmt.eventType}'", "Unknown event '${stmt.eventType}'")
                        checkerFor(scope, checker, enums).checkExpression(stmt.target)
                        stmt.fields.forEach { checkerFor(scope, checker, enums).checkExpression(it.value) }
                    } else {
                        checkerFor(scope, checker, enums).checkSend(stmt, event)
                    }
                }
                is ExprStmt -> checkerFor(scope, checker, enums).checkExpression(stmt.expression)
                is IfStmt -> checkIf(stmt, checker, scope, enums)
            }
        }
    }

    private fun checkIf(stmt: IfStmt, checker: TypeChecker, parent: Scope, enums: Map<String, Type.Enum>) {
        val checkerHere = checkerFor(parent, checker, enums)
        when (val condition = stmt.condition) {
            is ExprCondition -> {
                val type = checkerHere.checkExpression(condition.expression)
                if (type != Type.Bool && type != Type.Unknown) {
                    diagnostics.error("SEM_CONDITION", condition.span, "Условие if должно иметь тип Bool", "if condition must have type Bool")
                }
            }
            is LetCondition -> {
                val type = checkerHere.checkExpression(condition.value)
                val inner = (type as? Type.Option)?.inner ?: Type.Unknown
                if (type !is Type.Option) {
                    diagnostics.error("SEM_IF_LET", condition.span, "if let требует Option<T>, получено ${type.render()}", "if let requires Option<T>, got ${type.render()}")
                }
                val symbol = Symbol.Local(condition.name, inner, condition.span)
                model.record(condition, symbol)
            }
        }
        val thenParent = parent.child()
        if (stmt.condition is LetCondition) {
            model.localSymbol(stmt.condition)?.let { thenParent.declare(it, diagnostics) }
        }
        checkBlockInto(stmt.thenBranch, checker, thenParent, enums)
        when (val elseBranch = stmt.elseBranch) {
            is ElseBlock -> checkBlock(elseBranch.block, checker, parent, enums)
            is ElseIfBranch -> {
                val nested = IfStmt(elseBranch.condition, elseBranch.block, null, elseBranch.span)
                checkIf(nested, checker, parent, enums)
            }
            null -> Unit
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checker(
        scope: Scope,
        behavior: BehaviorDecl,
        periodSeconds: BigDecimal?,
        every: Boolean?,
        enums: Map<String, Type.Enum>,
    ): TypeChecker {
        val checker = TypeChecker(
            environment = options.environment,
            symbols = symbols,
            model = model,
            diagnostics = diagnostics,
            behaviorName = behavior.name,
            targetKind = behavior.targetType,
            rulePeriodSeconds = periodSeconds,
            deltaTimeSeconds = deltaTimeSeconds,
            everyRule = every == true,
            ruleScope = scope,
        )
        // event registry is private implementation state of TypeChecker; seed it for record checking.
        events.values.forEach(checker::registerEvent)
        return checker
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checkerFor(scope: Scope, base: TypeChecker, enums: Map<String, Type.Enum>): TypeChecker {
        // TypeChecker carries rule context but is inexpensive; create a scope-aware facade.
        return TypeChecker(
            environment = options.environment,
            symbols = symbols,
            model = model,
            diagnostics = diagnostics,
            behaviorName = checkerBehaviorName(base),
            targetKind = checkerTargetKind(base),
            rulePeriodSeconds = checkerPeriod(base),
            deltaTimeSeconds = deltaTimeSeconds,
            everyRule = checkerEvery(base),
            ruleScope = scope,
        ).also { checker -> events.values.forEach(checker::registerEvent) }
    }

    private fun checkerBehaviorName(checker: TypeChecker): String = checker.behaviorName
    private fun checkerTargetKind(checker: TypeChecker): String = checker.targetKind
    private fun checkerPeriod(checker: TypeChecker): BigDecimal? = checker.rulePeriodSeconds
    private fun checkerEvery(checker: TypeChecker): Boolean = checker.everyRule

    private fun ruleBody(rule: RuleDecl): Block = when (rule) {
        is OnRule -> rule.body
        is EveryRule -> rule.body
    }

    private fun containsIntrinsic(block: Block, name: String): Boolean =
        block.statements.any { stmtContains(it, name) }

    private fun stmtContains(stmt: Stmt, name: String): Boolean = when (stmt) {
        is LetStmt -> exprContains(stmt.value, name)
        is AssignStmt -> exprContains(stmt.value, name)
        is SendStmt -> exprContains(stmt.target, name) || stmt.fields.any { exprContains(it.value, name) }
        is ExprStmt -> exprContains(stmt.expression, name)
        is IfStmt -> conditionContains(stmt.condition, name) || containsIntrinsic(stmt.thenBranch, name) || (stmt.elseBranch?.let {
            when (it) {
                is ElseBlock -> containsIntrinsic(it.block, name)
                is ElseIfBranch -> conditionContains(it.condition, name) || containsIntrinsic(it.block, name)
            }
        } ?: false)
    }

    private fun conditionContains(condition: Condition, name: String): Boolean = when (condition) {
        is ExprCondition -> exprContains(condition.expression, name)
        is LetCondition -> exprContains(condition.value, name)
    }

    private fun exprContains(expr: Expr, name: String): Boolean = when (expr) {
        is CallExpr -> intrinsicPath(expr.callee) == name || expr.arguments.any { exprContains(it, name) }
        is MemberExpr -> exprContains(expr.receiver, name)
        is IndexExpr -> exprContains(expr.receiver, name) || exprContains(expr.index, name)
        is UnaryExpr -> exprContains(expr.operand, name)
        is BinaryExpr -> exprContains(expr.left, name) || exprContains(expr.right, name)
        is ParenExpr -> exprContains(expr.expression, name)
        is RecordExpr -> expr.fields.any { exprContains(it.value, name) }
        else -> false
    }

    private fun intrinsicPath(expr: Expr): String? = when (expr) {
        is NameExpr -> expr.name
        is MemberExpr -> intrinsicPath(expr.receiver)?.let { "$it.${expr.member}" }
        else -> null
    }

    private fun resolveType(type: TypeRef, behaviorName: String, enums: Map<String, Type.Enum>, span: SourceSpan): Type {
        return try {
            resolveTypeRef(type, behaviorName, enums)
        } catch (e: IllegalArgumentException) {
            diagnostics.error("SEM_BAD_TYPE", span, "Некорректный тип '${type.name}'", "Invalid type '${type.name}'")
            Type.Unknown
        }
    }

    private fun durationValue(literal: UnitLiteral): BigDecimal = try {
        val unit = parseUnitType(literal.unit)
        if (unit != Type.Duration) {
            diagnostics.error("SEM_BAD_DURATION", literal.span, "Ожидалась единица времени, получено '${literal.unit}'", "Expected a duration unit, got '${literal.unit}'")
            BigDecimal.ZERO
        } else {
            BigDecimal(literal.rawNumber).multiply(canonicalMultiplier(literal.unit) ?: BigDecimal.ONE)
        }
    } catch (_: NumberFormatException) {
        diagnostics.error("SEM_BAD_NUMBER", literal.span, "Некорректное числовое значение '${literal.rawNumber}'", "Invalid numeric value '${literal.rawNumber}'")
        BigDecimal.ZERO
    }

    private fun parseDuration(text: String): BigDecimal {
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|min|h)$").matchEntire(text)
            ?: error("deltaTime must be a duration such as 1s or 100ms")
        val number = BigDecimal(match.groupValues[1])
        return number.multiply(canonicalMultiplier(match.groupValues[2]) ?: BigDecimal.ONE)
    }
}

