package colony.semantics

import colony.bytecode.*
import colony.parser.ColonyParserFacade
import kotlin.test.*

/** Regression tests for diagnostics and step-size handling found during integration. */
class CompilerDiagnosticsTest {
    private fun analyze(source: String, step: String = "1s") =
        SemanticAnalyzer(SemanticOptions(deltaTime = step)).analyze(ColonyParserFacade().parse(source))

    private fun codes(source: String, step: String = "1s") = analyze(source, step).diagnostics.map { it.code }

    @Test fun fractionalStepsAcceptWholeSecondPeriods() {
        // BigDecimal.equals is scale-sensitive: 1s % 0.100s used to be "0.00 != 0" and every period was rejected.
        for ((step, ticks, canonical) in listOf(Triple("100ms", 10L, "0.1"), Triple("0.5s", 2L, "0.5"), Triple("250ms", 4L, "0.25"))) {
            val program = compileSource("behavior T for House { every 1s as tick { } }", step)
            assertEquals(canonical, program.stepSeconds, step)
            assertEquals(ticks, program.behaviors.single().handlers.single().periodTicks, step)
        }
    }

    @Test fun periodMustStillBeAMultipleOfAFractionalStep() {
        assertEquals(listOf("SEM_PERIOD_GRID"), codes("behavior T for House { every 750ms as tick { } }", "500ms"))
        assertEquals(emptyList(), codes("behavior T for House { every 1500ms as tick { } }", "500ms"))
    }

    @Test fun hazardIsAllowedInAHandlerWhosePeriodEqualsAFractionalStep() {
        val source = """behavior T for Xenomorph { every 100ms as strike { if hazard(0.4per_s, "s") { } } }"""
        assertEquals(emptyList(), codes(source, "100ms"))
        assertEquals(listOf("SEM_HAZARD_PERIOD"), codes(source.replace("100ms as", "200ms as"), "100ms"))
    }

    @Test fun absurdlyLongPeriodIsADiagnosticNotACrash() {
        assertEquals(listOf("SEM_BAD_PERIOD"), codes("behavior T for House { every 99999999999999999999999999h as tick { } }"))
    }

    @Test fun anAlreadyReportedOperandDoesNotCascade() {
        assertEquals(listOf("SEM_UNKNOWN_VIEW"), codes("behavior T for House { every 1s as x { if view.nope && view.occupants > 0 { } } }"))
        assertEquals(listOf("SEM_UNKNOWN_VIEW"), codes("behavior T for House { every 1s as x { let a = view.nope + 1; let b = a * 2; if b > 3 { } } }"))
        assertEquals(listOf("SEM_UNDECLARED"), codes("behavior T for Human { every 1s as x { if let t = nearest(view.reachable_breakables) { damage.request(t.id, 5hp, Nope); } } }"))
    }

    @Test fun refToAnUnknownKindAndUnknownTypeNamesAreRejected() {
        assertEquals(listOf("SEM_UNKNOWN_KIND"), codes("behavior T for House { param x: Ref<Nothing>; }"))
        assertEquals(listOf("SEM_UNKNOWN_KIND"), codes("behavior T for House { state x: Option<Ref<Nothing>> = none; }"))
        assertEquals(listOf("SEM_UNKNOWN_TYPE"), codes("behavior T for House { state s: Float = 1.0; }"))
        // Known kinds and the built-in records remain valid type names.
        assertEquals(emptyList(), codes("event Sighting { spot: Position; seen: List<Target>; } behavior T for House { param h: Ref<Heater>; }"))
    }

    @Test fun eventNamesMayBeUsedAsTypesEvenBeforeTheirDeclaration() {
        assertEquals(emptyList(), codes("event First { later: Later; } event Later { n: Int64; }"))
    }

    @Test fun stateAndParametersHoldOnlyFlatValues() {
        assertEquals(emptyList(), codes("behavior T for House { param h: Ref<Heater>; state a: Option<Bool> = none; state b: Option<Ref<Heater>> = none; }"))
        assertEquals(listOf("SEM_FLAT_TYPE"), codes("behavior T for House { param spot: Position; }"))
        assertContains(codes("behavior T for House { state xs: List<Real64> = none; }"), "SEM_FLAT_TYPE")
        assertContains(codes("event E {} behavior T for House { state e: E = none; }"), "SEM_FLAT_TYPE")
    }

    @Test fun anOptionOfAnOptionIsRejectedBecauseNoneIsTheOnlyEmptyValue() {
        assertEquals(listOf("SEM_NESTED_OPTION"), codes("behavior T for House { state x: Option<Option<Bool>> = none; }"))
        assertContains(codes("behavior T for House { every 1s as tick { let x = some(some(1)); } }"), "SEM_NESTED_OPTION")
        assertContains(codes("behavior T for House { every 1s as tick { let x = some(none); } }"), "SEM_NESTED_OPTION")
    }

    @Test fun viewIsOnlyAReceiverOfFieldAccess() {
        assertEquals(emptyList(), codes("behavior T for House { every 1s as x { let n = view.occupants; } }"))
        assertEquals(listOf("SEM_VIEW_VALUE"), codes("behavior T for House { every 1s as x { let v = view; } }"))
    }

    @Test fun numericLiteralsMustFitTheirRuntimeRepresentation() {
        assertEquals(emptyList(), codes("behavior T for House { state n: Int64 = 9223372036854775807; }"))
        assertEquals(listOf("SEM_INT_RANGE"), codes("behavior T for House { state n: Int64 = 9223372036854775808; }"))
        assertEquals(listOf("SEM_NUMBER_RANGE"), codes("behavior T for House { state r: Real64 = 1e999; }"))
        assertEquals(listOf("SEM_NUMBER_RANGE"), codes("behavior T for House { state p: Power = 1e400kW; }"))
    }
}
