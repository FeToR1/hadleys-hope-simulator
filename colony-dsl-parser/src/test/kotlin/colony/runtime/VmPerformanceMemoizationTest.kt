package colony.runtime

import colony.bytecode.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class VmPerformanceMemoizationTest {
    private val empty = JsonObject(emptyMap())
    private fun pair(program: BytecodeProgram, behavior: String, budget: Int = 100_000) =
        ReferenceVm("entity", program, behavior, instructionBudget = budget) to
            ReferenceVm("entity", program, behavior, instructionBudget = budget, memoizePeriodicHandlers = false)

    @Test fun repeatedPowerIntentsSurviveMemoHitsAndEventAndObservationChanges() {
        val program = compileSource(File("examples/heater.colony").readText())
        val (cached, control) = pair(program, "HeaterControl")
        val demand = program.events.single { it.name == "HeatingDemand" }.id
        fun frame(tick: Int, occupied: Boolean, broken: Boolean, enabled: Boolean? = null) = VmFrame(
            tick.toLong(), buildJsonObject {
                put("home_occupants", if (occupied) 1 else 0)
                put("broken", broken)
                put("power_connected", true)
            }, enabled?.let { listOf(DeliveredEvent(demand, buildJsonObject { put("enabled", it) })) }.orEmpty())
        val frames = (0..4).map { frame(it, true, false) } +
            (5..9).map { frame(it, true, false, if (it == 5) true else null) } +
            (10..14).map { frame(it, true, true) } +
            (15..19).map { frame(it, false, false) } +
            (20..24).map { frame(it, true, false) }
        frames.forEach { frame ->
            val expected = control.step(frame)
            val actual = cached.step(frame)
            assertEquals(expected, actual, "tick ${frame.tick}")
            assertEquals(1, actual.intents.size)
        }
        assertEquals(2000.0, number(cached.step(frame(25, true, false)).intents.single().arguments.single()))
    }

    @Test fun conditionalTimeRandomAndSendPathsInvalidatePreviousDeterministicPath() {
        val program = compileSource("""
            event Report { n: Int64; }
            behavior Test for House {
                state count: Int64 = 0;
                state clock: Duration = 0s;
                every 1s as tick {
                    if view.occupants > 0 {
                        clock = time;
                        if chance(0.5, "coin") { count = count + 1; }
                        send Report { n: count } to this;
                    } else {
                        count = 7;
                    }
                }
            }
        """)
        val (cached, control) = pair(program, "Test")
        for (tick in 0..30) {
            val frame = VmFrame(tick.toLong(), buildJsonObject { put("occupants", if (tick in 8..20) 1 else 0) })
            assertEquals(control.step(frame), cached.step(frame))
            assertEquals(control.randomDrawCount, cached.randomDrawCount)
        }
        assertEquals(13L, cached.randomDrawCount)
    }

    @Test fun eachDynamicOperationPreventsMemoizationOnItsOwn() {
        for (operation in listOf("clock = time;", "flip = chance(0.5, \"coin\");", "send Report {} to this;")) {
            val program = compileSource("""
                event Report {}
                behavior Test for House {
                    state clock: Duration = 0s;
                    state flip: Bool = false;
                    every 1s as tick {
                        if view.occupants > 0 { $operation }
                    }
                }
            """)
            val (cached, control) = pair(program, "Test")
            repeat(20) { tick ->
                val frame = VmFrame(tick.toLong(), buildJsonObject { put("occupants", if (tick < 4) 0 else 1) })
                assertEquals(control.step(frame), cached.step(frame), "$operation at $tick")
                assertEquals(control.randomDrawCount, cached.randomDrawCount)
            }
        }
    }

    @Test fun writesBeforeReadsReplayAfterAnotherHandlerChangesTheSlot() {
        val program = compileSource("""
            event Change { n: Int64; }
            behavior Test for House {
                state value: Int64 = 0;
                state result: Int64 = 0;
                on Change(m) as change { value = m.n; }
                every 1s as set {
                    value = 4;
                    result = value + 1;
                }
                every 1s as overwrite { value = 9; }
            }
        """)
        val (cached, control) = pair(program, "Test")
        val event = program.events.single().id
        repeat(10) { tick ->
            val frame = VmFrame(tick.toLong(), empty, listOf(DeliveredEvent(event, buildJsonObject { put("n", tick) })))
            assertEquals(control.step(frame), cached.step(frame))
            assertEquals(5, cached.stateSnapshot().getValue("result").jsonPrimitive.int)
        }
    }

    @Test fun memoReplayChargesBudgetAndKeepsOriginalFailureDiagnosticAndRollback() {
        val original = compileSource("behavior Test for House { state value: Int64 = 0; }")
        val behavior = original.behaviors.single().copy(
            temporaryCount = 0, initialize = 0,
            code = listOf(
                Instruction(Op.CONST, value = JsonPrimitive(0)), Instruction(Op.STORE_STATE, arg = 0), Instruction(Op.RETURN),
                Instruction(Op.RETURN),
                Instruction(Op.CONST, value = JsonPrimitive(1)), Instruction(Op.STORE_STATE, arg = 0), Instruction(Op.RETURN)
            ),
            handlers = listOf(Handler("preceding", 3, 0, periodTicks = 2), Handler("cached", 4, 0, periodTicks = 1))
        )
        val (cached, control) = pair(original.copy(behaviors = listOf(behavior)), "Test", budget = 3)
        assertEquals(control.step(VmFrame(1, empty)), cached.step(VmFrame(1, empty)))
        repeat(2) {
            val expected = assertFailsWith<IllegalStateException> { control.step(VmFrame(2, empty)) }
            val actual = assertFailsWith<IllegalStateException> { cached.step(VmFrame(2, empty)) }
            assertEquals(expected.message, actual.message)
            assertEquals(control.stateSnapshot(), cached.stateSnapshot())
        }
        assertEquals(control.step(VmFrame(3, empty)), cached.step(VmFrame(3, empty)))
    }

    @Test fun memoReplayRespectsOutputsAlreadyProducedEarlierInTheTick() {
        val original = compileSource("behavior Test for Heater {}")
        val behavior = original.behaviors.single().copy(
            temporaryCount = 0, initialize = 0,
            code = listOf(
                Instruction(Op.RETURN),
                Instruction(Op.CONST, value = JsonPrimitive(1)), Instruction(Op.POWER_REQUEST), Instruction(Op.RETURN),
                Instruction(Op.CONST, value = JsonPrimitive(2)), Instruction(Op.POWER_REQUEST), Instruction(Op.RETURN)
            ),
            handlers = List(1024) { index -> Handler("earlier$index", 1, 0, periodTicks = 2) } +
                Handler("cached", 4, 0, periodTicks = 1))
        val (cached, control) = pair(original.copy(behaviors = listOf(behavior)), "Test")
        assertEquals(control.step(VmFrame(1, empty)), cached.step(VmFrame(1, empty)))
        val expected = assertFailsWith<IllegalStateException> { control.step(VmFrame(2, empty)) }
        val actual = assertFailsWith<IllegalStateException> { cached.step(VmFrame(2, empty)) }
        assertEquals(expected.message, actual.message)
        assertContains(actual.message.orEmpty(), "Output limit exceeded")
        assertEquals(control.step(VmFrame(3, empty)), cached.step(VmFrame(3, empty)))
    }
}
