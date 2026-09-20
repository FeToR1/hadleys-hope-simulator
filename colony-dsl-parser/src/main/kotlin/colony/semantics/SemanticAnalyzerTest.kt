package colony.semantics

import colony.ast.*
import colony.ir.IRBuilder
import colony.ir.IRInstruction
import colony.ir.IRProgram
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticAnalyzerTest {
    private val s = SourceSpan(1, 1, 1, 1, 0, 0)

    @Test
    fun validHeaterBuildsTypedIr() {
        val program = Program(
            declarations = listOf(
                behavior(
                    name = "HeaterControl",
                    target = "Heater",
                    members = listOf(
                        StateDecl("target", type("Power"), num("2", "kW"), s),
                        EveryRule(
                            UnitLiteral("1", "s", s),
                            "regulate",
                            block(
                                IfStmt(
                                    ExprCondition(
                                        BinaryExpr(
                                            MemberExpr(NameExpr("view", s), "home_occupants", s),
                                            BinaryOperator.EQ,
                                            num("0"),
                                            s,
                                        ),
                                        s,
                                    ),
                                    block(
                                        ExprStmt(
                                            call(
                                                member(NameExpr("power", s), "request", s),
                                                num("0", "W"),
                                            ),
                                        ),
                                        s,
                                    ),
                                    ElseBlock(
                                        block(
                                            ExprStmt(
                                                call(
                                                    member(NameExpr("power", s), "request", s),
                                                    NameExpr("target", s),
                                                ),
                                            ),
                                            s,
                                        ),
                                    ),
                                    s,
                                ),
                            ),
                        ),
                    ),
                    s,
                ),
            ),
            span = s,
        )

        val result = SemanticAnalyzer(SemanticOptions(deltaTime = "1s")).analyze(program)
        assertTrue(result.isValid, result.diagnostics.joinToString("\n"))

        val ir: IRProgram = IRBuilder(result.model).build(program)
        val behavior = ir.behaviors.single()
        assertTrue(behavior.timers.single().periodTicks == 1L)
        assertTrue(behavior.state.slotCount == 1)
        assertTrue(
            behavior.blocks.flatMap { it.instructions }.any { it is IRInstruction.CallEffect },
            "Expected an external effect in generated IR",
        )
    }

    @Test
    fun powerCannotBeAssignedToTemperature() {
        val program = Program(
            listOf(
                behavior(
                    "Broken",
                    "House",
                    listOf(StateDecl("temperature", type("Temperature"), num("50", "W"), s)),
                    s,
                ),
            ),
            s,
        )

        val result = SemanticAnalyzer().analyze(program)
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == "SEM_TYPE_MISMATCH" })
    }

    @Test
    fun foreignStateMutationIsRejected() {
        val program = Program(
            listOf(
                behavior(
                    "HumanControl",
                    "Human",
                    listOf(
                        ParamDecl("other", refType("Human"), s),
                        EveryRule(
                            UnitLiteral("1", "s", s),
                            "mutate",
                            block(
                                AssignStmt(LValue(listOf("other", "stress"), s), num("0"), s),
                            ),
                        ),
                    ),
                    s,
                ),
            ),
            s,
        )

        val result = SemanticAnalyzer().analyze(program)
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == "SEM_FOREIGN_STATE" })
    }

    @Test
    fun hazardRequiresExactSimulationStep() {
        val program = Program(
            listOf(
                behavior(
                    "HumanControl",
                    "Human",
                    listOf(
                        EveryRule(
                            UnitLiteral("2", "s", s),
                            "risky",
                            block(
                                ExprStmt(call(NameExpr("hazard", s), num("0.4", "per_s"), text("strike"))),
                            ),
                        ),
                    ),
                    s,
                ),
            ),
            s,
        )

        val result = SemanticAnalyzer(SemanticOptions(deltaTime = "1s")).analyze(program)
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == "SEM_HAZARD_PERIOD" })
    }

    @Test
    fun constantChanceIsRangeChecked() {
        val bad = Program(
            listOf(
                behavior(
                    "HumanControl",
                    "Human",
                    listOf(
                        EveryRule(
                            UnitLiteral("1", "s", s),
                            "risky",
                            block(
                                ExprStmt(call(NameExpr("chance", s), num("1.2"), text("strike"))),
                            ),
                        ),
                    ),
                    s,
                ),
            ),
            s,
        )

        val result = SemanticAnalyzer().analyze(bad)
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == "SEM_PROBABILITY_RANGE" })
    }

    @Test
    fun localAssignmentLowersToStoreLocal() {
        val program = Program(
            listOf(
                behavior(
                    "HeaterControl",
                    "Heater",
                    listOf(
                        EveryRule(
                            UnitLiteral("1", "s", s),
                            "tick",
                            block(
                                LetStmt("x", type("Power"), num("1", "kW"), s),
                                AssignStmt(LValue(listOf("x"), s), num("2", "kW"), s),
                            ),
                        ),
                    ),
                    s,
                ),
            ),
            s,
        )

        val result = SemanticAnalyzer().analyze(program)
        assertTrue(result.isValid, result.diagnostics.joinToString("\n"))
        val ir = IRBuilder(result.model).build(program)
        assertTrue(
            ir.behaviors.single().blocks.flatMap { it.instructions }.any { it is IRInstruction.StoreLocal },
        )
    }

    private fun behavior(name: String, target: String, members: List<BehaviorMember>, span: SourceSpan) =
        BehaviorDecl(name, target, members, span)

    private fun type(name: String) = TypeRef(name, span = s)

    private fun refType(kind: String) = TypeRef("Ref", listOf(TypeRef(kind, span = s)), s)

    private fun num(raw: String, unit: String? = null) = NumberLiteral(raw, unit, s)

    private fun text(value: String) = StringLiteral(value, s)

    private fun block(vararg statements: Stmt) = Block(statements.toList(), s)

    private fun member(receiver: Expr, name: String, span: SourceSpan) = MemberExpr(receiver, name, span)

    private fun call(callee: Expr, vararg args: Expr) = CallExpr(callee, args.toList(), s)
}
