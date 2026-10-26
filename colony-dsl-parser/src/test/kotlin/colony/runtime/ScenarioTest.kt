package colony.runtime

import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class ScenarioTest {
    @Test fun fiveThousandHousesPreserveTheFullWorldConfiguration() {
        val base = prepareScenario(Path.of("../examples/physics/full.settings"))
        val large = prepareScenario(Path.of("../examples/physics/full-5000.settings"))
        assertEquals(5000, large.manifest.instances.count { it.kind == "House" })
        assertEquals(20028, large.manifest.instances.size)
        assertEquals(base.scenario.world, large.scenario.world)
        assertEquals(base.scenario.seed, large.scenario.seed)
        assertEquals(base.scenario.step, large.scenario.step)
        assertEquals(base.scenario.ticks, large.scenario.ticks)
        assertEquals(base.scenario.populations.filter { it.template != "household" },
            large.scenario.populations.filter { it.template != "household" })
    }

    @Test fun klyaksaScenariosUseLocalGroundThreats() {
        for (name in listOf("ecosystem-small.settings", "colony.settings", "full.settings", "full-5000.settings", "cascade.settings")) {
            val prepared = prepareScenario(Path.of("../examples/physics/$name"))
            assertTrue(prepared.manifest.instances.any { it.kind == "Predator" }, name)
            assertTrue(prepared.manifest.instances.all { it.kind in colony.semantics.SemanticEnvironment().kindContracts }, name)
        }
    }

    @Test fun aMonthAtOneSecondStepsPassesScenarioValidation() {
        val directory = java.nio.file.Files.createTempDirectory("colony-month-")
        val scenario = directory.resolve("month.settings")
        try {
            val catalog = Path.of("../examples/physics/catalog.settings").toAbsolutePath().normalize()
            val text = java.nio.file.Files.readString(Path.of("../examples/physics/full.settings"))
                .replace("include \"catalog.settings\";", "include \"${catalog.toString().replace('\\', '/')}\";")
                .replace(Regex("ticks = [0-9]+"), "ticks = 2592000")
            java.nio.file.Files.writeString(scenario, text)
            assertEquals(2_592_000, prepareScenario(scenario).scenario.ticks)
        } finally {
            java.nio.file.Files.deleteIfExists(scenario)
            java.nio.file.Files.deleteIfExists(directory)
        }
    }

    @Test fun catalogExpansionAndReplayHaveStableResults() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.settings"))
        assertEquals(9, prepared.manifest.instances.size)
        assertEquals(JsonPrimitive("home-1/heater"), prepared.manifest.instances.single { it.id == "home-1/house" }.params["heater"])
        val first = SimulationRun(prepared, "test")
        val second = SimulationRun(prepared, "test")
        val frames = (0..16).map { first.step().also { frame -> assertEquals(frame.withoutPids(), second.step().withoutPids()) } }
        fun power(tick: Int, id: String) = frames[tick].entities.single { it.id == id }.metrics.getValue("power_consumption").jsonPrimitive.double
        assertEquals(0.0, power(0, "home-1/heater"))
        assertEquals(2000.0, power(1, "home-1/heater"))
        assertEquals(0.0, power(5, "home-1/heater"))
        assertEquals(0.0, power(15, "home-2/kettle"))
        assertEquals(2, frames[1].deliveredEvents)
    }

    @Test fun threeHundredHousesHaveOneVmForEveryObject() {
        val prepared = prepareScenario(Path.of("../examples/integration/colony-300.settings"))
        assertEquals(1208, prepared.manifest.instances.size)
        val run = SimulationRun(prepared)
        assertEquals(1208, run.step().entities.size)
        assertEquals(300, run.step().deliveredEvents)
    }

    @Test fun invalidReferencesAndParametersFailBeforeStarting() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.settings"))
        val badTemplate = Template(mapOf("house" to ObjectSpec("House", "HouseControl",
            params = buildJsonObject { put("heater", "missing") },
            view = buildJsonObject { put("occupants", 1); put("temperature", 16) })))
        val catalog = Catalog(sources = listOf("unused"), templates = mapOf("bad" to badTemplate))
        val scenario = prepared.scenario.copy(populations = listOf(Population("bad", 1, "bad")), changes = emptyList())
        assertFailsWith<IllegalArgumentException> { expandScenario(scenario.copy(populations = listOf(Population("bad", -1, "bad"))), catalog, prepared.program) }
        assertFails { expandScenario(scenario, catalog, prepared.program) }
        val badParams = badTemplate.copy(objects = badTemplate.objects.mapValues { it.value.copy(params = JsonObject(emptyMap())) })
        assertFailsWith<IllegalArgumentException> { expandScenario(scenario, catalog.copy(templates = mapOf("bad" to badParams)), prepared.program) }
    }

    @Test fun aPowerOutageInTheDemoScenarioReachesTheHouseAndItsAppliances() {
        val run = SimulationRun(prepareScenario(Path.of("../examples/integration/small.settings")), "test")
        val frames = (0..46).map { run.step() }
        fun power(tick: Int) = frames[tick].entities.single { it.id == "home-2/heater" }.metrics.getValue("power_consumption").jsonPrimitive.double
        fun stress(tick: Int) = frames[tick].entities.single { it.id == "home-2/resident" }.vmState.getValue("stress").jsonPrimitive.double
        assertEquals(2000.0, power(19), "the heater draws power while the house is connected")
        assertEquals(0.0, power(20), "the heater sees power_connected = false in the same frame")
        // docs/simulation/trigger-conditions.md, section 3: residents are not recipients of a house power event.
        assertEquals(0.0, stress(20) - stress(19), 1e-9, "the resident does not hear about the house power")
        assertEquals(2000.0, power(46), "and draws again after PowerRestored at tick 45")
        val lost = frames[20].events.single { it.type == "PowerLost" }
        assertEquals("home-2/house", lost.entityId)
        assertContains(lost.recipients, "home-2/heater")
        assertFalse(lost.recipients.contains("home-2/resident"))
        assertEquals(1, frames.flatMap { it.events }.count { it.type == "PowerRestored" })
    }
}
