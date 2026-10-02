package colony.world

import colony.runtime.ReferenceRun
import colony.runtime.prepareScenario
import colony.semantics.SemanticEnvironment
import colony.semantics.Type
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/**
 * The physical models against the formulas they come from (docs/technical-reference.md#world) and the cascade the
 * lab asks for: a broken grid, a cooling house, a frozen pipe, a repair and the money it costs.
 */
class WorldKernelTest {
    private fun cascade() = prepareScenario(Path.of("../examples/physics/cascade.json"))

    @Test fun aContractObservationTheWorldCannotComputeStopsTheRunBeforeTheFirstTick() {
        // docs/simulation/calculations.md, section 1: a mismatch is a binding error, not a mid-tick crash.
        val prepared = cascade()
        val house = SemanticEnvironment().kindContracts.getValue("House")
        val broken = SemanticEnvironment().kindContracts +
            ("House" to house.copy(viewFields = house.viewFields + ("telepathy" to Type.Bool)))
        val failure = assertFailsWith<IllegalStateException> { ReferenceRun(prepared, "test", kindContracts = broken) }
        assertTrue(failure.message!!.contains("telepathy"), failure.message)
    }

    @Test fun theDefaultContractIsFullyComputableByTheWorld() {
        // The guard for contract/kernel drift: constructing the run validates every declared observation.
        cascade().let { prepared -> ReferenceRun(prepared, "test").use { } }
    }

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
        // docs/simulation/trigger-conditions.md, section 3: ObjectBroken reaches the owner and every crew,
        // so a crew can react to the event instead of polling active_jobs.
        val crews = prepared.manifest.instances.filter { it.kind == "Rover" }.map { it.id }
        assertTrue(crews.isNotEmpty() && crews.all { it in broken.second.recipients },
            "every crew hears about the pole break")
        val pipeHouse = kernel.topology.waterPipe.entries.first { it.value == frozen.second.entityId }.key
        assertTrue(pipeHouse in frozen.second.recipients, "the served house hears about its frozen pipe")
        assertTrue(dark.first == broken.first, "power goes in the step the pole breaks")
        // docs/simulation/trigger-conditions.md, section 3: a house power event reaches the house and its appliances.
        val humans = prepared.manifest.instances.filter { it.kind == "Human" }.map { it.id }
        val appliances = prepared.manifest.instances.filter { it.kind == "Heater" || it.kind == "Kettle" }.map { it.id }
        assertTrue(appliances.any { it in dark.second.recipients }, "the appliances of the darkened houses hear about the power loss")
        assertTrue(humans.none { it in dark.second.recipients }, "residents do not receive the house power event")
        assertTrue(frozen.first > dark.first, "the pipe freezes after the power is gone, not before")
        // docs/simulation/calculations.md, section 2: freezing is found in phase 4, so the water goes only with the next step.
        assertTrue(dry.first == frozen.first + 1, "water is lost the step after the pipe freezes, not with it")
        assertTrue(repaired.first > broken.first, "a repair comes after the break")
        // docs/simulation/trigger-conditions.md, section 8: the journal chains the attack to both breaks, the
        // losses of power and water, and the repairs that end them.
        assertEquals(broken.second.id, dark.second.causationId, "the power loss chains to the pole break")
        val frozenCause = frozen.second.causationId
        assertTrue(frozenCause != null && history.any { it.second.id == frozenCause && it.second.type == "PowerLost" },
            "the frozen pipe chains to the power loss of the house it serves")
        assertEquals(frozen.second.id, dry.second.causationId, "the water loss chains to the frozen pipe")
        val poleRepair = assertNotNull(firstOf("RepairCompleted") { it.startsWith("grid/pole") }, "the crew repairs the pole")
        assertEquals(broken.second.id, poleRepair.second.causationId, "the repair chains to the break that opened the job")
        val restored = assertNotNull(firstOf("PowerRestored"), "power comes back")
        assertEquals(poleRepair.second.id, restored.second.causationId, "the restoration chains to the repair")
        val pipeRepair = assertNotNull(firstOf("RepairCompleted") { it.startsWith("water/pipe") }, "the crew repairs the pipe")
        assertEquals(frozen.second.id, pipeRepair.second.causationId, "the pipe repair chains to the freeze")
        assertTrue(spend.any { it.kind == "electricity" }, "the houses are billed for what they used")
        assertTrue(kernel.spentBy("settlement") > 0, "the settlement pays for the grid repairs")
    }

    @Test fun powerGoesToTheLowerClassFirstAndIsCutInProportion() {
        // Two heaters and two kettles on a grid that can only feed the heaters.
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json")).let { base ->
            base.copy(scenario = base.scenario.copy(
                ticks = 30,
                world = base.scenario.world!!.copy(power = base.scenario.world!!.power.copy(
                    reactorPower = 9_000.0, solarPeak = 0.0, upsCapacity = 0.0, upsInitialCharge = 0.0, upsMaxPower = 0.0)),
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

    @Test fun anExhaustedUpsStopsBridgingAndTheHousesLosePower() {
        // docs/simulation/trigger-conditions.md, section 3: "ИБП разрядился после потери сети" is a PowerLost.
        // With both sources dead the battery bridges the bus, drains, and then the houses really lose power.
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json")).let { base ->
            base.copy(scenario = base.scenario.copy(
                ticks = 60,
                world = base.scenario.world!!.copy(power = base.scenario.world!!.power.copy(
                    reactorPower = 0.0, solarPeak = 0.0, upsCapacity = 1.8e6, upsInitialCharge = 1.8e6)),
            ))
        }
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        run.use {
            // The pump is the kernel's own class-0 consumer: while the battery bridges the dead sources,
            // the settlement keeps receiving power through it.
            repeat(4) { _ -> it.step() }
            assertTrue(kernel.grantedOf("water/pump") > 0.0, "the battery feeds the settlement while it holds charge")
            val lost = (4 until 60).map { tick -> tick to it.step() }
                .firstOrNull { (_, snapshot) -> snapshot.events.any { e -> e.type == "PowerLost" } }
            val dark = assertNotNull(lost, "an exhausted battery ends the bridging with a PowerLost")
            assertTrue(dark.first > 3, "the battery bridged the dead sources for several steps first")
            assertFalse(kernel.isPowered("home-1/house"), "the house is really disconnected after the battery dies")
        }
    }

    @Test fun theUpsChargesAsAClassZeroConsumerWhileHeatersWait() {
        // docs/simulation/calculations.md, section 4.3 and the section 11 resolution: charging is priority class 0,
        // so a starving grid fills the battery before it feeds the heaters.
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json")).let { base ->
            base.copy(scenario = base.scenario.copy(
                ticks = 10,
                world = base.scenario.world!!.copy(power = base.scenario.world!!.power.copy(
                    reactorPower = 100_000.0, solarPeak = 0.0, upsCapacity = 1.8e9, upsInitialCharge = 0.0)),
            ))
        }
        val run = ReferenceRun(prepared, "test")
        val kernel = assertNotNull(run.kernel)
        run.use {
            val snapshot = it.step()
            assertTrue(kernel.grantedOf("grid/ups") > 0.0, "the battery receives charge power")
            assertTrue(prepared.manifest.instances.filter { h -> h.kind == "Heater" }
                .all { h -> kernel.grantedOf(h.id) == 0.0 }, "charging beats heating when the grid runs short")
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
        // docs/simulation/calculations.md, section 10.4: the reports add up to the postings behind them. Every
        // owner's monthly total is exactly the sum of the captured postings, repairs included, water included.
        val byOwner = postings.groupBy { it.owner }.mapValues { (_, own) -> own.sumOf { p -> p.amount } }
        assertEquals(byOwner, kernel.monthlyReport(), "the report adds up to the postings behind it")
        // An indoor pipe belongs to the house it serves, so the house pays for its repair (section 6.2 and 10.3).
        assertTrue(postings.any { it.kind == "repair" && it.owner.endsWith("/house") },
            "the house pays for the repair of its own pipe")
        assertTrue(kernel.spentBy("settlement") > 0, "the settlement pays for the grid repairs")
    }
}
