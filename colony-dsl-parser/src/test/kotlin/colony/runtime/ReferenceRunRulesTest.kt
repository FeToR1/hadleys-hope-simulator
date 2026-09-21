package colony.runtime

import colony.bytecode.compileSource
import kotlinx.serialization.json.*
import kotlin.test.*

/** Rules of docs/simulation that the reference run must honour: one-step intents, dead executors, damage before power. */
class ReferenceRunRulesTest {
    private fun point(x: Int, y: Int) = buildJsonObject { put("x", x); put("y", y) }
    private val heaterView = buildJsonObject { put("home_occupants", 1); put("broken", false); put("power_connected", true) }

    private fun prepare(source: String, objects: Map<String, ObjectSpec>, ticks: Int): PreparedRun {
        val program = compileSource(source)
        val catalog = Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(objects)))
        val scenario = Scenario(catalog = "inline", ticks = ticks, populations = listOf(Population("t", 1, "g")))
        return expandScenario(scenario, catalog, program)
    }

    private fun TickSnapshot.entity(id: String) = entities.single { it.id == id }
    private fun EntitySnapshot.number(name: String) = metrics.getValue(name).jsonPrimitive.double

    @Test fun powerRequestsLiveForOneStepOnly() {
        val run = ReferenceRun(prepare(
            "behavior Pulse for Heater { every 2s as request { power.request(1kW); } }",
            mapOf("heater" to ObjectSpec("Heater", "Pulse", view = heaterView)), ticks = 4), "test")
        val power = (0 until 4).map { run.step().entity("g-1/heater").number("power_consumption") }
        // Rule fires on ticks 0 and 2; a request that is not repeated counts as zero (spec, rule 4).
        assertEquals(listOf(1000.0, 0.0, 1000.0, 0.0), power)
    }

    @Test fun destroyedExecutorsAreRejectedAndDamageComesBeforePower() {
        val source = """
            behavior Killer for Xenomorph {
                enum Cause { Kill }
                every 1s as strike {
                    if let target = nearest(view.visible_infrastructure) { damage.request(target.id, 100hp, Kill); }
                }
            }
            behavior Load for Heater { every 1s as draw { power.request(1kW); } }
        """
        val target = buildJsonObject { put("id", "\$instance/heater"); put("position", point(0, 0)); put("distance", 0) }
        val alienView = buildJsonObject {
            put("position", point(0, 0)); put("patrol_waypoint", point(0, 0))
            put("visible_infrastructure", buildJsonArray { add(target) })
        }
        val run = ReferenceRun(prepare(source, mapOf(
            "alien" to ObjectSpec("Xenomorph", "Killer", view = alienView),
            "heater" to ObjectSpec("Heater", "Load", view = heaterView)), ticks = 3), "test")

        val first = run.step()
        // Alive in S_0, so its request was accepted, but it is destroyed in the damage phase and consumes nothing.
        assertEquals("dead", first.entity("g-1/heater").status)
        assertEquals(0.0, first.entity("g-1/heater").number("health"))
        assertEquals(0.0, first.entity("g-1/heater").number("power_consumption"))
        assertTrue(first.effects.single { it.source == "g-1/heater" }.accepted)

        val second = run.step()
        // Dead in S_1: the request is rejected outright.
        assertFalse(second.effects.single { it.source == "g-1/heater" }.accepted)
        assertEquals(0.0, second.entity("g-1/heater").number("power_consumption"))
        assertEquals(100.0, second.entity("g-1/alien").number("health"))
    }

    @Test fun coordinatesMayBeNegativeButDistancesMayNot() {
        val known = mapOf("a" to Instance("a", "House", "B", JsonObject(emptyMap()), JsonObject(emptyMap()), null, 0.0, 0.0))
        validateValue(point(-5, 3), "Position", known, "p")
        val target = buildJsonObject { put("id", "a"); put("position", point(-1, -2)); put("distance", 4) }
        validateValue(target, "Target", known, "t")
        assertFails { validateValue(JsonObject(target + ("distance" to JsonPrimitive(-1))), "Target", known, "t") }
        assertFails { validateValue(buildJsonObject { put("x", "left"); put("y", 3) }, "Position", known, "p") }
    }
}
