package colony.parser

import colony.ast.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

/** Builds the project's domain AST; ANTLR parse contexts do not escape this layer. */
class AstBuilder : ColonyBaseVisitor<AstNode>() {

    override fun visitProgram(ctx: ColonyParser.ProgramContext): Program {
        return Program(
            declarations = ctx.topLevelDecl().map { visit(it) as TopLevelDecl },
            span = span(ctx),
        )
    }

    override fun visitEventDecl(ctx: ColonyParser.EventDeclContext): EventDecl {
        return EventDecl(
            name = ctx.typeName().text,
            fields = ctx.eventField().map { visit(it) as EventField },
            span = span(ctx),
        )
    }

    override fun visitEventField(ctx: ColonyParser.EventFieldContext): EventField {
        return EventField(
            name = ctx.identifier().text,
            type = typeRef(ctx.typeRef()),
            span = span(ctx),
        )
    }

    override fun visitBehaviorDecl(ctx: ColonyParser.BehaviorDeclContext): BehaviorDecl {
        return BehaviorDecl(
            name = ctx.identifier().text,
            targetType = ctx.typeName().text,
            members = ctx.behaviorMember().map { visit(it) as BehaviorMember },
            span = span(ctx),
        )
    }

    override fun visitBehaviorMember(ctx: ColonyParser.BehaviorMemberContext): BehaviorMember {
        return when {
            ctx.enumDecl() != null -> visit(ctx.enumDecl()) as EnumDecl
            ctx.paramDecl() != null -> visit(ctx.paramDecl()) as ParamDecl
            ctx.stateDecl() != null -> visit(ctx.stateDecl()) as StateDecl
            else -> visit(ctx.ruleDecl()) as RuleDecl
        }
    }

    override fun visitEnumDecl(ctx: ColonyParser.EnumDeclContext): EnumDecl {
        return EnumDecl(
            name = ctx.identifier().text,
            values = ctx.enumValue().map { it.identifier().text },
            span = span(ctx),
        )
    }

    override fun visitParamDecl(ctx: ColonyParser.ParamDeclContext): ParamDecl {
        return ParamDecl(
            name = ctx.identifier().text,
            type = typeRef(ctx.typeRef()),
            span = span(ctx),
        )
    }

    override fun visitStateDecl(ctx: ColonyParser.StateDeclContext): StateDecl {
        return StateDecl(
            name = ctx.identifier().text,
            type = typeRef(ctx.typeRef()),
            initializer = expr(ctx.expression()),
            span = span(ctx),
        )
    }

    override fun visitOnRule(ctx: ColonyParser.OnRuleContext): OnRule {
        return OnRule(
            eventType = ctx.typeName().text,
            messageName = ctx.identifier(0).text,
            ruleName = ctx.identifier(1).text,
            body = visit(ctx.block()) as Block,
            span = span(ctx),
        )
    }

    override fun visitEveryRule(ctx: ColonyParser.EveryRuleContext): EveryRule {
        return EveryRule(
            period = unitLiteral(ctx.durationLiteral()),
            ruleName = ctx.identifier().text,
            body = visit(ctx.block()) as Block,
            span = span(ctx),
        )
    }

    override fun visitBlock(ctx: ColonyParser.BlockContext): Block {
        return Block(
            statements = ctx.statement().map { visit(it) as Stmt },
            span = span(ctx),
        )
    }

    override fun visitStatement(ctx: ColonyParser.StatementContext): Stmt {
        return when {
            ctx.letStatement() != null -> visit(ctx.letStatement()) as LetStmt
            ctx.ifStatement() != null -> visit(ctx.ifStatement()) as IfStmt
            ctx.assignmentStatement() != null -> visit(ctx.assignmentStatement()) as AssignStmt
            ctx.sendStatement() != null -> visit(ctx.sendStatement()) as SendStmt
            else -> visit(ctx.expressionStatement()) as ExprStmt
        }
    }

    override fun visitLetStatement(ctx: ColonyParser.LetStatementContext): LetStmt {
        return LetStmt(
            name = ctx.identifier().text,
            declaredType = ctx.typeRef()?.let(::typeRef),
            value = expr(ctx.expression()),
            span = span(ctx),
        )
    }

    override fun visitIfStatement(ctx: ColonyParser.IfStatementContext): IfStmt {
        return IfStmt(
            condition = condition(ctx.condition()),
            thenBranch = visit(ctx.block()) as Block,
            elseBranch = ctx.elsePart()?.let { elseBranch(it) },
            span = span(ctx),
        )
    }

    private fun condition(ctx: ColonyParser.ConditionContext): Condition {
        return if (ctx.LET() != null) {
            LetCondition(
                name = ctx.identifier().text,
                value = expr(ctx.expression()),
                span = span(ctx),
            )
        } else {
            ExprCondition(expr(ctx.expression()), span(ctx))
        }
    }

