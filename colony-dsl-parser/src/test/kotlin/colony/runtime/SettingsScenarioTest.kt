package colony.runtime

import colony.compiler.SourceFile
import colony.compiler.compileSources
import colony.parser.ColonyParserFacade
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SettingsScenarioTest {
    private val example = Path.of("../examples/settings/colony.settings")

    private fun prepare(text: String): PreparedRun {
        val directory = Files.createTempDirectory("colony-settings-test-")
        try {
            Files.copy(example.parent.resolve("behavior.colony"), directory.resolve("behavior.colony"))
            val file = directory.resolve("colony.settings")
            Files.writeString(file, text)
            return prepareScenario(file)
        } finally {
            listOf("colony.settings", "behavior.colony").forEach { Files.deleteIfExists(directory.resolve(it)) }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun settingsBindTypedParametersAndExpandACompactGrid() {
        val prepared = prepareScenario(example)
        assertEquals(160, prepared.manifest.instances.size)
        val heater = prepared.manifest.instances.single { it.id == "home-12/heater" }
        assertEquals(2000.0, heater.params.getValue("demand").jsonPrimitive.double)
        assertEquals(72.0, heater.x); assertEquals(70.0, heater.y)
        assertEquals("home-12/house", heater.parent)
        assertEquals("home-12/heater", prepared.manifest.instances.single { it.id == "home-12/house" }.params.getValue("heater").jsonPrimitive.content)
        assertEquals(180.0, prepared.scenario.world!!.sea.fogPeriodSeconds)
        assertEquals(19.0, prepared.scenario.world.climate.meanTemperature)
        assertContentEquals(colony.cvm.ArtifactWriter.write(compileSources(prepared.sources).nativeProgram),
            colony.cvm.ArtifactWriter.write(prepared.program.nativeProgram))
    }

    @Test fun mainSettingsPreserveThe5000Planet() {
        val settings = prepareScenario(Path.of("../examples/physics/colony.settings"))
        assertEquals(5000, settings.manifest.instances.count { it.kind == "House" })
        assertEquals(20224, settings.manifest.instances.size)
        assertEquals(86400, settings.scenario.ticks)
        assertEquals(19.0, settings.scenario.world!!.climate.meanTemperature)
        assertEquals(8000.0, settings.manifest.instances.first { it.kind == "Heater" }.params.getValue("rating").jsonPrimitive.double)
    }

    @Test fun sharedExpressionsNormalizeUnitsAndPermitNegativeCoordinates() {
        val source = Files.readString(example).replace("let houses = 80;", "let houses: Int64 = 2 * (3 + 1);")
            .replace("2kW;", "(1kW + 500W) * 2;").replace("x = 2m;", "x = -2m;")
        val prepared = prepare(source)
        assertEquals(16, prepared.manifest.instances.size)
        assertEquals(3000.0, prepared.manifest.instances.first { it.kind == "Heater" }.params.getValue("demand").jsonPrimitive.double)
        assertEquals(-2.0, prepared.manifest.instances.first { it.kind == "Heater" }.x)
    }

    @Test fun settingsUseCvmFloatingPointRoundingAndExactMixedComparisons() {
        val source = Files.readString(example).replace("amplitude = 4degC;", "amplitude = 4degC; weatherFactor = 0.1 + 0.2;")
            .replace("meanTemperature = 19degC", "meanTemperature = -0degC")
            .replace("enabled = true", "enabled = (-9223372036854775807 - 1) == -9223372036854775808.0")
        val world = prepare(source).scenario.world!!
        assertEquals(0.1 + 0.2, world.climate.weatherFactor)
        assertEquals(Long.MIN_VALUE, java.lang.Double.doubleToRawLongBits(world.climate.meanTemperature))
        assertTrue(world.sea.enabled, "Int64 minimum is exactly equal to its binary floating point representation")
    }

    @Test fun settingsRunThroughTheNativeFleetAndWorld() {
        val prepared = prepare(Files.readString(example).replace("houses = 80", "houses = 2"))
        SimulationRun(prepared, "settings").use { run ->
            val first = run.step(); val second = run.step()
            assertEquals("process", first.runtimeMode)
            assertEquals(2, first.effects.count { it.operation == "POWER_REQUEST" })
            assertEquals(2, second.deliveredEvents)
            assertTrue(second.effects.any { it.arguments.firstOrNull()?.jsonPrimitive?.doubleOrNull == 2000.0 })
        }
    }

    @Test fun badUnitsBindingsNamesAndGridAreRejected() {
        val source = Files.readString(example)
        for ((from, to) in listOf("param demand = heaterPower" to "param demand = 20degC",
            "param demand = heaterPower" to "param demand = [heaterPower]",
            "param target = 21degC" to "param target = 21",
            "param heater = \"\$instance/heater\"" to "param heater = \"\$instance/house\"",
            "meanTemperature = 19degC" to "meanTemperature = 2kW",
            "columns = 10" to "columns = 0", "count = houses" to "count = -1",
            "reactorPower = 1e3kW" to "unknownSetting = 1",
            "param demand = heaterPower" to "param absent = heaterPower",
            "seed = 426;" to "seed = 426; seed = 1;",
            "let houses = 80;" to "let houses = 80; let houses = 2;",
            "let houses = 80;" to "let houses = 9223372036854775807 + 1;",
            "param target = 21degC" to "param target = view.temperature",
            "param target = 21degC" to "param target = 21unknown")) {
            val failure = assertFailsWith<IllegalArgumentException>("accepted $to") { prepare(source.replace(from, to)) }
            assertContains(failure.message.orEmpty(), "colony.settings")
        }
    }

    @Test fun newWordsRemainIdentifiersInBehaviorPrograms() {
        val ast = ColonyParserFacade().parse("event Marker { object: Int64; source: Bool; include: Bool; at: Int64; } behavior template for House { state settings: Int64 = 0; }")
        assertEquals(2, ast.declarations.size)
    }
}
