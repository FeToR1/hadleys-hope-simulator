package colony.runtime

import colony.bytecode.compileSource
import kotlinx.serialization.json.*
import kotlin.test.*

/** What the reference run computes for the programs (docs/simulation/trigger-conditions.md, section 6). */
class ReferenceObservationsTest {
    private val program = compileSource("""
        behavior HouseWatch for House {
            state first_kind: String = "";
            state first_broken: Bool = true;
            every 1s as w { first_kind = view.devices[0].kind; first_broken = view.devices[0].broken; }
        }
        behavior HeaterWatch for Heater {
            state granted: Power = 0W;
            state occupants: Int64 = -1;
            every 1s as w { granted = view.power_granted; occupants = view.home_occupants; power.request(1500W); }
        }
        behavior HumanWatch for Human {
            state hp: Health = 0hp;
            every 1s as w { hp = view.health; }
        }
        behavior AlienWatch for Xenomorph {
            enum Cause { Bite }
            state seen: String = "";
            state seen_kind: String = "";
            state seen_hp: Health = 0hp;
            state nearest_first: String = "";
            every 1s as w {
                if let t = nearest(view.visible_humans) {
                    seen = t.id; seen_kind = t.kind; seen_hp = t.health;
                    damage.request(t.id, 30hp, Bite);
                }
                nearest_first = view.visible_infrastructure[0].id;
            }
        }
    """)

    private fun ref(id: String) = buildJsonObject { put("id", "\$instance/$id") }
    private val objects = mapOf(
        "house" to ObjectSpec("House", "HouseWatch", view = buildJsonObject { put("occupants", 2); put("temperature", 18) }),
        "heater" to ObjectSpec("Heater", "HeaterWatch", parent = "\$instance/house", x = 10.0, y = 10.0),
        "human" to ObjectSpec("Human", "HumanWatch", parent = "\$instance/house", x = 30.0, y = 0.0, view = buildJsonObject {
            put("cold", false); put("reachable_breakables", buildJsonArray { })
        }),
        "alien" to ObjectSpec("Xenomorph", "AlienWatch", x = 100.0, y = 0.0, view = buildJsonObject {
            put("patrol_waypoint", buildJsonObject { put("x", 0); put("y", 0) })
            // Deliberately listed far-first: the run must sort by distance.
            put("visible_infrastructure", buildJsonArray { add(ref("house")); add(ref("heater")) })
            put("visible_humans", buildJsonArray { add(ref("human")) })
        }),
    )

    private fun prepare(objects: Map<String, ObjectSpec> = this.objects, changes: List<ObservationChange> = emptyList(), ticks: Int = 4): PreparedRun =
        expandScenario(
            Scenario(catalog = "inline", ticks = ticks, populations = listOf(Population("t", 1, "g")), changes = changes),
            Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(objects))), program)

    private fun TickSnapshot.state(id: String, name: String) = entities.single { it.id == id }.vmState.getValue(name)

    @Test fun theRunComputesHealthPowerDevicesAndSortedEnrichedTargets() {
        val run = ReferenceRun(prepare(), "test")
        val first = run.step()
        assertEquals("Heater", first.state("g-1/house", "first_kind").jsonPrimitive.content)
        assertEquals(false, first.state("g-1/house", "first_broken").jsonPrimitive.boolean)
        assertEquals(2, first.state("g-1/heater", "occupants").jsonPrimitive.int, "occupants come from the house")
        assertEquals(0.0, first.state("g-1/heater", "granted").jsonPrimitive.double, "nothing was granted before step 0")
        assertEquals(100.0, first.state("g-1/human", "hp").jsonPrimitive.double)
        assertEquals("g-1/human", first.state("g-1/alien", "seen").jsonPrimitive.content)
        assertEquals("Human", first.state("g-1/alien", "seen_kind").jsonPrimitive.content)
        assertEquals(100.0, first.state("g-1/alien", "seen_hp").jsonPrimitive.double)
        assertEquals("g-1/heater", first.state("g-1/alien", "nearest_first").jsonPrimitive.content, "sorted by distance, not by input order")

        val second = run.step()
        assertEquals(1500.0, second.state("g-1/heater", "granted").jsonPrimitive.double, "power_granted is last step's allocation")
        assertEquals(70.0, second.state("g-1/human", "hp").jsonPrimitive.double, "the bite of step 0 is visible in step 1")
        assertEquals(70.0, second.state("g-1/alien", "seen_hp").jsonPrimitive.double)
    }
    @Test fun aDestroyedApplianceShowsAsBrokenInItsHousesDeviceList() {
        val biter = objects.getValue("alien").copy(view = buildJsonObject {
            put("patrol_waypoint", buildJsonObject { put("x", 0); put("y", 0) })
            put("visible_infrastructure", buildJsonArray { add(ref("house")) })
            put("visible_humans", buildJsonArray { add(ref("heater")) })
        })
        val run = ReferenceRun(prepare(objects + ("alien" to biter), ticks = 6), "test")
        val broken = (0 until 5).map { run.step().state("g-1/house", "first_broken").jsonPrimitive.boolean }
        // 30 hp per step: the heater is destroyed in step 3 and its house sees that from step 4 on.
        assertEquals(listOf(false, false, false, false, true), broken)
    }

    @Test fun catalogsMayOmitDefaultsAndComputedFieldsCannotBeGiven() {
        val house = prepare().manifest.instances.single { it.id == "g-1/house" }
        assertEquals(JsonPrimitive(true), house.view["power_connected"])
        assertEquals(JsonPrimitive(true), house.view["water_available"])

        val alien = objects.getValue("alien")
        val withoutHumans = alien.copy(view = JsonObject(alien.view - "visible_humans"))
        val filled = prepare(objects + ("alien" to withoutHumans)).manifest.instances.single { it.id == "g-1/alien" }
        assertEquals(JsonArray(emptyList()), filled.view["visible_humans"])

        val withDevices = objects.getValue("house").copy(view = buildJsonObject {
            put("occupants", 2); put("temperature", 18); put("devices", buildJsonArray { })
        })
        val rejected = assertFailsWith<IllegalArgumentException> { prepare(objects + ("house" to withDevices)) }
        assertContains(rejected.message.orEmpty(), "computed by the run")
        val change = ObservationChange(1, "g-1/human", buildJsonObject { put("health", 5) })
        assertFailsWith<IllegalArgumentException> { prepare(changes = listOf(change)) }
    }
}
