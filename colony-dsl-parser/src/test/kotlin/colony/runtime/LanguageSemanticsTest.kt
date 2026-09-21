package colony.runtime

import colony.bytecode.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** End-to-end language semantics: compile DSL, execute on ReferenceVm, compare with the meaning written in the source. */
class LanguageSemanticsTest {
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap())
    private val pos = { x: Int, y: Int -> obj("x" to JsonPrimitive(x), "y" to JsonPrimitive(y)) }
    private fun target(id: String, distance: Int) =
        obj("id" to JsonPrimitive(id), "position" to pos(0, 0), "distance" to JsonPrimitive(distance))

    @Test fun ifLetElseChainWithNestedShortCircuit() {
        val code = compileSource("""
            behavior T for Xenomorph {
                state out: Int64 = 0;
                every 1s as go {
                    if let t = nearest(view.visible_infrastructure) {
                        if t.distance > 2m && (t.id == "a" || t.id == "b") { out = 1; }
                        else if t.distance <= 2m { out = 2; }
                        else { out = 3; }
                    } else if view.position.x > 5m { out = 4; } else { out = 5; }
                }
            }
        """)
        fun run(list: List<JsonElement>, x: Int): Int {
            val vm = ReferenceVm("t", code, "T")
            val view = obj("position" to pos(x, 0), "visible_infrastructure" to JsonArray(list), "patrol_waypoint" to pos(0, 0))
            return vm.step(VmFrame(0, view)).state.getValue("out").jsonPrimitive.int
        }
        assertEquals(1, run(listOf(target("a", 5)), 0))
        assertEquals(1, run(listOf(target("b", 5)), 0))
        assertEquals(3, run(listOf(target("c", 5)), 0))
        assertEquals(2, run(listOf(target("a", 1)), 0))
        assertEquals(4, run(emptyList(), 9))
        assertEquals(5, run(emptyList(), 1))
    }

    @Test fun numericMixing() {
        val code = compileSource("""
            behavior T for House {
                state a: Real64 = 1;
                state b: Real64 = 0.0;
                state c: Real64 = 0.0;
                state d: Int64 = 0;
                state e: Bool = false;
                state f: Real64 = 0.0;
                every 1s as go {
                    b = a + 2;
                    c = 3 / 2;
                    d = 10 % 3;
                    e = 1 == 1.0;
                    f = -a * 2.5;
                }
            }
        """)
        val state = ReferenceVm("t", code, "T").step(VmFrame(0, JsonObject(emptyMap()))).state
        assertEquals(3.0, state.getValue("b").jsonPrimitive.double)
        assertEquals(1.5, state.getValue("c").jsonPrimitive.double)
        assertEquals(1, state.getValue("d").jsonPrimitive.int)
        assertEquals(true, state.getValue("e").jsonPrimitive.boolean)
        assertEquals(-2.5, state.getValue("f").jsonPrimitive.double)
    }

    @Test fun optionFieldsInEvents() {
        val code = compileSource("""
            event Report { v: Option<Real64>; }
            behavior Sender for House {
                param peer: Ref<House>;
                every 1s as go {
                    send Report { v: some(1.5) } to peer;
                    send Report { v: none } to peer;
                }
            }
            behavior Receiver for House {
                state total: Real64 = 0.0;
                state nones: Int64 = 0;
                on Report(m) as take {
                    if let x = m.v { total = total + x; } else { nones = nones + 1; }
                }
            }
        """)
        val sender = ReferenceVm("s", code, "Sender", obj("peer" to JsonPrimitive("r")))
        val receiver = ReferenceVm("r", code, "Receiver")
        val out = sender.step(VmFrame(0, JsonObject(emptyMap())))
        assertEquals(2, out.events.size)
        val state = receiver.step(VmFrame(1, JsonObject(emptyMap()),
            out.events.map { DeliveredEvent(it.eventId, it.fields, it.sender, it.sequence) })).state
        assertEquals(1.5, state.getValue("total").jsonPrimitive.double)
        assertEquals(1, state.getValue("nones").jsonPrimitive.int)
    }

    @Test fun timerPhases() {
        val code = compileSource("""
            behavior T for House {
                state fast: Int64 = 0;
                state slow: Int64 = 0;
                every 2s as a { fast = fast + 1; }
                every 1min as b { slow = slow + 1; }
            }
        """)
        val vm = ReferenceVm("t", code, "T")
        var state = JsonObject(emptyMap())
        for (tick in 0L until 121L) state = vm.step(VmFrame(tick, JsonObject(emptyMap()))).state
        assertEquals(61, state.getValue("fast").jsonPrimitive.int)   // ticks 0,2,...,120
        assertEquals(3, state.getValue("slow").jsonPrimitive.int)    // ticks 0,60,120
    }

    @Test fun durationsAndTime() {
        val code = compileSource("""
            behavior T for Human {
                state next: Duration = 0s;
                state fired: Int64 = 0;
                every 1s as go {
                    if time >= next { fired = fired + 1; next = time + 10s; }
                }
            }
        """)
        val vm = ReferenceVm("t", code, "T")
        var state = JsonObject(emptyMap())
        for (tick in 0L until 35L) state = vm.step(VmFrame(tick, JsonObject(emptyMap()))).state
        assertEquals(4, state.getValue("fired").jsonPrimitive.int) // 0,10,20,30
    }

    @Test fun handlersRunInDeclarationOrderPerEvent() {
        val code = compileSource("""
            event Ping {}
            behavior T for House {
                state trace: Int64 = 0;
                on Ping(m) as first { trace = trace * 10 + 1; }
                on Ping(m) as second { trace = trace * 10 + 2; }
                every 1s as tick { trace = trace * 10 + 3; }
            }
        """)
        val vm = ReferenceVm("t", code, "T")
        val ping = code.events.single { it.name == "Ping" }.id
        val state = vm.step(VmFrame(0, JsonObject(emptyMap()), listOf(DeliveredEvent(ping, JsonObject(emptyMap()))))).state
        assertEquals(123, state.getValue("trace").jsonPrimitive.int)
    }

    @Test fun unaryOperatorsOnPhysicalAndOtherTypes() {
        val code = compileSource("""
            behavior T for House {
                state t: Temperature = -5degC;
                state p: Power = -(2kW);
                state n: Int64 = -3;
                state ok: Bool = !false;
                state d: Duration = 90s;
                state m: Money = 5;
            }
        """)
        val state = ReferenceVm("t", code, "T").stateSnapshot()
        assertEquals(-5.0, state.getValue("t").jsonPrimitive.double)
        assertEquals(-2000.0, state.getValue("p").jsonPrimitive.double)
        assertEquals(-3, state.getValue("n").jsonPrimitive.int)
    }

    @Test fun scientificNotationLiteral() {
        val code = compileSource("behavior T for House { state a: Real64 = 1e3; state b: Real64 = 2.5e-1; }")
        val state = ReferenceVm("t", code, "T").stateSnapshot()
        assertEquals(1000.0, state.getValue("a").jsonPrimitive.double)
        assertEquals(0.25, state.getValue("b").jsonPrimitive.double)
    }

    @Test fun lettersAndScopesInsideBranches() {
        val code = compileSource("""
            behavior T for House {
                state out: Real64 = 0.0;
                every 1s as go {
                    let x = 1.0;
                    if view.occupants > 0 {
                        let y = x + 1.0;
                        if y > 1.5 { let z = y * 2.0; out = z; }
                    } else {
                        let y = 10.0;
                        out = y + x;
                    }
                }
            }
        """)
        fun run(occ: Int) = ReferenceVm("t", code, "T")
            .step(VmFrame(0, obj("occupants" to JsonPrimitive(occ), "temperature" to JsonPrimitive(20)))).state
            .getValue("out").jsonPrimitive.double
        assertEquals(4.0, run(1))
        assertEquals(11.0, run(0))
    }
}