    private fun elseBranch(ctx: ColonyParser.ElsePartContext): ElseBranch {
        return if (ctx.ifStatement() != null) {
            ElseBlock(
                block = Block(listOf(visitIfStatement(ctx.ifStatement())), span(ctx)),
                span = span(ctx),
            )
        } else {
            ElseBlock(
                block = visit(ctx.block()) as Block,
                span = span(ctx),
            )
        }
    }

    override fun visitAssignmentStatement(ctx: ColonyParser.AssignmentStatementContext): AssignStmt {
        return AssignStmt(
            target = LValue(
                parts = ctx.lvalue().identifier().map { it.text },
                span = span(ctx.lvalue()),
            ),
            value = expr(ctx.expression()),
            span = span(ctx),
        )
    }


    override fun visitSendStatement(ctx: ColonyParser.SendStatementContext): SendStmt {
        return SendStmt(
            eventType = ctx.typeName().text,
            fields = ctx.recordLiteralTail().recordFieldInit().map { field ->
                RecordFieldInit(
                    name = field.identifier().text,
                    value = expr(field.expression()),
                    span = span(field),
                )
            },
            target = expr(ctx.expression()),
            span = span(ctx),
        )
    }

    override fun visitExpressionStatement(ctx: ColonyParser.ExpressionStatementContext): ExprStmt {
        return ExprStmt(expr(ctx.expression()), span(ctx))
    }

    override fun visitExpression(ctx: ColonyParser.ExpressionContext): Expr = visit(ctx.logicalOrExpression()) as Expr

    override fun visitLogicalOrExpression(ctx: ColonyParser.LogicalOrExpressionContext): Expr =
        foldBinary(ctx.logicalAndExpression().map(::expr), BinaryOperator.OR, span(ctx))

    override fun visitLogicalAndExpression(ctx: ColonyParser.LogicalAndExpressionContext): Expr =
        foldBinary(ctx.equalityExpression().map(::expr), BinaryOperator.AND, span(ctx))

    override fun visitEqualityExpression(ctx: ColonyParser.EqualityExpressionContext): Expr {
        val operands = ctx.comparisonExpression().map(::expr)
        val ops = ctx.children.orEmpty().filterIsInstance<TerminalNode>().mapNotNull {
            when (it.symbol.type) {
                ColonyParser.EQ -> BinaryOperator.EQ
                ColonyParser.NEQ -> BinaryOperator.NEQ
                else -> null
            }
        }
        return foldBinary(operands, ops, span(ctx))
    }

    override fun visitComparisonExpression(ctx: ColonyParser.ComparisonExpressionContext): Expr {
        val operands = ctx.additiveExpression().map(::expr)
        val ops = ctx.children.orEmpty().filterIsInstance<TerminalNode>().mapNotNull {
            when (it.symbol.type) {
                ColonyParser.LT -> BinaryOperator.LT
                ColonyParser.LE -> BinaryOperator.LE
                ColonyParser.GT -> BinaryOperator.GT
                ColonyParser.GE -> BinaryOperator.GE
                else -> null
            }
        }
        return foldBinary(operands, ops, span(ctx))
    }

    override fun visitAdditiveExpression(ctx: ColonyParser.AdditiveExpressionContext): Expr {
        val operands = ctx.multiplicativeExpression().map(::expr)
        val ops = ctx.children.orEmpty().filterIsInstance<TerminalNode>().mapNotNull {
            when (it.symbol.type) {
                ColonyParser.PLUS -> BinaryOperator.ADD
                ColonyParser.MINUS -> BinaryOperator.SUB
                else -> null
            }
        }
        return foldBinary(operands, ops, span(ctx))
    }

    override fun visitMultiplicativeExpression(ctx: ColonyParser.MultiplicativeExpressionContext): Expr {
        val operands = ctx.unaryExpression().map(::expr)
        val ops = ctx.children.orEmpty().filterIsInstance<TerminalNode>().mapNotNull {
            when (it.symbol.type) {
                ColonyParser.STAR -> BinaryOperator.MUL
                ColonyParser.SLASH -> BinaryOperator.DIV
                ColonyParser.PERCENT -> BinaryOperator.MOD
                else -> null
            }
        }
        return foldBinary(operands, ops, span(ctx))
    }

    override fun visitUnaryExpression(ctx: ColonyParser.UnaryExpressionContext): Expr {
        if (ctx.postfixExpression() != null) return expr(ctx.postfixExpression())
        val operand = visit(ctx.unaryExpression()) as Expr
        val op = when {
            ctx.NOT() != null -> UnaryOperator.NOT
            ctx.PLUS() != null -> UnaryOperator.PLUS
            else -> UnaryOperator.MINUS
        }
        return UnaryExpr(op, operand, span(ctx))
    }

