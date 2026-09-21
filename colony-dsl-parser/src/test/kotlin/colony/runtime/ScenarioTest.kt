package colony.runtime

import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class ScenarioTest {
    @Test fun catalogExpansionAndReplayHaveStableResults() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
        assertEquals(9, prepared.manifest.instances.size)
        assertEquals(JsonPrimitive("home-1/heater"), prepared.manifest.instances.single { it.id == "home-1/house" }.params["heater"])
        val first = ReferenceRun(prepared, "test")
        val second = ReferenceRun(prepared, "test")
        val frames = (0..16).map { first.step().also { frame -> assertEquals(frame, second.step()) } }
        fun power(tick: Int, id: String) = frames[tick].entities.single { it.id == id }.metrics.getValue("power_consumption").jsonPrimitive.double
        assertEquals(0.0, power(0, "home-1/heater"))
        assertEquals(2000.0, power(1, "home-1/heater"))
        assertEquals(0.0, power(5, "home-1/heater"))
        assertEquals(0.0, power(15, "home-2/kettle"))
        assertEquals(2, frames[1].deliveredEvents)
    }

    @Test fun threeHundredHousesHaveOneVmForEveryObject() {
        val prepared = prepareScenario(Path.of("../examples/integration/colony-300.json"))
        assertEquals(1208, prepared.manifest.instances.size)
        val run = ReferenceRun(prepared)
        assertEquals(1208, run.step().entities.size)
        assertEquals(300, run.step().deliveredEvents)
    }

    @Test fun invalidReferencesAndParametersFailBeforeStarting() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
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
}
