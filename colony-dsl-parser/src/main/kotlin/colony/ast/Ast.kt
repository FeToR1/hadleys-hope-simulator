package colony.ast

/** 1-based source coordinates; endColumn is exclusive. */
data class SourceSpan(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val startOffset: Int,
    val endOffsetExclusive: Int,
) {
    companion object {
        fun synthetic(): SourceSpan = SourceSpan(1, 1, 1, 1, 0, 0)
    }
}

sealed interface AstNode {
    val span: SourceSpan
}

data class Program(
    val declarations: List<TopLevelDecl>,
    override val span: SourceSpan,
) : AstNode

sealed interface TopLevelDecl : AstNode

data class EventDecl(
    val name: String,
    val fields: List<EventField>,
    override val span: SourceSpan,
) : TopLevelDecl

data class EventField(
    val name: String,
    val type: TypeRef,
    override val span: SourceSpan,
) : AstNode

data class BehaviorDecl(
    val name: String,
    val targetType: String,
    val members: List<BehaviorMember>,
    override val span: SourceSpan,
) : TopLevelDecl

sealed interface BehaviorMember : AstNode

data class EnumDecl(
    val name: String,
    val values: List<String>,
    override val span: SourceSpan,
) : BehaviorMember

data class ParamDecl(
    val name: String,
    val type: TypeRef,
    override val span: SourceSpan,
) : BehaviorMember

data class StateDecl(
    val name: String,
    val type: TypeRef,
    val initializer: Expr,
    override val span: SourceSpan,
) : BehaviorMember

sealed interface RuleDecl : BehaviorMember

data class OnRule(
    val eventType: String,
    val messageName: String,
    val ruleName: String,
    val body: Block,
    override val span: SourceSpan,
) : RuleDecl

data class EveryRule(
    val period: UnitLiteral,
    val ruleName: String,
    val body: Block,
    override val span: SourceSpan,
) : RuleDecl

data class Block(
    val statements: List<Stmt>,
    override val span: SourceSpan,
) : AstNode

sealed interface Stmt : AstNode

data class LetStmt(
    val name: String,
    val declaredType: TypeRef?,
    val value: Expr,
    override val span: SourceSpan,
) : Stmt

data class IfStmt(
    val condition: Condition,
    val thenBranch: Block,
    val elseBranch: ElseBranch?,
    override val span: SourceSpan,
) : Stmt

sealed interface Condition : AstNode

data class ExprCondition(
    val expression: Expr,
    override val span: SourceSpan,
) : Condition

data class LetCondition(
    val name: String,
    val value: Expr,
    override val span: SourceSpan,
) : Condition

sealed interface ElseBranch : AstNode

data class ElseIfBranch(
    val condition: Condition,
    val block: Block,
    override val span: SourceSpan,
) : ElseBranch

data class ElseBlock(
    val block: Block,
    override val span: SourceSpan,
) : ElseBranch

data class AssignStmt(
    val target: LValue,
    val value: Expr,
    override val span: SourceSpan,
) : Stmt

data class SendStmt(
    val eventType: String,
    val fields: List<RecordFieldInit>,
    val target: Expr,
    override val span: SourceSpan,
) : Stmt

data class ExprStmt(
    val expression: Expr,
    override val span: SourceSpan,
) : Stmt

data class LValue(
    val parts: List<String>,
    override val span: SourceSpan,
) : AstNode

sealed interface Expr : AstNode

data class BoolLiteral(val value: Boolean, override val span: SourceSpan) : Expr
data class NoneLiteral(override val span: SourceSpan) : Expr
data class StringLiteral(val value: String, override val span: SourceSpan) : Expr

data class NumberLiteral(
    val rawNumber: String,
    val unit: String?,
    override val span: SourceSpan,
) : Expr

data class NameExpr(
    val name: String,
    override val span: SourceSpan,
) : Expr

data class ParenExpr(
    val expression: Expr,
    override val span: SourceSpan,
) : Expr

data class UnaryExpr(
    val operator: UnaryOperator,
    val operand: Expr,
    override val span: SourceSpan,
) : Expr

data class BinaryExpr(
    val left: Expr,
    val operator: BinaryOperator,
    val right: Expr,
    override val span: SourceSpan,
) : Expr

data class MemberExpr(
    val receiver: Expr,
    val member: String,
    override val span: SourceSpan,
) : Expr

data class CallExpr(
    val callee: Expr,
    val arguments: List<Expr>,
    override val span: SourceSpan,
) : Expr

data class IndexExpr(
    val receiver: Expr,
    val index: Expr,
    override val span: SourceSpan,
) : Expr

data class RecordExpr(
    val typeName: String,
    val fields: List<RecordFieldInit>,
    override val span: SourceSpan,
) : Expr

data class RecordFieldInit(
    val name: String,
    val value: Expr,
    override val span: SourceSpan,
) : AstNode

data class TypeRef(
    val name: String,
    val arguments: List<TypeRef> = emptyList(),
    override val span: SourceSpan = SourceSpan.synthetic(),
) : AstNode

data class UnitLiteral(
    val rawNumber: String,
    val unit: String,
    override val span: SourceSpan,
) : AstNode

enum class UnaryOperator { NOT, PLUS, MINUS }

enum class BinaryOperator {
    OR, AND,
    EQ, NEQ,
    LT, LE, GT, GE,
    ADD, SUB,
    MUL, DIV, MOD,
}
