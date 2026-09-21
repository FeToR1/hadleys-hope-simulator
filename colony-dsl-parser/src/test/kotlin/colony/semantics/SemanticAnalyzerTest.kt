package colony.semantics

import colony.ir.*
import colony.parser.ColonyParserFacade
import kotlin.test.*

class SemanticAnalyzerTest {
    @Test fun zeroTimeStepIsADiagnostic() {
        val result = SemanticAnalyzer(SemanticOptions(deltaTime = "0s")).analyze(
            ColonyParserFacade().parse("behavior Test for House { every 1s as tick {} }"))
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == "SEM_BAD_DT" })
    }

    @Test fun stateInitializerCannotReadWorldOrSpendRandomness() {
        rejects("behavior Test for House { state count: Int64 = view.occupants; }", "SEM_STATE_INITIALIZER")
        rejects("behavior Test for House { state coin: Bool = chance(0.5, \"coin\"); }", "SEM_STATE_INITIALIZER")
    }

    private fun analyze(source: String) = SemanticAnalyzer().analyze(ColonyParserFacade().parse(source))

    @Test fun validHeaterBuildsTypedIr() {
        val ast = ColonyParserFacade().parse("""
            behavior HeaterControl for Heater {
                state target: Power = 2kW;
                every 1s as regulate {
                    if view.home_occupants == 0 { power.request(0W); }
                    else { power.request(target); }
                }
            }
        """)
        val result = SemanticAnalyzer().analyze(ast)
        assertTrue(result.isValid, result.diagnostics.joinToString("\n"))
        val behavior = IRBuilder(result.model).build(ast).behaviors.single()
        assertEquals(1L, behavior.timers.single().periodTicks)
        assertEquals(1, behavior.state.slotCount)
        assertTrue(behavior.blocks.flatMap { it.instructions }.any { it is IRInstruction.CallEffect })
    }

    @Test fun powerCannotBeAssignedToTemperature() = rejects(
        "behavior Broken for House { state temperature: Temperature = 50W; }", "SEM_TYPE_MISMATCH")

    @Test fun foreignStateMutationIsRejected() = rejects("""
        behavior HumanControl for Human {
            param other: Ref<Human>;
            every 1s as mutate { other.stress = 0; }
        }
    """, "SEM_FOREIGN_STATE")

    @Test fun hazardRequiresExactSimulationStep() = rejects("""
        behavior HumanControl for Human { every 2s as risky { hazard(0.4per_s, "strike"); } }
    """, "SEM_HAZARD_PERIOD")

    @Test fun constantChanceIsRangeChecked() = rejects("""
        behavior HumanControl for Human { every 1s as risky { chance(1.2, "strike"); } }
    """, "SEM_PROBABILITY_RANGE")

    @Test fun localAssignmentLowersToStoreLocal() {
        val ast = ColonyParserFacade().parse("""
            behavior HeaterControl for Heater {
                every 1s as tick { let x: Power = 1kW; x = 2kW; }
            }
        """)
        val result = SemanticAnalyzer().analyze(ast)
        assertTrue(result.isValid, result.diagnostics.joinToString("\n"))
        assertTrue(IRBuilder(result.model).build(ast).behaviors.single().blocks
            .flatMap { it.instructions }.any { it is IRInstruction.StoreLocal })
    }

    private fun rejects(source: String, code: String) {
        val result = analyze(source)
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == code }, result.diagnostics.joinToString("\n"))
    }
}
