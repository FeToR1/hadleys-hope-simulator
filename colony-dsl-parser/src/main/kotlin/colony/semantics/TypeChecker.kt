package colony.semantics

import colony.ast.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class TypeChecker internal constructor(
    private val environment: SemanticEnvironment,
    private val symbols: SymbolTable,
    private val model: SemanticModel,
    private val diagnostics: DiagnosticSink,
    internal val behaviorName: String,
    internal val targetKind: String,
    internal val rulePeriodSeconds: BigDecimal?,
    internal val deltaTimeSeconds: BigDecimal,
    internal val everyRule: Boolean,
    internal val ruleScope: Scope,
) {
    fun checkExpression(expr: Expr): Type = when (expr) {
        is BoolLiteral -> Type.Bool.also { model.record(expr, it) }
        is NoneLiteral -> Type.Option(Type.Unknown).also { model.record(expr, it) }
        is StringLiteral -> Type.String.also { model.record(expr, it) }
        is NumberLiteral -> checkNumberLiteral(expr)
        is NameExpr -> checkName(expr)
        is ParenExpr -> checkExpression(expr.expression).also { model.record(expr, it) }
        is UnaryExpr -> checkUnary(expr)
        is BinaryExpr -> checkBinary(expr)
        is MemberExpr -> checkMember(expr)
        is CallExpr -> checkCall(expr)
        is IndexExpr -> checkIndex(expr)
        is RecordExpr -> checkRecord(expr)
    }

    private fun checkNumberLiteral(expr: NumberLiteral): Type {
        val type = if (expr.unit == null) {
            if (expr.rawNumber.contains('.') || expr.rawNumber.contains('e', ignoreCase = true)) Type.Real64 else Type.Int64
        } else {
            parseUnitType(expr.unit) ?: run {
                diagnostics.error(
                    "SEM_UNKNOWN_UNIT", expr.span,
                    "Неизвестная единица '${expr.unit}'",
                    "Unknown unit '${expr.unit}'",
                )
                Type.Unknown
            }
        }
        model.record(expr, type)
        checkNumberRange(expr, type)
        if (type == Type.Rate) {
            // Rate literal must be finite and non-negative; positivity is a semantic value check.
            val value = BigDecimal(expr.rawNumber)
            if (value < BigDecimal.ZERO) {
                diagnostics.error("SEM_NEGATIVE_RATE", expr.span, "Rate не может быть отрицательным", "Rate cannot be negative")
            }
        }
        return type
    }

    /** Literals must fit the runtime representation: Int64 exactly, everything else as a finite double. */
    private fun checkNumberRange(expr: NumberLiteral, type: Type) {
        if (type == Type.Unknown) return
        val value = runCatching { BigDecimal(expr.rawNumber) }.getOrNull() ?: return
        if (type == Type.Int64) {
            if (value > BigDecimal(Long.MAX_VALUE)) {
                diagnostics.error("SEM_INT_RANGE", expr.span, "Литерал ${expr.rawNumber} не помещается в Int64", "Literal ${expr.rawNumber} does not fit in Int64")
            }
            return
        }
        val canonical = value.multiply(expr.unit?.let(::canonicalMultiplier) ?: BigDecimal.ONE)
        if (!canonical.toDouble().isFinite()) {
            diagnostics.error("SEM_NUMBER_RANGE", expr.span, "Литерал ${expr.rawNumber} выходит за пределы Real64", "Literal ${expr.rawNumber} is outside the Real64 range")
        }
    }

    private fun checkName(expr: NameExpr, viewMember: Boolean = false): Type {
        val symbol = ruleScope.lookup(expr.name)
        if (symbol == null) {
            diagnostics.error(
                "SEM_UNDECLARED",
                expr.span,
                "Необъявленное имя '${expr.name}'",
                "Undeclared name '${expr.name}'",
            )
            model.record(expr, Type.Unknown)
            return Type.Unknown
        }
        if (!viewMember && symbol.type is Type.View) {
            diagnostics.error("SEM_VIEW_VALUE", expr.span, "view можно использовать только как view.поле", "view can only be used as view.field")
        }
        model.record(expr, symbol)
        val valueType = (symbol.type as? Type.EnumValue)?.enumType ?: symbol.type
        model.record(expr, valueType)
        return valueType
    }

    private fun checkUnary(expr: UnaryExpr): Type {
        val operand = checkExpression(expr.operand)
        val result = when (expr.operator) {
            UnaryOperator.NOT -> if (operand == Type.Bool || operand == Type.Unknown) Type.Bool else typeError(expr, "! requires Bool", "! требует Bool")
            UnaryOperator.PLUS, UnaryOperator.MINUS ->
                if (operand == Type.Unknown) Type.Unknown
                else if (operand.isNumericScalar() || operand.isPhysical() || operand == Type.Duration || operand == Type.Money)
                    operand
                else typeError(expr, "Unary sign is not defined for ${operand.render()}", "Унарный знак не определён для ${operand.render()}")
        }
        model.record(expr, result)
        return result
    }

    private fun checkBinary(expr: BinaryExpr): Type {
        val left = checkExpression(expr.left)
        val right = checkExpression(expr.right)
        val result = if (left == Type.Unknown || right == Type.Unknown) {
            // The operand was already diagnosed; keep the result type useful without cascading errors.
            when (expr.operator) {
                BinaryOperator.OR, BinaryOperator.AND, BinaryOperator.EQ, BinaryOperator.NEQ,
                BinaryOperator.LT, BinaryOperator.LE, BinaryOperator.GT, BinaryOperator.GE -> Type.Bool
                else -> Type.Unknown
            }
        } else when (expr.operator) {
            BinaryOperator.OR, BinaryOperator.AND ->
                if (left == Type.Bool && right == Type.Bool) Type.Bool else typeError(expr, "Logical operator requires Bool operands", "Логический оператор требует Bool")
            BinaryOperator.EQ, BinaryOperator.NEQ ->
                if (isEqualityComparable(left, right)) Type.Bool else typeError(expr, "Cannot compare ${left.render()} and ${right.render()}", "Нельзя сравнить ${left.render()} и ${right.render()}")
            BinaryOperator.LT, BinaryOperator.LE, BinaryOperator.GT, BinaryOperator.GE ->
                if (isOrderComparable(left, right)) Type.Bool else typeError(expr, "Cannot order ${left.render()} and ${right.render()}", "Нельзя сравнивать порядком ${left.render()} и ${right.render()}")
            BinaryOperator.ADD -> addition(left, right, expr)
            BinaryOperator.SUB -> subtraction(left, right, expr)
            BinaryOperator.MUL -> multiply(left, right, expr)
            BinaryOperator.DIV -> divide(left, right, expr)
            BinaryOperator.MOD ->
                if (left == Type.Int64 && right == Type.Int64) Type.Int64 else typeError(expr, "% requires Int64 operands", "% требует Int64")
        }
        model.record(expr, result)
        return result
    }

    private fun addition(left: Type, right: Type, expr: Expr): Type = when {
        left == Type.Int64 && right == Type.Int64 -> Type.Int64
        left.isNumericScalar() && right.isNumericScalar() -> Type.Real64
        left == Type.Real64 && right == Type.Int64 -> Type.Real64
        left == Type.Int64 && right == Type.Real64 -> Type.Real64
        left == Type.Physical(PhysicalKind.TEMPERATURE) && right == Type.Physical(PhysicalKind.TEMPERATURE_DELTA) -> Type.Physical(PhysicalKind.TEMPERATURE)
        left == Type.Physical(PhysicalKind.TEMPERATURE_DELTA) && right == Type.Physical(PhysicalKind.TEMPERATURE) -> Type.Physical(PhysicalKind.TEMPERATURE)
        left is Type.Physical && right is Type.Physical && left.kind == right.kind && left.kind != PhysicalKind.TEMPERATURE -> left
        left is Type.Physical || right is Type.Physical -> typeError(expr, "Incompatible physical quantities: ${left.render()} + ${right.render()}", "Несовместимые физические величины: ${left.render()} + ${right.render()}")
        left == Type.Duration && right == Type.Duration -> Type.Duration
        left == Type.Money && right == Type.Money -> Type.Money
        else -> typeError(expr, "Operator + is not defined for ${left.render()} and ${right.render()}", "Оператор + не определён для ${left.render()} и ${right.render()}")
    }

    private fun subtraction(left: Type, right: Type, expr: Expr): Type = when {
        left == Type.Int64 && right == Type.Int64 -> Type.Int64
        left.isNumericScalar() && right.isNumericScalar() -> Type.Real64
        left == Type.Physical(PhysicalKind.TEMPERATURE) && right == Type.Physical(PhysicalKind.TEMPERATURE) -> Type.Physical(PhysicalKind.TEMPERATURE_DELTA)
        left == Type.Physical(PhysicalKind.TEMPERATURE) && right == Type.Physical(PhysicalKind.TEMPERATURE_DELTA) -> Type.Physical(PhysicalKind.TEMPERATURE)
        left is Type.Physical && right is Type.Physical && left.kind == right.kind && left.kind != PhysicalKind.TEMPERATURE -> left
        left is Type.Physical || right is Type.Physical -> typeError(expr, "Incompatible physical quantities: ${left.render()} - ${right.render()}", "Несовместимые физические величины: ${left.render()} - ${right.render()}")
        left == Type.Duration && right == Type.Duration -> Type.Duration
        left == Type.Money && right == Type.Money -> Type.Money
        else -> typeError(expr, "Operator - is not defined for ${left.render()} and ${right.render()}", "Оператор - не определён для ${left.render()} и ${right.render()}")
    }

    private fun multiply(left: Type, right: Type, expr: Expr): Type = when {
        left.isNumericScalar() && right.isNumericScalar() -> if (left == Type.Real64 || right == Type.Real64) Type.Real64 else Type.Int64
        left.isPhysical() && right.isNumericScalar() -> left
        left.isNumericScalar() && right.isPhysical() -> right
        left == Type.Duration && right.isNumericScalar() -> Type.Duration
        left.isNumericScalar() && right == Type.Duration -> Type.Duration
        else -> typeError(expr, "Multiplication of ${left.render()} and ${right.render()} is not supported", "Умножение ${left.render()} и ${right.render()} не поддерживается")
    }

    private fun divide(left: Type, right: Type, expr: Expr): Type = when {
        left.isNumericScalar() && right.isNumericScalar() -> Type.Real64
        left.isPhysical() && right.isNumericScalar() -> left
        left == Type.Duration && right.isNumericScalar() -> Type.Duration
        left.isPhysical() && right.isPhysical() && left.sameNominal(right) -> Type.Real64
        left == Type.Duration && right == Type.Duration -> Type.Real64
        else -> typeError(expr, "Division of ${left.render()} and ${right.render()} is not supported", "Деление ${left.render()} на ${right.render()} не поддерживается")
    }

    private fun checkMember(expr: MemberExpr): Type {
        val receiver = expr.receiver
        val receiverType = if (receiver is NameExpr) checkName(receiver, viewMember = true) else checkExpression(receiver)
        val result = when {
            receiverType == Type.Unknown -> Type.Unknown
            receiverType is Type.View -> {
                val fieldType = environment.kindContract(receiverType.kind)?.viewFields?.get(expr.member)
                if (fieldType == null) {
                    diagnostics.error("SEM_UNKNOWN_VIEW", expr.span, "Наблюдение '${expr.member}' недоступно у view<${receiverType.kind}>", "Observation '${expr.member}' is not defined for view<${receiverType.kind}>")
                    Type.Unknown
                } else fieldType
            }
            receiverType is Type.Event -> receiverType.fields[expr.member] ?: run {
                diagnostics.error("SEM_UNKNOWN_EVENT_FIELD", expr.span, "У события '${receiverType.name}' нет поля '${expr.member}'", "Event '${receiverType.name}' has no field '${expr.member}'")
                Type.Unknown
            }
            receiverType is Type.Ref && expr.member == "id" -> Type.String
            receiverType == Type.IntrinsicNamespace -> Type.IntrinsicNamespace
            receiverType is Type.Kind -> environment.recordFields(receiverType.name)?.get(expr.member) ?: run {
                diagnostics.error("SEM_NO_MEMBER", expr.span, "Неизвестное поле '${expr.member}' у ${receiverType.name}", "Unknown field '${expr.member}' on ${receiverType.name}")
                Type.Unknown
            }
            else -> {
                diagnostics.error("SEM_NO_MEMBER", expr.span, "Тип ${receiverType.render()} не имеет члена '${expr.member}'", "Type ${receiverType.render()} has no member '${expr.member}'")
                Type.Unknown
            }
        }
        model.record(expr, result)
        return result
    }

    private fun checkCall(expr: CallExpr): Type {
        val path = intrinsicPath(expr.callee)
        if (path == null) {
            diagnostics.error("SEM_CALL_TARGET", expr.span, "Вызываемый объект должен быть известной встроенной функцией Colony", "Call target must be a known Colony intrinsic")
            model.record(expr, Type.Unknown)
            return Type.Unknown
        }
        val intrinsic = Intrinsics.resolve(path)
        if (intrinsic == null) {
            diagnostics.error("SEM_UNKNOWN_INTRINSIC", expr.span, "Неизвестная встроенная функция '$path'", "Unknown intrinsic '$path'")
            model.record(expr, Type.Unknown)
            return Type.Unknown
        }
        val argTypes = expr.arguments.map(::checkExpression)
        if (intrinsic.effect == EffectClass.PURE && expr.arguments.any(::containsExternalEffect)) {
            diagnostics.error(
                "SEM_PURE_EFFECT", expr.span,
                "Чистая функция '$path' не может содержать побочный эффект",
                "Pure function '$path' cannot contain a side effect",
            )
        }
        var normalizedArgTypes = argTypes
        if (path == "chance" &&
            (argTypes.getOrNull(0) == Type.Int64 || argTypes.getOrNull(0) == Type.Real64) &&
            isConstantProbability(expr.arguments.firstOrNull())
        ) {
            normalizedArgTypes = argTypes.toMutableList().also { it[0] = Type.Probability }
        }
        if (normalizedArgTypes.none { it == Type.Unknown }) {
            intrinsic.argumentCheck(normalizedArgTypes)?.let { message ->
                diagnostics.error("SEM_BAD_INTRINSIC_ARGS", expr.span, message, message)
            }
        }
        if (intrinsic.requiredCapability != null) {
            val kind = environment.kindContract(targetKind)
            if (kind == null || intrinsic.requiredCapability !in kind.capabilities) {
                diagnostics.error(
                    "SEM_CAPABILITY",
                    expr.span,
                    "Вид '$targetKind' не имеет capability ${intrinsic.requiredCapability}",
                    "Entity kind '$targetKind' does not have capability ${intrinsic.requiredCapability}",
                )
            }
        }
        if (path == "hazard") {
            if (!everyRule || rulePeriodSeconds == null || rulePeriodSeconds.compareTo(deltaTimeSeconds) != 0) {
                diagnostics.error(
                    "SEM_HAZARD_PERIOD",
                    expr.span,
                    "hazard(...) разрешён только в обработчике every с периодом ровно Δt=${deltaTimeSeconds.stripTrailingZeros().toPlainString()}s",
                    "hazard(...) is allowed only in an every-handler with period exactly Δt=${deltaTimeSeconds.stripTrailingZeros().toPlainString()}s",
                )
            }
        }
        if (path == "chance") validateChanceLiteral(expr)
        val result = intrinsic.resultType(normalizedArgTypes)
        if (result is Type.Option && result.inner is Type.Option) {
            diagnostics.error("SEM_NESTED_OPTION", expr.span, "Option<Option<T>> не поддерживается", "Option<Option<T>> is not supported")
        }
        model.record(expr, intrinsic)
        model.record(expr, result)
        return result
    }

    private fun validateChanceLiteral(expr: CallExpr) {
        val probability = expr.arguments.firstOrNull() ?: return
        if (probability is NumberLiteral && probability.unit == null) {
            val value = runCatching { BigDecimal(probability.rawNumber) }.getOrNull() ?: return
            if (value < BigDecimal.ZERO || value > BigDecimal.ONE) {
                diagnostics.error(
                    "SEM_PROBABILITY_RANGE",
                    probability.span,
                    "Вероятность должна быть в диапазоне [0,1], получено ${probability.rawNumber}",
                    "Probability must be in [0,1], got ${probability.rawNumber}",
                )
            }
        }
    }

    private fun isConstantProbability(expr: Expr?): Boolean = expr is NumberLiteral && expr.unit == null

    private fun checkIndex(expr: IndexExpr): Type {
        val receiver = checkExpression(expr.receiver)
        val index = checkExpression(expr.index)
        if (receiver == Type.Unknown || index == Type.Unknown) {
            model.record(expr, (receiver as? Type.List)?.element ?: Type.Unknown)
            return (receiver as? Type.List)?.element ?: Type.Unknown
        }
        if (receiver !is Type.List || index != Type.Int64) {
            diagnostics.error("SEM_INDEX", expr.span, "Индексирование требует List<T> и Int64", "Indexing requires List<T> and Int64")
            model.record(expr, Type.Unknown)
            return Type.Unknown
        }
        model.record(expr, receiver.element)
        return receiver.element
    }

    private fun checkRecord(expr: RecordExpr): Type {
        val eventType = resolveEvent(expr.typeName)
        if (eventType == null) {
            diagnostics.error("SEM_UNKNOWN_RECORD", expr.span, "Неизвестная запись/событие '${expr.typeName}'", "Unknown record/event '${expr.typeName}'")
            model.record(expr, Type.Unknown)
            expr.fields.forEach { checkExpression(it.value) }
            return Type.Unknown
        }
        validateRecordFields(eventType, expr.fields, expr.span)
        model.record(expr, eventType)
        return eventType
    }

    private fun validateRecordFields(expected: Type.Event, fields: List<RecordFieldInit>, span: SourceSpan) {
        fields.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            diagnostics.error("SEM_DUPLICATE_FIELD", duplicates.last().span, "Поле '$name' передано повторно", "Duplicate field '$name'")
        }
        val provided = fields.map { it.name }.toSet()
        for (field in expected.fields) {
            if (field.key !in provided) {
                diagnostics.error("SEM_MISSING_FIELD", span, "Для '${expected.name}' отсутствует поле '${field.key}'", "Missing field '${field.key}' in '${expected.name}'")
            }
        }
        for (field in fields) {
            val expectedType = expected.fields[field.name]
            if (expectedType == null) {
                diagnostics.error("SEM_UNKNOWN_FIELD", field.span, "Неизвестное поле '${field.name}' для '${expected.name}'", "Unknown field '${field.name}' for '${expected.name}'")
                checkExpression(field.value)
            } else {
                val actual = checkExpression(field.value)
                requireAssignable(expectedType, actual, field.value.span, "${expected.name}.${field.name}")
            }
        }
    }

    private fun resolveEvent(name: String): Type.Event? = eventRegistry[name]

    private val eventRegistry: MutableMap<String, Type.Event> = linkedMapOf()

    fun registerEvent(event: Type.Event) { eventRegistry[event.name] = event }

    fun checkRecordAgainst(event: Type.Event, fields: List<RecordFieldInit>) = validateRecordFields(event, fields, fields.firstOrNull()?.span ?: SourceSpan.synthetic())

    fun checkSend(stmt: SendStmt, event: Type.Event) {
        val targetType = checkExpression(stmt.target)
        if (targetType !is Type.Ref && targetType != Type.Unknown) {
            diagnostics.error("SEM_SEND_TARGET", stmt.target.span, "Адресат send должен иметь тип Ref<Kind>", "send target must have type Ref<Kind>")
        }
        checkRecordAgainst(event, stmt.fields)
    }

    fun checkAssignment(stmt: AssignStmt) {
        val actual = checkExpression(stmt.value)
        if (stmt.target.parts.isEmpty()) return
        val root = ruleScope.lookup(stmt.target.parts.first())
        if (root == null) {
            diagnostics.error("SEM_UNDECLARED", stmt.target.span, "Необъявленное имя '${stmt.target.parts.first()}'", "Undeclared name '${stmt.target.parts.first()}'")
            return
        }
        if (stmt.target.parts.size > 1) {
            diagnostics.error(
                "SEM_FOREIGN_STATE",
                stmt.target.span,
                "Нельзя изменять состояние другой сущности через Ref: '${stmt.target.parts.joinToString(".")}'",
                "Cannot mutate foreign entity state through Ref: '${stmt.target.parts.joinToString(".")}'",
            )
            return
        }
        model.record(stmt.target, root)
        when (root) {
            is Symbol.State, is Symbol.Local -> requireAssignable(root.type, actual, stmt.value.span, root.name)
            is Symbol.Param, is Symbol.Message, is Symbol.Implicit, is Symbol.EnumValueSymbol -> diagnostics.error(
                "SEM_ASSIGN_READONLY",
                stmt.target.span,
                "Нельзя присваивать '${root.name}': этот символ только для чтения",
                "Cannot assign '${root.name}': this symbol is read-only",
            )
        }
    }

    private fun requireAssignable(expected: Type, actual: Type, span: SourceSpan, target: String) {
        if (expected.isAssignableFrom(actual)) return
        diagnostics.error(
            "SEM_TYPE_MISMATCH",
            span,
            "Нельзя присвоить ${actual.render()} в '$target' типа ${expected.render()}",
            "Cannot assign ${actual.render()} to '$target' of type ${expected.render()}",
        )
    }

    private fun isEqualityComparable(left: Type, right: Type): Boolean =
        left.isAssignableFrom(right) || right.isAssignableFrom(left) || (left is Type.Option && right is Type.Option && left.inner.isAssignableFrom(right.inner))

    private fun isOrderComparable(left: Type, right: Type): Boolean =
        (left.isNumericScalar() && right.isNumericScalar()) ||
            (left is Type.Physical && right is Type.Physical && left.kind == right.kind && left.kind != PhysicalKind.TEMPERATURE_DELTA) ||
            (left == Type.Duration && right == Type.Duration)

    private fun typeError(expr: Expr, en: String, ru: String): Type {
        diagnostics.error("SEM_BAD_OPERATOR", expr.span, ru, en)
        return Type.Unknown
    }


    private fun containsExternalEffect(expr: Expr): Boolean = when (expr) {
        is CallExpr -> {
            val path = intrinsicPath(expr.callee)
            val own = path?.let { Intrinsics.resolve(it)?.effect == EffectClass.EXTERNAL } == true
            own || expr.arguments.any(::containsExternalEffect)
        }
        is MemberExpr -> containsExternalEffect(expr.receiver)
        is IndexExpr -> containsExternalEffect(expr.receiver) || containsExternalEffect(expr.index)
        is UnaryExpr -> containsExternalEffect(expr.operand)
        is BinaryExpr -> containsExternalEffect(expr.left) || containsExternalEffect(expr.right)
        is ParenExpr -> containsExternalEffect(expr.expression)
        is RecordExpr -> expr.fields.any { containsExternalEffect(it.value) }
        else -> false
    }
    private fun intrinsicPath(expr: Expr): String? = when (expr) {
        is NameExpr -> expr.name
        is MemberExpr -> intrinsicPath(expr.receiver)?.let { "$it.${expr.member}" }
        else -> null
    }
}

