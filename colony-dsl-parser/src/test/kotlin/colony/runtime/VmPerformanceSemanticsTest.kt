package colony.runtime

import colony.compiler.*
import kotlinx.serialization.json.*
import kotlin.test.*

class VmPerformanceSemanticsTest {
    private fun view(occupants: Int) = JsonObject(mapOf("occupants" to JsonPrimitive(occupants)))

    @Test fun dispatchPreservesSenderSequenceDeclarationAndTimerOrder() {
        val program = compileSource("""
            event Ping { n: Int64; }
            event Ignored {}
            behavior Test for House {
                state trace: Int64 = 0;
                on Ping(m) as first { trace = trace * 10 + m.n; }
                on Ping(m) as second { trace = trace * 10 + 9; }
                every 2s as slow { trace = trace * 10 + 4; }
                every 1s as fast { trace = trace * 10 + 5; }
            }
        """)
        val ping = program.events.single { it.name == "Ping" }.id
        val ignored = program.events.single { it.name == "Ignored" }.id
        fun event(sender: String, sequence: Long, n: Int) =
            DeliveredEvent(ping, JsonObject(mapOf("n" to JsonPrimitive(n))), sender, sequence)
        val vm = NativeVm("house", program, "Test")
        val state = vm.step(VmFrame(0, JsonObject(emptyMap()), listOf(
            event("b", 1, 3), event("a", 2, 2), event("a", 1, 1),
            DeliveredEvent(ignored, JsonObject(emptyMap()))
        ))).state
        assertEquals(19293945L, state.getValue("trace").jsonPrimitive.long)
        assertEquals(192939455L, vm.step(VmFrame(1, JsonObject(emptyMap()))).state.getValue("trace").jsonPrimitive.long)
    }

    @Test fun failedFrameRestoresExistingAndNewRandomStreamsAndOutgoingSequence() {
        val program = compileSource("""
            event Report { n: Int64; }
            behavior Test for House {
                param peer: Ref<House>;
                state count: Int64 = 0;
                every 1s as tick {
                    if chance(0.5, "existing") { count = count + 1; }
                    if view.occupants == 2 {
                        if chance(0.5, "new") { count = count + 10; }
                    }
                    send Report { n: count } to peer;
                    let divisor = view.occupants - 2;
                    let quotient = 10 / divisor;
                }
            }
        """)
        val parameters = JsonObject(mapOf("peer" to JsonPrimitive("receiver")))
        val actual = NativeVm("sender", program, "Test", parameters, seed = 97)
        val control = NativeVm("sender", program, "Test", parameters, seed = 97)
        assertEquals(control.step(VmFrame(0, view(1))), actual.step(VmFrame(0, view(1))))
        val snapshot = actual.stateSnapshot()
        repeat(3) {
            assertFailsWith<IllegalStateException> { actual.step(VmFrame(1, view(2))) }
            assertEquals(snapshot, actual.stateSnapshot())
        }
        repeat(20) { tick ->
            val frame = VmFrame(tick + 1L, view(1))
            assertEquals(control.step(frame), actual.step(frame))
        }
    }

    @Test fun cachedRandomInputMatchesDocumentedStreamForEveryCounter() {
        val program = compileSource("""
            behavior Test for House {
                state first: Bool = false;
                state second: Bool = false;
                every 1s as tick {
                    first = chance(0.5, "first");
                    second = chance(0.5, "second");
                }
            }
        """)
        val vm = NativeVm("entity-\u2603", program, "Test", seed = Long.MIN_VALUE)
        repeat(100) { counter ->
            val state = vm.step(VmFrame(counter.toLong(), JsonObject(emptyMap()))).state
            for (site in listOf("first", "second")) {
                val expected = randomUnit(Long.MIN_VALUE, vm.entityId, "Test", "tick", site, counter.toLong()) < 0.5
                assertEquals(expected, state.getValue(site).jsonPrimitive.boolean)
            }
        }
    }

}
