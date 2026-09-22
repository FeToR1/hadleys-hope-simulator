package colony.cvm

import colony.bytecode.SourceFile
import colony.bytecode.bytecodeJson
import colony.runtime.ConformanceFile
import colony.runtime.jsonEquivalent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The native VM against the reference: the same programs, the same frames, and every intent, message and state
 * value compared. The vectors come from the Kotlin runtime, so agreement here means two independent
 * implementations of the language, through two different back ends, do the same thing.
 */
class NativeConformanceTest {
    private val executable = NativeVmDriver.executable()

    private fun artifactOf(source: String, into: Path): Pair<Program, Path> {
        val program = compileToCvm(listOf(SourceFile("package.colony", source)))
        val path = into.resolve("program.cvm")
        Files.write(path, ArtifactWriter.write(program))
        return program to path
    }

    private fun vectors(name: String): ConformanceFile =
        bytecodeJson.decodeFromString(File("../conformance/$name.json").readText())

    /** Sessions land here so another build, for instance one with sanitizers, can replay the same bytes. */
    private val sessions = File("build/sessions").apply { mkdirs() }

    private fun runFile(name: String, source: String): Int {
        val directory = Files.createTempDirectory("cvm-$name")
        val (program, artifact) = artifactOf(source, directory)
        Files.copy(artifact, sessions.toPath().resolve("$name.cvm"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val file = vectors(name)
        var steps = 0
        for (case in file.cases) {
            val record = File(sessions, "$name.${case.name}.session").outputStream().buffered()
            record.use {
            NativeVmDriver(executable!!, artifact, program, case.behavior, case.entityId, case.seed, case.params, it).use { vm ->
                assertTrue(jsonEquivalent(vm.initialState, case.initialState),
                    "$name/${case.name}: state after initialization is ${vm.initialState}, expected ${case.initialState}")
                for (step in case.steps) {
                    val events = step.events.map { event ->
                        buildJsonObject {
                            put("eventId", event.eventId); put("sender", event.sender)
                            put("sequence", event.sequence); put("fields", event.fields)
                        }
                    }
                    val outcome = vm.step(step.tick, step.view, events)
                    val where = "$name/${case.name} tick ${step.tick}"
                    if (step.expect.failure) {
                        assertNotNull(outcome.failure, "$where: the step should have failed")
                    } else {
                        assertNull(outcome.failure, "$where: the step failed with ${outcome.failure}")
                        val expectedIntents = step.expect.intents.map {
                            buildJsonObject { put("operation", it.operation); put("arguments", JsonArray(it.arguments)) }
                        }
                        assertTrue(jsonEquivalent(JsonArray(outcome.intents), JsonArray(expectedIntents)),
                            "$where: intents are ${outcome.intents}, expected $expectedIntents")
                        val expectedEvents = step.expect.events.map {
                            buildJsonObject {
                                put("target", it.target); put("eventId", it.eventId)
                                put("sequence", it.sequence); put("fields", it.fields)
                            }
                        }
                        assertTrue(jsonEquivalent(JsonArray(outcome.events), JsonArray(expectedEvents)),
                            "$where: messages are ${outcome.events}, expected $expectedEvents")
                    }
                    assertTrue(jsonEquivalent(outcome.state, step.expect.state),
                        "$where: state is ${outcome.state}, expected ${step.expect.state}")
                    steps++
                }
            }
            }
        }
        return steps
    }

    private fun source(vararg paths: String) = paths.joinToString("\n") { File(it).readText() }

    @Test fun theNativeVmIsBuilt() {
        assertNotNull(executable, "build it first: cmake -S native -B native/build && cmake --build native/build")
    }

    @Test fun heater() {
        if (executable == null) return
        assertEquals(20, runFile("heater", source("examples/heater.colony")))
    }

    @Test fun kettle() {
        if (executable == null) return
        assertEquals(7, runFile("kettle", source("../examples/integration/devices.colony")))
    }

    @Test fun residentWithItsRandomDraws() {
        if (executable == null) return
        assertEquals(378, runFile("resident", source("examples/resident.colony")))
    }

    @Test fun xenomorphWithHazardAndMovement() {
        if (executable == null) return
        assertEquals(60, runFile("xenomorph", source("examples/xenomorph.colony")))
    }

    @Test fun everyLanguageFeature() {
        if (executable == null) return
        assertEquals(229, runFile("language", source("../conformance/programs/language.colony")))
    }
}