object LiteralEvaluator {
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)

    fun evaluate(expr: Expr): TypedConstant? = when (expr) {
        is BoolLiteral -> TypedConstant(Type.Bool, ConstantValue.Bool(expr.value))
        is NoneLiteral -> TypedConstant(Type.Option(Type.Unknown), ConstantValue.None)
        is StringLiteral -> TypedConstant(Type.String, ConstantValue.Text(expr.value))
        is NumberLiteral -> evaluateNumber(expr)
        is UnaryExpr -> null
        else -> null
    }

    private fun evaluateNumber(expr: NumberLiteral): TypedConstant {
        val raw = BigDecimal(expr.rawNumber, MC)
        return when (val type = if (expr.unit == null) {
            if (expr.rawNumber.contains('.') || expr.rawNumber.contains('e', true)) Type.Real64 else Type.Int64
        } else parseUnitType(expr.unit) ?: Type.Unknown) {
            Type.Int64 -> TypedConstant(Type.Int64, ConstantValue.Int64(raw.longValueExact()))
            Type.Real64 -> TypedConstant(Type.Real64, ConstantValue.Real64(raw.toDouble()))
            Type.Duration -> TypedConstant(Type.Duration, ConstantValue.Decimal(raw.multiply(canonicalMultiplier(expr.unit ?: "") ?: BigDecimal.ONE, MC)))
            Type.Rate -> TypedConstant(Type.Rate, ConstantValue.Decimal(raw.multiply(canonicalMultiplier(expr.unit ?: "") ?: BigDecimal.ONE, MC)))
            is Type.Physical -> TypedConstant(type, ConstantValue.Decimal(raw.multiply(canonicalMultiplier(expr.unit ?: "") ?: BigDecimal.ONE, MC)))
            else -> TypedConstant(type, ConstantValue.Decimal(raw))
        }
    }
}
