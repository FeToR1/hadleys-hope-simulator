package colony.runtime

import colony.bytecode.compileSource
import kotlinx.serialization.json.*
import kotlin.test.*

/** Events the world establishes (docs/simulation/trigger-conditions.md, section 3) and how programs receive them. */
class WorldEventsTest {
    private val program = compileSource("""
        event ActionSucceeded { action: String; }
        event ActionRejected { action: String; reason: String; }
        event DamageApplied { target: String; amount: Health; reason: String; }
        event ObjectBroken { object: String; reason: String; }
        event PowerLost {}
        event PowerRestored {}
        event WaterLost {}

        behavior Owner for House {
            state hits: Int64 = 0;
            state broken: String = "";
            state power_lost: Int64 = 0;
            state power_restored: Int64 = 0;
            state water_lost: Int64 = 0;
            on DamageApplied(m) as hurt { hits = hits + 1; }
            on ObjectBroken(m) as lost { broken = m.object; }
            on PowerLost(m) as dark { power_lost = power_lost + 1; }
            on PowerRestored(m) as light { power_restored = power_restored + 1; }
            on WaterLost(m) as dry { water_lost = water_lost + 1; }
        }
        behavior Appliance for Heater {
            state power_lost: Int64 = 0;
            state connected: Bool = true;
            on PowerLost(m) as dark { power_lost = power_lost + 1; }
            every 1s as watch { connected = view.power_connected; }
        }
        behavior Occupant for Human {
            state power_lost: Int64 = 0;
            on PowerLost(m) as dark { power_lost = power_lost + 1; }
        }
        behavior Biter for Xenomorph {
            param dmg: Health;
            enum Cause { Bite }
            state confirmed: Int64 = 0;
            state rejected: Int64 = 0;
            state last_reason: String = "";
            on ActionSucceeded(m) as ok { confirmed = confirmed + 1; }
            on ActionRejected(m) as no { rejected = rejected + 1; last_reason = m.reason; }
            every 1s as bite {
                if let t = nearest(view.visible_humans) { damage.request(t.id, dmg, Bite); }
            }
        }
    """)

    private fun ref(id: String) = buildJsonObject { put("id", "\$instance/$id") }
    private fun point(x: Int, y: Int) = buildJsonObject { put("x", x); put("y", y) }
    private fun alien(targets: List<String>, damage: Int, x: Double) = ObjectSpec("Xenomorph", "Biter",
        params = buildJsonObject { put("dmg", damage) }, x = x, view = buildJsonObject {
            put("patrol_waypoint", point(0, 0))
            put("visible_infrastructure", buildJsonArray { add(ref("house")) })
            put("visible_humans", buildJsonArray { targets.forEach { add(ref(it)) } })
        })
    private val house = ObjectSpec("House", "Owner", view = buildJsonObject { put("occupants", 1); put("temperature", 18) })
    private val heater = ObjectSpec("Heater", "Appliance", parent = "\$instance/house", x = 10.0, y = 10.0)
    private val resident = ObjectSpec("Human", "Occupant", parent = "\$instance/house", x = 20.0, view = buildJsonObject {
        put("cold", false); put("reachable_breakables", buildJsonArray { })
    })

