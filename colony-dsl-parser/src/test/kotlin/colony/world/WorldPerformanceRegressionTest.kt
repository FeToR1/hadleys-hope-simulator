package colony.world

import colony.bytecode.Op
import colony.runtime.ReferenceFleet
import colony.runtime.ReferenceRun
import colony.runtime.VmIntent
import colony.runtime.prepareScenario
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class WorldPerformanceRegressionTest {
    private fun prepared() = prepareScenario(Path.of("../examples/physics/cascade.json"))

    @Test fun lazyFieldsAreCachedAndUnresolvedFieldsCannotEscapeThePhase() {
        val prepared = prepared()
        val config = prepared.scenario.world!!
        val world = WorldKernel(prepared.manifest, config, buildTopology(prepared.manifest, config), 1.0)
        val house = prepared.manifest.instances.first { it.kind == "House" }
        world.beginObservationPhase()
        val view = JsonObject(world.lazyView(house, linkedSetOf("health", "devices", "temperature")))
        val devices = view.getValue("devices")
        assertSame(devices, view.getValue("devices"))
        val health = view.getValue("health")
        assertSame(health, view.getValue("health"))
        val materialized = JsonObject(view.toMap())
        assertEquals(JsonObject(world.view(house, view.keys)), materialized)
        assertEquals(materialized.hashCode(), view.hashCode())
        assertEquals(materialized.toString(), view.toString())
        val unresolved = world.lazyView(house, setOf("health"))
        world.endObservationPhase()
        val target = devices.jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        world.step(0, listOf(VmIntent(target, Op.DAMAGE_REQUEST,
            listOf(JsonPrimitive(target), JsonPrimitive(1000.0), JsonPrimitive("test")))), listOf("damage"), setOf(target))
        assertEquals(materialized, view)
        assertFalse(devices.jsonArray.first().jsonObject.getValue("broken").jsonPrimitive.boolean)
        assertFailsWith<IllegalStateException> { unresolved.getValue("health") }
    }

    @Test fun ledgerOverflowKeepsEveryCurrentTickPostingAndFullBillingTotals() {
        val prepared = prepared()
        val base = prepared.scenario.world!!
        val config = base.copy(climate = base.climate.copy(meanTemperature = 20.0, amplitude = 0.0),
            tariffs = base.tariffs.copy(electricityPerKwh = 3_600_000, waterPerCubicMetre = 0, billingIntervalSeconds = 1))
        val world = WorldKernel(prepared.manifest, config, buildTopology(prepared.manifest, config), 1.0)
        val heaters = prepared.manifest.instances.filter { it.kind == "Heater" }
        val intents = heaters.map { VmIntent(it.id, Op.POWER_REQUEST, listOf(JsonPrimitive(100.0))) }
        val accepted = heaters.mapTo(HashSet()) { it.id }
        val totals = HashMap<String, Long>()
        val posted = ArrayList<Posting>()
        repeat(4000) { tick ->
            world.step(tick.toLong(), intents, intents.map { "${it.source}@$tick" }, accepted)
            assertEquals(heaters.map { it.parent }.distinct().size, world.lastPostings.size, "tick=$tick")
            assertTrue(world.lastPostings.all { it.tick == tick.toLong() })
            for (posting in world.lastPostings) {
                totals.merge(posting.owner, posting.amount, Long::plus)
                posted += posting
            }
        }
        assertTrue(posted.size > 20_000)
        assertEquals(posted.takeLast(20_000), world.ledger)
        assertEquals(totals, world.monthlyReport())
        totals.forEach { (owner, total) -> assertEquals(total, world.spentBy(owner)) }
    }

    @Test fun networksRefreshAfterDamageRepairAndPipeFrost() {
        val prepared = prepared()
        val base = prepared.scenario.world!!
        val config = base.copy(repair = base.repair.copy(duration = mapOf("pole" to 1.0), dispatchDelaySeconds = 0.0))
        val originalTopology = buildTopology(prepared.manifest, config)
        val pole = originalTopology.poles.first { it.id != "grid/bus" }
        val rover = prepared.manifest.instances.first { it.kind == "Rover" }
        val manifest = prepared.manifest.copy(instances = prepared.manifest.instances.map {
            if (it.id == rover.id) it.copy(x = pole.at.x, y = pole.at.y) else it
        })
        val topology = buildTopology(manifest, config)
        val world = WorldKernel(manifest, config, topology, 1.0)
        val house = topology.houses.first { topology.powerFeed[it] == pole.id }
        assertTrue(world.isPowered(house))
        val broken = world.step(0, listOf(VmIntent(rover.id, Op.DAMAGE_REQUEST,
            listOf(JsonPrimitive(pole.id), JsonPrimitive(10000.0), JsonPrimitive("test")))), listOf("damage"), setOf(rover.id))
        assertFalse(world.isPowered(house))
        assertTrue(broken.any { it.type == "PowerLost" && it.entityId == house })
        val repaired = world.step(1, listOf(VmIntent(rover.id, Op.REPAIR_REQUEST, listOf(JsonPrimitive(pole.id)))), listOf("repair"), setOf(rover.id))
        assertTrue(world.isPowered(house))
        assertTrue(repaired.any { it.type == "PowerRestored" && it.entityId == house })
        val freezing = config.copy(house = config.house.copy(initialTemperature = -10.0),
            water = config.water.copy(freezeThresholdIndoor = 1.0))
        val frostWorld = WorldKernel(manifest, freezing, buildTopology(manifest, freezing), 1.0)
        assertTrue(frostWorld.hasWater(house))
        val frozen = frostWorld.step(0, emptyList(), emptyList(), emptySet())
        assertTrue(frostWorld.hasWater(house))
        assertTrue(frozen.any { it.type == "ObjectBroken" && it.entityId == topology.waterPipe.getValue(house) })
        assertFalse(frozen.any { it.type == "WaterLost" && it.entityId == house })
        val next = frostWorld.step(1, emptyList(), emptyList(), emptySet())
        assertFalse(frostWorld.hasWater(house))
        assertTrue(next.any { it.type == "WaterLost" && it.entityId == house })
    }

    @Test fun omittedSnapshotsPreserveAllTickFactsAndTheNextFullSnapshot() {
        val prepared = prepared()
        ReferenceRun(prepared, "same", ReferenceFleet(prepared, 1)).use { complete ->
            ReferenceRun(prepared, "same", ReferenceFleet(prepared, 4)).use { selective ->
                val retained = selective.step()
                assertEquals(complete.step(), retained)
                val retainedText = retained.toString()
                repeat(1798) {
                    val full = complete.step()
                    val facts = selective.step(captureSnapshot = false)
                    assertFalse(facts.full)
                    assertTrue(facts.entities.isEmpty())
                    assertTrue(facts.effects.isEmpty())
                    assertEquals(full.copy(full = false, entities = emptyList(), effects = emptyList()), facts)
                    assertEquals(complete.kernel!!.ledger, selective.kernel!!.ledger)
                    assertEquals(complete.kernel!!.monthlyReport(), selective.kernel!!.monthlyReport())
                }
                assertEquals(complete.step(), selective.step())
                assertEquals(retainedText, retained.toString())
            }
        }
    }

    @Test fun retainedSnapshotsStayImmutableAcrossWorkerCounts() {
        val prepared = prepared()
        ReferenceRun(prepared, "same", ReferenceFleet(prepared, 1)).use { serial ->
            ReferenceRun(prepared, "same", ReferenceFleet(prepared, 4)).use { parallel ->
                val first = serial.step()
                assertEquals(first, parallel.step())
                val saved = first.toString()
                repeat(180) { assertEquals(serial.step(), parallel.step()) }
                assertEquals(saved, first.toString())
            }
        }
    }
}
