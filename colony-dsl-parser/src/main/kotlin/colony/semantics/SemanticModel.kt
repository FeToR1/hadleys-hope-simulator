package colony.semantics

import colony.ast.CallExpr
import colony.ast.LetStmt
import colony.ast.LetCondition
import colony.ast.Expr
import colony.ast.LValue
import colony.ast.NameExpr
import colony.ast.RuleDecl
import java.util.IdentityHashMap

class SemanticModel {
    private val expressionTypes = IdentityHashMap<Expr, Type>()
    private val names = IdentityHashMap<NameExpr, Symbol>()
    private val lvalues = IdentityHashMap<LValue, Symbol>()
    private val intrinsics = IdentityHashMap<CallExpr, IntrinsicContract>()
    private val rules = IdentityHashMap<RuleDecl, RuleContext>()
    private val letSymbols = IdentityHashMap<LetStmt, Symbol.Local>()
    private val letConditionSymbols = IdentityHashMap<LetCondition, Symbol.Local>()

    fun typeOf(expr: Expr): Type = expressionTypes[expr] ?: error("No semantic type for expression")
    fun resolved(name: NameExpr): Symbol? = names[name]
    fun resolved(lvalue: LValue): Symbol? = lvalues[lvalue]
    fun intrinsic(call: CallExpr): IntrinsicContract? = intrinsics[call]
    fun ruleContext(rule: RuleDecl): RuleContext? = rules[rule]
    fun localSymbol(stmt: LetStmt): Symbol.Local? = letSymbols[stmt]
    fun localSymbol(condition: LetCondition): Symbol.Local? = letConditionSymbols[condition]

    internal fun record(expr: Expr, type: Type) { expressionTypes[expr] = type }
    internal fun record(name: NameExpr, symbol: Symbol) { names[name] = symbol }
    internal fun record(lvalue: LValue, symbol: Symbol) { lvalues[lvalue] = symbol }
    internal fun record(call: CallExpr, intrinsic: IntrinsicContract) { intrinsics[call] = intrinsic }
    internal fun record(rule: RuleDecl, context: RuleContext) { rules[rule] = context }
    internal fun record(stmt: LetStmt, symbol: Symbol.Local) { letSymbols[stmt] = symbol }
    internal fun record(condition: LetCondition, symbol: Symbol.Local) { letConditionSymbols[condition] = symbol }
}

data class RuleContext(
    val behaviorName: String,
    val targetKind: String,
    val ruleName: String,
    val periodSeconds: java.math.BigDecimal?,
    val periodTicks: Long?,
    val isTimer: Boolean,
    val hasHazard: Boolean,
)

data class SemanticResult(
    val model: SemanticModel,
    val diagnostics: List<SemanticDiagnostic>,
) {
    val isValid: Boolean get() = diagnostics.none { it.severity == DiagnosticSeverity.ERROR }

    fun requireValid(): SemanticModel {
        if (!isValid) throw SemanticException(diagnostics)
        return model
    }
}