    private fun run(objects: Map<String, ObjectSpec>, ticks: Int = 8, changes: List<ObservationChange> = emptyList()): ReferenceRun {
        val scenario = Scenario(catalog = "inline", ticks = ticks, populations = listOf(Population("t", 1, "g")), changes = changes)
        val catalog = Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(objects)))
        return ReferenceRun(expandScenario(scenario, catalog, program), "test")
    }

    private fun TickSnapshot.state(id: String, name: String) = entities.single { it.id == id }.vmState.getValue(name).jsonPrimitive
    private fun TickSnapshot.eventsOf(type: String) = events.filter { it.type == type }

    @Test fun damageProducesAChainOfEventsThatEndsInABrokenObject() {
        // 60 hp per bite: the heater survives the first bite and is destroyed by the second.
        val run = run(mapOf("house" to house, "heater" to heater, "biter" to alien(listOf("heater"), 60, 100.0)))
        val first = run.step()
        assertEquals(listOf("ActionSucceeded", "DamageApplied"), first.events.map { it.type })
        val applied = first.eventsOf("DamageApplied").single()
        assertEquals("g-1/heater", applied.entityId)
        assertEquals("g-1/biter", applied.actorId)
        assertEquals(listOf("g-1/biter", "g-1/house"), applied.recipients, "executor and the owner of the appliance")
        assertEquals(first.effects.single { it.operation == "DAMAGE_REQUEST" }.ref, applied.causationId, "caused by the request")

        val second = run.step()
        val broken = second.eventsOf("ObjectBroken").single()
        assertEquals(second.eventsOf("DamageApplied").single().id, broken.causationId, "the break is caused by the hit that took the last hp")
        assertEquals(listOf("g-1/house"), broken.recipients)
        assertEquals(second.events.map { it.id }.toSet().size, second.events.size, "ids are unique")
    }

    @Test fun programsReceiveWorldEventsInTheNextFrameOnlyIfTheyDeclaredThem() {
        val run = run(mapOf("house" to house, "heater" to heater, "biter" to alien(listOf("heater"), 60, 100.0)))
        val first = run.step()
        assertEquals(0, first.state("g-1/house", "hits").int, "events of step 0 are not visible in step 0")
        assertEquals(0, first.state("g-1/biter", "confirmed").int)
        val second = run.step()
        assertEquals(1, second.state("g-1/house", "hits").int)
        assertEquals(1, second.state("g-1/biter", "confirmed").int)
        assertEquals("", second.state("g-1/house", "broken").content, "not broken yet")
        val third = run.step()
        assertEquals("g-1/heater", third.state("g-1/house", "broken").content)
        assertEquals(2, third.state("g-1/house", "hits").int)
    }

    @Test fun deadExecutorsGetTheirActionsRejectedWithAReason() {
        // Y destroys X in step 0; X, alive in S_0, still hits Z. From step 1 X is dead and its bites are rejected.
        val run = run(mapOf(
            "house" to house,
            "x" to alien(listOf("z"), 30, 100.0),
            "y" to alien(listOf("x"), 100, 200.0),
            "z" to alien(emptyList(), 30, 300.0),
        ))
        val first = run.step()
        assertEquals("dead", first.entities.single { it.id == "g-1/x" }.status)
        assertTrue(first.effects.single { it.source == "g-1/x" && it.operation == "DAMAGE_REQUEST" }.accepted)
        assertEquals(listOf("EntityDied"), first.events.filter { it.entityId == "g-1/x" && it.type == "EntityDied" }.map { it.type })
        val second = run.step()
        val rejected = second.eventsOf("ActionRejected").single()
        assertEquals("g-1/x", rejected.entityId)
        assertEquals("executor_unable", rejected.fields.getValue("reason").jsonPrimitive.content)
        val third = run.step()
        assertEquals(1, third.state("g-1/x", "rejected").int)
        assertEquals("executor_unable", third.state("g-1/x", "last_reason").content)
    }

    private fun change(tick: Long, target: String, name: String, value: Boolean) =
        ObservationChange(tick, "g-1/$target", buildJsonObject { put(name, value) })

    @Test fun aLostHousePowerConnectionReachesTheHouseItsApplianceAndItsResidentsWithTheNewObservation() {
        val run = run(mapOf("house" to house, "heater" to heater, "resident" to resident), ticks = 8, changes = listOf(
            change(3, "house", "power_connected", false), change(6, "house", "power_connected", true)))
        val snapshots = (0 until 8).map { run.step() }

        val lost = snapshots[3].eventsOf("PowerLost").single()
        assertEquals("g-1/house", lost.entityId)
        assertEquals(setOf("g-1/house", "g-1/heater", "g-1/resident"), lost.recipients.toSet())
        assertNull(lost.causationId, "a scenario input has no cause inside the world")
        assertEquals(0, snapshots[2].state("g-1/house", "power_lost").int)
        // The transition is into this snapshot: the event and the observation arrive in the same frame.
        assertEquals(1, snapshots[3].state("g-1/house", "power_lost").int)
        assertEquals(1, snapshots[3].state("g-1/heater", "power_lost").int)
        assertEquals(1, snapshots[3].state("g-1/resident", "power_lost").int)
        assertEquals(false, snapshots[3].state("g-1/heater", "connected").boolean, "an appliance follows its house")
        assertEquals(true, snapshots[2].state("g-1/heater", "connected").boolean)

        assertEquals(1, snapshots[6].eventsOf("PowerRestored").size)
        assertEquals(1, snapshots[6].state("g-1/house", "power_restored").int)
        assertEquals(true, snapshots[6].state("g-1/heater", "connected").boolean)
        assertEquals(1, snapshots[7].state("g-1/house", "power_lost").int, "no repeated events without a transition")
    }

    @Test fun anApplianceCableAloneAndTheWaterSupplyAreReportedToTheirOwnersOnly() {
        val run = run(mapOf("house" to house, "heater" to heater, "resident" to resident), ticks = 6, changes = listOf(
            change(2, "heater", "power_connected", false), change(4, "house", "water_available", false),
            // A change of tick 0 defines the initial state and is not an event.
            change(0, "house", "water_available", true)))
        val snapshots = (0 until 6).map { run.step() }
        assertEquals(emptyList(), snapshots[0].events)
        val cable = snapshots[2].eventsOf("PowerLost").single()
        assertEquals("g-1/heater", cable.entityId)
        assertEquals(listOf("g-1/heater"), cable.recipients)
        assertEquals(0, snapshots[2].state("g-1/house", "power_lost").int)
        val water = snapshots[4].eventsOf("WaterLost").single()
        assertEquals(listOf("g-1/house"), water.recipients)
        assertEquals(1, snapshots[4].state("g-1/house", "water_lost").int)
        assertEquals(0, snapshots[5].eventsOf("WaterLost").size)
    }
}
