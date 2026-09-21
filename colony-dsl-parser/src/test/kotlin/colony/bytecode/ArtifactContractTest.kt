package colony.bytecode

import colony.runtime.*
import colony.semantics.CONTRACT_VERSION
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.*

/** The artifact says which contract it was compiled against and which observations each program reads. */
class ArtifactContractTest {
    private val program = compileSource(File("examples/heater.colony").readText())

    @Test fun anArtifactRecordsItsContractVersionAndSurvivesTheJsonRoundTrip() {
        assertEquals(CONTRACT_VERSION, program.contract)
        val text = bytecodeJson.encodeToString(program)
        assertContains(text, "\"contract\": $CONTRACT_VERSION")
        assertEquals(program, bytecodeJson.decodeFromString<BytecodeProgram>(text))
    }

    @Test fun eachBehaviorListsExactlyTheObservationsItReads() {
        assertEquals(listOf("occupants", "temperature"), program.behaviors.single { it.name == "HouseControl" }.observes)
        assertEquals(listOf("home_occupants", "broken", "power_connected"), program.behaviors.single { it.name == "HeaterControl" }.observes)
        assertEquals(emptyList(), compileSource("behavior Idle for House { }").behaviors.single().observes)
    }

    @Test fun aRuntimeRefusesAProgramFromAnotherContract() {
        val scenario = Scenario(catalog = "inline", ticks = 1, populations = listOf(Population("t", 1, "g")))
        val catalog = Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(emptyMap())))
        val stale = program.copy(contract = CONTRACT_VERSION - 1)
        val refusal = assertFailsWith<IllegalArgumentException> { expandScenario(scenario, catalog, stale) }
        assertContains(refusal.message.orEmpty(), "contract")
    }
}
