package colony.runtime

import colony.parser.ColonyParserFacade
import colony.semantics.ConstantExpressions
import colony.semantics.ConstantValue
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SettingsLibrariesTest {
    private fun files(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("colony-libraries-")
        try { action(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun relativeSourcesAndDiamondIncludesKeepOneDeclaration() = files { directory ->
        Files.createDirectories(directory.resolve("lib")); Files.createDirectories(directory.resolve("code"))
        Files.copy(Path.of("../examples/settings/behavior.colony"), directory.resolve("code/behavior.colony"))
        Files.writeString(directory.resolve("lib/base.settings"), """
            settings Library {
                source "../code/behavior.colony";
                let demand = 2kW;
                template home {
                    object house using Home { param heater = "${'$'}instance/heater"; param target = 21degC; }
                    object heater using Heating { param demand = demand; parent = "${'$'}instance/house"; }
                }
            }
        """.trimIndent())
        for (name in listOf("left", "right")) Files.writeString(directory.resolve("$name.settings"),
            "settings { include \"lib/base.settings\"; }")
        val root = directory.resolve("colony.settings")
        Files.writeString(root, """
            settings { include "left.settings"; include "right.settings";
                population home as home { count = 2; } world {} }
        """.trimIndent())
        val prepared = prepareScenario(root)
        assertEquals(1, prepared.sources.size)
        assertEquals(4, prepared.manifest.instances.size)
        assertEquals(2000.0, prepared.manifest.instances.first { it.kind == "Heater" }.params.getValue("demand").jsonPrimitive.double)
    }

    @Test fun cyclesDuplicateIncludesAndRunPropertiesInLibrariesAreRejected() = files { directory ->
        val root = directory.resolve("colony.settings")
        Files.writeString(root, "settings { include \"a.settings\"; }")
        Files.writeString(directory.resolve("a.settings"), "settings { include \"b.settings\"; }")
        Files.writeString(directory.resolve("b.settings"), "settings { include \"a.settings\"; }")
        assertContains(assertFailsWith<IllegalArgumentException> { prepareScenario(root) }.message.orEmpty(), "include cycle")
        Files.writeString(root, "settings { include \"a.settings\"; include \"a.settings\"; }")
        assertContains(assertFailsWith<IllegalArgumentException> { prepareScenario(root) }.message.orEmpty(), "duplicate include")
        Files.writeString(root, "settings { include \"a.settings\"; }")
        Files.writeString(directory.resolve("a.settings"), "settings { ticks = 10; }")
        assertContains(assertFailsWith<IllegalArgumentException> { prepareScenario(root) }.message.orEmpty(), "included settings")
    }

    @Test fun typedRecordConstantsShareColonySchemasAndUnits() {
        val ast = ColonyParserFacade().parseSettings("""
            settings {
                let point: Position = Position { x: -2m, y: 1km };
                let target = Target { id: "home-1/house", position: point };
            }
        """.trimIndent())
        val expressions = ConstantExpressions()
        ast.constants.forEach(expressions::declare)
        val record = expressions.evaluate(ast.constants[1].value).value as ConstantValue.Record
        val point = record.fields.getValue("position").value as ConstantValue.Record
        assertEquals("1000", (point.fields.getValue("y").value as ConstantValue.Decimal).value.stripTrailingZeros().toPlainString())
        for (text in listOf("Position { x: 2kW, y: 1m }", "Position { x: 1m }", "Position { x: 1m, x: 2m, y: 1m }",
            "Target { kind: \"House\" }", "Position { x: view.temperature, y: 1m }")) {
            val document = ColonyParserFacade().parseSettings("settings { let bad = $text; }")
            assertFails(text) { ConstantExpressions().declare(document.constants.single()) }
        }
    }

    @Test fun scheduledObservationsValidateTargetsTypesAndWorldBoundary() = files { directory ->
        val library = Path.of("../examples/integration/catalog.settings").toAbsolutePath().normalize().toString().replace('\\', '/')
        val root = directory.resolve("colony.settings")
        val source = """
            settings { include "$library"; ticks = 4;
                population household as home { count = 1; }
                population predator as predator { count = 1; }
                at 2 for "predator-1/predator" {
                    visible_infrastructure = [Target { id: "home-1/heater" }];
                    patrol_waypoint = Position { x: 1km, y: -2m };
                }
            }
        """.trimIndent()
        Files.writeString(root, source)
        val prepared = prepareScenario(root)
        assertEquals(2, prepared.scenario.changes.single().tick)
        assertEquals(-2.0, prepared.scenario.changes.single().view.getValue("patrol_waypoint").jsonObject.getValue("y").jsonPrimitive.double)
        for ((from, to) in listOf("at 2" to "at 4", "at 2" to "at 2s", "predator-1/predator" to "predator-2/predator",
            "x: 1km" to "x: 1kW", "home-1/heater\" }" to "missing\" }", "ticks = 4;" to "ticks = 4; world {}")) {
            Files.writeString(root, source.replace(from, to))
            assertFailsWith<IllegalArgumentException>(to) { prepareScenario(root) }
        }
    }

    @Test fun jsonScenarioInputIsRejected() {
        assertContains(assertFailsWith<IllegalArgumentException> { prepareScenario(Path.of("scenario.json")) }.message.orEmpty(), ".settings")
    }
}
