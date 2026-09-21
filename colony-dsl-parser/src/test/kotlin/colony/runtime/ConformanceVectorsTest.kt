package colony.runtime

import colony.bytecode.bytecodeJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*

class ConformanceVectorsTest {
    private val committed = File("../conformance")
    private fun load(name: String) = bytecodeJson.decodeFromString<ConformanceFile>(committed.resolve("$name.json").readText())

    @Test fun theCommittedVectorsAreCurrent() {
        val temp = Files.createTempDirectory("conformance")
        writeConformance(Path.of("..").toAbsolutePath().normalize(), temp)
        for (name in conformanceFileNames()) {
            assertEquals(temp.resolve(name).readText(), committed.resolve(name).readText().replace("\r\n", "\n"),
                "conformance/$name is stale; regenerate: colony-dsl-parser conformance conformance (from the repository root)")
        }
    }

    @Test fun theReferenceVmReplaysEveryCommittedVector() {
        for (name in conformanceFileNames().filter { it != "prng.json" }.map { it.removeSuffix(".json") }) {
            assertEquals(emptyList(), verifyConformance(load(name)), name)
        }
        assertEquals(emptyList(), verifyPrng(bytecodeJson.decodeFromString<PrngFile>(committed.resolve("prng.json").readText())))
    }

    @Test fun aWrongExpectationIsReportedNotIgnored() {
        val file = load("kettle")
        val step = file.cases.single().steps.first()
        val wrongIntent = ConformanceIntent("POWER_REQUEST", listOf(JsonPrimitive(12345)))
        val tampered = file.copy(cases = listOf(file.cases.single().copy(steps = listOf(step.copy(expect = step.expect.copy(intents = listOf(wrongIntent)))))))
        assertEquals(1, verifyConformance(tampered).size)
        assertTrue(jsonEquivalent(JsonPrimitive(2), JsonPrimitive(2.0)), "numbers agree by value")
        assertFalse(jsonEquivalent(JsonPrimitive("2"), JsonPrimitive(2)), "a string is not a number")
        assertFalse(jsonEquivalent(JsonPrimitive(true), JsonPrimitive(1)))
    }

    @Test fun theVectorsActuallyExerciseRandomnessFailuresEventsAndOrdering() {
        val resident = load("resident").cases
        val vandalism = resident.map { case -> case.steps.any { step -> step.expect.intents.any { it.operation == "DAMAGE_REQUEST" } } }
        assertTrue(true in vandalism && false in vandalism, "some seeds vandalize and some do not")
        assertTrue(load("language").cases.single { it.name == "rollback" }.steps.count { it.expect.failure } == 2, "failed steps are part of the vectors")
        val receiver = load("language").cases.single { it.name == "receiver" }
        assertEquals(JsonPrimitive(5), receiver.steps[2].expect.state.getValue("last"), "the last event by (sender, sequence) wins, not the last delivered")
        val strikes = load("xenomorph").cases.map { case -> case.steps.filter { s -> s.expect.intents.any { it.operation == "DAMAGE_REQUEST" } }.map { it.tick } }
        assertTrue(strikes.toSet().size == strikes.size, "different seeds strike at different steps")
        assertTrue(load("heater").cases.flatMap { it.steps }.any { step -> step.expect.events.isNotEmpty() }, "outgoing events are recorded")
    }

    @Test fun theRandomVectorsCoverSeedsKeysAndCounters() {
        val vectors = bytecodeJson.decodeFromString<PrngFile>(committed.resolve("prng.json").readText()).vectors
        assertTrue(vectors.any { it.seed < 0 } && vectors.any { it.entityId.any { c -> c.code > 127 } } && vectors.any { it.site.isEmpty() } && vectors.any { it.counter > 1_000_000_000L })
        assertTrue(vectors.all { it.unit.toDouble() in 0.0..1.0 })
    }
}
