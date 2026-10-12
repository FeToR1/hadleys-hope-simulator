package colony.semantics

import colony.ast.*
import java.math.BigDecimal

/** Settings reuse the source type checker; evaluation admits only pure constant expressions. */
class ConstantExpressions {
    private val symbols = SymbolTable()
    private val values = linkedMapOf<String, TypedConstant>()

    fun declare(statement: LetStmt) {
        require(statement.name !in values) { "Duplicate constant ${statement.name}" }
        var value = evaluate(statement.value)
        statement.declaredType?.let {
            val expected = resolveTypeRef(it, "Settings", emptyMap())
            require(expected != Type.Unknown && expected.isAssignableFrom(value.type)) {
                "${statement.name}: expected ${expected.render()}, got ${value.type.render()}"
            }
            value = when {
                expected == Type.Real64 && value.type == Type.Int64 ->
                    TypedConstant(expected, ConstantValue.Real64((value.value as ConstantValue.Int64).value.toDouble()))
                else -> value.copy(type = expected)
            }
        }
        val sink = DiagnosticSink()
        symbols.global.declare(Symbol.Local(statement.name, value.type, statement.span), sink)
        values[statement.name] = value
    }

    fun evaluate(expression: Expr): TypedConstant {
        fun requireConstant(expr: Expr) {
            when (expr) {
                is CallExpr, is MemberExpr, is IndexExpr, is RecordExpr ->
                    error("${expr.span.startLine}:${expr.span.startColumn}: settings expressions must be constant; calls and observations are unavailable")
                is BinaryExpr -> { requireConstant(expr.left); requireConstant(expr.right) }
                is UnaryExpr -> requireConstant(expr.operand)
                is ParenExpr -> requireConstant(expr.expression)
                else -> Unit
            }
        }
        requireConstant(expression)
        val sink = DiagnosticSink()
        val model = SemanticModel()
        TypeChecker(SemanticEnvironment(), symbols, model, sink, "Settings", "House", null,
            BigDecimal.ONE, false, symbols.global).checkExpression(expression)
        if (!sink.isEmpty()) throw SemanticException(sink.items)
        fun numeric(value: TypedConstant): BigDecimal = when (val v = value.value) {
            is ConstantValue.Int64 -> BigDecimal.valueOf(v.value)
            is ConstantValue.Real64 -> BigDecimal(v.value)
            is ConstantValue.Decimal -> BigDecimal(v.value.toDouble())
            else -> error("Expected a numeric constant")
        }
        fun real(value: TypedConstant): Double = when (val v = value.value) {
            is ConstantValue.Int64 -> v.value.toDouble()
            is ConstantValue.Real64 -> v.value
            is ConstantValue.Decimal -> v.value.toDouble()
            else -> error("Expected a numeric constant")
        }
        fun number(type: Type, value: BigDecimal): TypedConstant = TypedConstant(type, when (type) {
            Type.Int64, Type.Money -> ConstantValue.Int64(value.longValueExact())
            else -> ConstantValue.Real64(value.toDouble().also { require(it.isFinite()) { "Nonfinite constant" } })
        })
        fun eval(expr: Expr): TypedConstant {
            LiteralEvaluator.evaluate(expr)?.let { return it }
            return when (expr) {
                is NameExpr -> values[expr.name] ?: error("Only settings constants may be read: ${expr.name}")
                is ParenExpr -> eval(expr.expression)
                is UnaryExpr -> {
                    val operand = eval(expr.operand)
                    when (expr.operator) {
                        UnaryOperator.NOT -> TypedConstant(Type.Bool, ConstantValue.Bool(!(operand.value as ConstantValue.Bool).value))
                        UnaryOperator.PLUS -> operand
                        UnaryOperator.MINUS -> if (operand.type in setOf(Type.Int64, Type.Money))
                            number(model.typeOf(expr), numeric(operand).negate())
                            else TypedConstant(model.typeOf(expr), ConstantValue.Real64(-real(operand)))
                    }
                }
                is BinaryExpr -> {
                    val left = eval(expr.left)
                    fun bool(value: TypedConstant) = (value.value as ConstantValue.Bool).value
                    when (expr.operator) {
                        BinaryOperator.AND -> TypedConstant(Type.Bool, ConstantValue.Bool(bool(left) && bool(eval(expr.right))))
                        BinaryOperator.OR -> TypedConstant(Type.Bool, ConstantValue.Bool(bool(left) || bool(eval(expr.right))))
                        else -> {
                            val right = eval(expr.right)
                            if (expr.operator in setOf(BinaryOperator.EQ, BinaryOperator.NEQ)) {
                                val same = if (left.type.isNumericScalar() || left.type.isPhysical() || left.type == Type.Duration)
                                    numeric(left).compareTo(numeric(right)) == 0 else left.value == right.value
                                TypedConstant(Type.Bool, ConstantValue.Bool(if (expr.operator == BinaryOperator.EQ) same else !same))
                            } else {
                                val a = numeric(left); val b = numeric(right)
                                when (expr.operator) {
                                    BinaryOperator.LT, BinaryOperator.LE, BinaryOperator.GT, BinaryOperator.GE ->
                                        TypedConstant(Type.Bool, ConstantValue.Bool(when (expr.operator) {
                                            BinaryOperator.LT -> a < b; BinaryOperator.LE -> a <= b
                                            BinaryOperator.GT -> a > b; else -> a >= b
                                        }))
                                    else -> {
                                        val type = model.typeOf(expr)
                                        if (type == Type.Int64 || type == Type.Money) number(type, when (expr.operator) {
                                            BinaryOperator.ADD -> a + b; BinaryOperator.SUB -> a - b
                                            BinaryOperator.MUL -> a * b; BinaryOperator.MOD -> a.remainder(b)
                                            else -> error("Unsupported integer operator")
                                        }) else {
                                            // CVM2 arithmetic uses IEEE-754, including rounding after every operation.
                                            val x = real(left); val y = real(right)
                                            val result = when (expr.operator) {
                                                BinaryOperator.ADD -> x + y; BinaryOperator.SUB -> x - y
                                                BinaryOperator.MUL -> x * y; BinaryOperator.DIV -> x / y
                                                else -> error("Unsupported real operator")
                                            }
                                            require(result.isFinite()) { "Nonfinite constant" }
                                            TypedConstant(type, ConstantValue.Real64(result))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> error("${expr.span.startLine}:${expr.span.startColumn}: settings expressions must be constant; calls and observations are unavailable")
            }
        }
        return eval(expression)
    }
}
