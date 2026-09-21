package colony.runtime

import colony.bytecode.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ReferenceVmTest {
    @Test fun verifierRejectsBrokenStackAndJumpTargets() {
        val code = compileSource("behavior Test for House {}")
        val behavior = code.behaviors.single()
        assertFailsWith<IllegalArgumentException> {
            BytecodeVerifier.verify(code.copy(behaviors = listOf(behavior.copy(code = listOf(Instruction(Op.JUMP, arg = 42))))))
        }
        assertFailsWith<IllegalArgumentException> {
            BytecodeVerifier.verify(code.copy(behaviors = listOf(behavior.copy(code = listOf(Instruction(Op.BINARY), Instruction(Op.RETURN))))))
        }
        assertFailsWith<IllegalArgumentException> { BytecodeVerifier.verify(code.copy(version = 2)) }
    }

    @Test fun instructionQuotaStopsAnInfiniteBytecodeLoop() {
        val code = compileSource("behavior Test for House {}")
        val loop = code.copy(behaviors = listOf(code.behaviors.single().copy(code = listOf(Instruction(Op.JUMP, arg = 0)))))
        val error = assertFailsWith<IllegalStateException> { ReferenceVm("test", loop, "Test", instructionBudget = 20) }
        assertContains(error.message.orEmpty(), "Instruction budget exceeded")
    }

    @Test fun realHeaterProgramExchangesEventsAndShutsOffWhenEmpty() {
        val original = compileSource(File("examples/heater.colony").readText())
        val code = bytecodeJson.decodeFromString<BytecodeProgram>(bytecodeJson.encodeToString(original))
        val home = ReferenceVm("home", code, "HouseControl", obj("heater" to JsonPrimitive("heater")))
        val heater = ReferenceVm("heater", code, "HeaterControl")
        val request = home.step(VmFrame(0, obj("occupants" to JsonPrimitive(1), "temperature" to JsonPrimitive(16))))
        assertEquals("heater", request.events.single().target)
        val view = obj("home_occupants" to JsonPrimitive(1), "broken" to JsonPrimitive(false), "power_connected" to JsonPrimitive(true))
        assertEquals(0.0, number(heater.step(VmFrame(0, view)).intents.single().arguments.single()))
        val event = request.events.single()
        assertEquals(2000.0, number(heater.step(VmFrame(1, view, listOf(DeliveredEvent(event.eventId, event.fields)))).intents.single().arguments.single()))
        assertEquals(0.0, number(heater.step(VmFrame(2, JsonObject(view + ("home_occupants" to JsonPrimitive(0))))).intents.single().arguments.single()))
    }

    @Test fun initializationEnumsRecordsAndShortCircuitExecute() {
        val code = compileSource("""
            event Value { n: Int64; }
            behavior Test for House {
                enum Mode { Ready, Waiting }
                state mode: Mode = Ready;
                state count: Int64 = 2 + 3 * 4;
                every 1s as update {
                    let local = Value { n: 7 };
                    if (false && chance(1.0, "skip")) || (true || chance(1.0, "also_skip")) {
                        count = count + local.n;
                    }
                    if mode == Ready { mode = Waiting; }
                }
            }
        """)
        val vm = ReferenceVm("test", code, "Test")
        assertEquals(14, vm.stateSnapshot().getValue("count").jsonPrimitive.int)
        val result = vm.step(VmFrame(0, JsonObject(emptyMap())))
        assertEquals(21, result.state.getValue("count").jsonPrimitive.int)
        assertEquals("Waiting", result.state.getValue("mode").jsonPrimitive.content)
        assertEquals(0L, vm.randomDrawCount)
    }

    @Test fun randomReplayAndFailedFrameRollback() {
        val code = compileSource("""
            behavior Test for House {
                state count: Int64 = 0;
                every 1s as step {
                    if chance(0.5, "coin") { count = count + 1; }
                    let fail = 10 / view.occupants;
                }
            }
        """)
        val first = ReferenceVm("a", code, "Test", seed = 123)
        val second = ReferenceVm("a", code, "Test", seed = 123)
        assertFails { first.step(VmFrame(0, obj("occupants" to JsonPrimitive(0)))) }
        assertEquals(0L, first.randomDrawCount)
        assertEquals(0, first.stateSnapshot().getValue("count").jsonPrimitive.int)
        repeat(30) {
            val frame = VmFrame(it.toLong(), obj("occupants" to JsonPrimitive(1)))
            assertEquals(first.step(frame), second.step(frame))
        }
    }

    @Test fun nearestAndDomainInstructionsCompileFromActualExamples() {
        for (file in listOf("resident", "xenomorph")) {
            val program = compileSource(File("examples/$file.colony").readText())
            val vm = ReferenceVm(file, program, program.behaviors.single().name)
            val position = obj("x" to JsonPrimitive(0), "y" to JsonPrimitive(0))
            val target = obj("id" to JsonPrimitive("pole"), "position" to position, "distance" to JsonPrimitive(1))
            vm.step(VmFrame(0, obj("cold" to JsonPrimitive(false), "position" to position,
                "reachable_breakables" to JsonArray(listOf(target)), "visible_infrastructure" to JsonArray(listOf(target)), "patrol_waypoint" to position)))
        }
    }

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap())
}
