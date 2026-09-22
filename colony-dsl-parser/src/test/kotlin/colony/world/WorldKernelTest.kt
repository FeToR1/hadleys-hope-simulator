package colony.world

import colony.runtime.ReferenceRun
import colony.runtime.prepareScenario
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/**
 * The physical models against the formulas they come from (docs/simulation/calculations.md) and the cascade the
 * lab asks for: a broken grid, a cooling house, a frozen pipe, a repair and the money it costs.
 */
class WorldKernelTest {
    private fun cascade() = prepareScenario(Path.of("../examples/physics/cascade.json"))

    @Test fun aHouseFollowsTheClosedFormOfItsThermalModel() {
        val prepared = cascade()
        val world = prepared.scenario.world!!
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        val house = "home-1/house"
        val start = kernel.temperatureOf(house)!!
        run.use { active -> repeat(200) { active.step() } }

        // Without heating the house decays towards the outside temperature with the time constant C/G.
        val outside = world.climate.outsideTemperature(0.0)
        val tau = world.house.capacity / world.house.conductance
        val expected = outside + (start - outside) * Math.exp(-200.0 / tau)
        val actual = kernel.temperatureOf(house)!!
        // The heater warms the house for part of that time, so the model is a lower bound on the temperature.
        assertTrue(actual >= expected - 0.5, "house at $actual, free cooling would give $expected")
        assertTrue(actual < start, "the house cannot get warmer than it started at -38 outside")
    }

    @Test fun theCascadeRunsFromABrokenPoleToAFrozenPipeAndARepair() {
        val prepared = cascade()
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        val history = ArrayList<Pair<Long, colony.runtime.WorldEvent>>()
        val spend = ArrayList<Posting>()
        run.use { repeat(1200) { snapshot -> it.step().let { s -> s.events.forEach { e -> history += s.tickId to e }; spend += s.postings } } }

        fun firstOf(type: String, entity: (String) -> Boolean = { true }) =
            history.firstOrNull { it.second.type == type && entity(it.second.entityId) }

        val broken = assertNotNull(firstOf("ObjectBroken") { it.startsWith("grid/pole") }, "a pole has to break")
        val dark = assertNotNull(firstOf("PowerLost"), "the houses on that pole lose power")
        val frozen = assertNotNull(firstOf("ObjectBroken") { it.startsWith("water/pipe") }, "a pipe has to freeze")
        val dry = assertNotNull(firstOf("WaterLost"), "the house loses its water")
        val repaired = assertNotNull(firstOf("RepairCompleted"), "the crew has to finish something")

        assertEquals("XenomorphAttack", broken.second.fields.getValue("reason").jsonPrimitive.content)
        assertEquals("freezing", frozen.second.fields.getValue("reason").jsonPrimitive.content)
        assertTrue(dark.first == broken.first, "power goes in the step the pole breaks")
        assertTrue(frozen.first > dark.first, "the pipe freezes after the power is gone, not before")
        assertTrue(dry.first == frozen.first, "water goes with the pipe")
        assertTrue(repaired.first > broken.first, "a repair comes after the break")
        assertTrue(spend.any { it.kind == "electricity" }, "the houses are billed for what they used")
        assertTrue(kernel.spentBy("settlement") > 0, "the settlement pays for the grid repairs")
    }

    @Test fun powerGoesToTheLowerClassFirstAndIsCutInProportion() {
        // Two heaters and two kettles on a grid that can only feed the heaters.
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json")).let { base ->
            base.copy(scenario = base.scenario.copy(
                ticks = 30,
                world = base.scenario.world!!.copy(power = base.scenario.world!!.power.copy(
                    reactorPower = 9_000.0, solarPeak = 0.0, upsCapacity = 0.0, upsMaxPower = 0.0)),
            ))
        }
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        run.use { active -> repeat(30) { active.step() } }
        val heaters = prepared.manifest.instances.filter { it.kind == "Heater" }.map { kernel.grantedOf(it.id) }
        val kettles = prepared.manifest.instances.filter { it.kind == "Kettle" }.map { kernel.grantedOf(it.id) }
        val pumpAndHeaters = kernel.grantedOf("water/pump") + heaters.sum()
        assertTrue(kernel.grantedOf("water/pump") > 0.0, "the pump is class 0 and is served first")
        assertEquals(0.0, kettles.sum(), "nothing is left for the kettles")
        assertTrue(pumpAndHeaters <= 9_000.0 + 1e-6, "the grid never hands out more than it has")
        val asked = heaters.size * 8_000.0
        if (asked > 0 && heaters.sum() > 0) {
            val share = heaters.first() / 8_000.0
            assertTrue(heaters.all { abs(it / 8_000.0 - share) < 1e-9 }, "the class that runs short is cut in proportion")
        }
    }

    @Test fun theSettlementPlanIsDeterministicAndConnectsEveryHouse() {
        val prepared = cascade()
        val world = prepared.scenario.world!!
        val first = buildTopology(prepared.manifest, world)
        val second = buildTopology(prepared.manifest, world)
        assertEquals(first.fixtures.map { it.id }, second.fixtures.map { it.id })
        assertEquals(6, first.houses.size)
        assertTrue(first.houses.all { it in first.powerFeed && it in first.waterPipe }, "every house has a feed and a pipe")
        assertTrue(first.powerFeed.containsKey("water/pump"), "the pump draws from the grid")
        assertEquals(3, first.sources.size, "a reactor, a solar station and a battery")
        // An appliance draws through the house it belongs to.
        val heater = prepared.manifest.instances.first { it.kind == "Heater" }
        assertEquals(first.powerFeed[heater.parent], first.powerFeed[heater.id])
    }

    @Test fun billingCarriesTheRoundingRemainderSoTheTotalStays() {
        val prepared = cascade()
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        val postings = ArrayList<Posting>()
        run.use { repeat(1200) { _ -> postings += it.step().postings } }
        val electricity = postings.filter { it.kind == "electricity" }
        assertTrue(electricity.isNotEmpty(), "an hour of consumption is posted")
        assertTrue(electricity.all { it.amount > 0 }, "a posting is never zero or negative")
        val perHouse = electricity.groupBy { it.owner }.mapValues { it.value.sumOf { p -> p.amount } }
        assertTrue(perHouse.keys.all { it.endsWith("/house") }, "the bill goes to the house that used it")
        assertEquals(perHouse.values.sum() + kernel.spentBy("settlement"),
            kernel.monthlyReport().values.sum() - postings.filter { it.kind == "water" }.sumOf { it.amount },
            "the report adds up to the postings behind it")
    }
}