    override fun visitPostfixExpression(ctx: ColonyParser.PostfixExpressionContext): Expr {
        var result = expr(ctx.primaryExpression())
        for (part in ctx.postfixPart()) {
            result = when {
                part.identifier() != null -> MemberExpr(result, part.identifier().text, span(part))
                part.argumentList() != null || part.LPAREN() != null -> CallExpr(
                    callee = result,
                    arguments = part.argumentList()?.expression()?.map(::expr) ?: emptyList(),
                    span = span(part),
                )
                else -> IndexExpr(
                    receiver = result,
                    index = expr(part.expression()),
                    span = span(part),
                )
            }
        }
        return result
    }

    override fun visitPrimaryExpression(ctx: ColonyParser.PrimaryExpressionContext): Expr {
        return when {
            ctx.literal() != null -> expr(ctx.literal())
            ctx.identifier() != null -> NameExpr(ctx.identifier().text, span(ctx))
            ctx.expression() != null -> ParenExpr(expr(ctx.expression()), span(ctx))
            else -> visit(ctx.recordLiteral()) as Expr
        }
    }

    override fun visitLiteral(ctx: ColonyParser.LiteralContext): Expr {
        return when {
            ctx.TRUE() != null -> BoolLiteral(true, span(ctx))
            ctx.FALSE() != null -> BoolLiteral(false, span(ctx))
            ctx.NONE() != null -> NoneLiteral(span(ctx))
            ctx.STRING() != null -> StringLiteral(unquote(ctx.STRING().text), span(ctx))
            else -> numberLiteral(ctx.numberLiteral())
        }
    }

    override fun visitRecordLiteral(ctx: ColonyParser.RecordLiteralContext): RecordExpr {
        return RecordExpr(
            typeName = ctx.typeName().text,
            fields = ctx.recordFieldInit().map { field ->
                RecordFieldInit(
                    name = field.identifier().text,
                    value = expr(field.expression()),
                    span = span(field),
                )
            },
            span = span(ctx),
        )
    }

    private fun typeRef(ctx: ColonyParser.TypeRefContext): TypeRef {
        return TypeRef(
            name = ctx.typeName().text,
            arguments = ctx.typeRef().map(::typeRef),
            span = span(ctx),
        )
    }

    private fun numberLiteral(ctx: ColonyParser.NumberLiteralContext): NumberLiteral {
        val text = ctx.text
        val match = Regex("^((?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)([A-Za-z_µ][A-Za-z0-9_µ]*)?$").matchEntire(text)
            ?: error("ANTLR NUMBER/NUMBER_UNIT invariant violated: $text")
        return NumberLiteral(match.groupValues[1], match.groupValues[2].ifEmpty { null }, span(ctx))
    }

    private fun unitLiteral(ctx: ParserRuleContext): UnitLiteral {
        val text = ctx.text
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)([A-Za-z_µ][A-Za-z0-9_µ]*)$").matchEntire(text)
            ?: error("Duration literal must contain a unit: $text")
        return UnitLiteral(match.groupValues[1], match.groupValues[2], span(ctx))
    }

    private fun foldBinary(values: List<Expr>, op: BinaryOperator, at: SourceSpan): Expr =
        values.reduceOrNull { left, right -> BinaryExpr(left, op, right, at) } ?: error("empty binary expression")

    private fun foldBinary(values: List<Expr>, operators: List<BinaryOperator>, at: SourceSpan): Expr {
        require(values.isNotEmpty())
        var result = values.first()
        for (index in 1 until values.size) {
            result = BinaryExpr(result, operators[index - 1], values[index], at)
        }
        return result
    }

    private fun expr(ctx: ParserRuleContext): Expr = visit(ctx) as Expr

    private fun span(ctx: ParserRuleContext): SourceSpan = SourceSpan.from(ctx.start, ctx.stop)

    private fun unquote(tokenText: String): String {
        val raw = tokenText.substring(1, tokenText.length - 1)
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '\\') {
                out.append(raw[i++])
                continue
            }
            require(i + 1 < raw.length) { "invalid string escape" }
            when (val c = raw[i + 1]) {
                '"' -> out.append('\"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    require(i + 5 < raw.length) { "invalid unicode escape" }
                    val hex = raw.substring(i + 2, i + 6)
                    out.append(hex.toInt(16).toChar())
                    i += 4
                }
                else -> error("unsupported string escape: \\$c")
            }
            i += 2
        }
        return out.toString()
    }
}

private fun SourceSpan.Companion.from(start: Token?, stop: Token?): SourceSpan {
    val first = start ?: return SourceSpan.synthetic()
    val last = stop ?: first
    val lastText = last.text ?: ""
    return SourceSpan(
        startLine = first.line,
        startColumn = first.charPositionInLine + 1,
        endLine = last.line,
        endColumn = last.charPositionInLine + lastText.length + 1,
        startOffset = first.startIndex.coerceAtLeast(0),
        endOffsetExclusive = (last.stopIndex + 1).coerceAtLeast(first.startIndex.coerceAtLeast(0)),
    )
}
