package colony.compiler

import colony.runtime.*
import colony.semantics.CONTRACT_VERSION
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.*

/** The artifact says which contract it was compiled against and which observations each program reads. */
class ArtifactContractTest {
    private val program = compileSource(File("examples/heater.colony").readText())

    @Test fun anArtifactRecordsItsContractVersionAndSurvivesTheBinaryRoundTrip() {
        val artifact = colony.cvm.ArtifactWriter.write(program.nativeProgram)
        val back = colony.cvm.ArtifactReader.read(artifact)
        assertEquals(CONTRACT_VERSION, back.contract)
        assertContentEquals(artifact, colony.cvm.ArtifactWriter.write(back))
    }

    @Test fun eachBehaviorListsExactlyTheObservationsItReads() {
        assertEquals(listOf("temperature", "occupants"), program.behaviors.single { it.name == "HouseControl" }.observes)
        assertEquals(listOf("home_occupants", "broken", "power_connected"), program.behaviors.single { it.name == "HeaterControl" }.observes)
        assertEquals(emptyList(), compileSource("behavior Idle for House { }").behaviors.single().observes)
    }

    @Test fun aRuntimeRefusesAProgramFromAnotherContract() {
        val scenario = Scenario(ticks = 1, populations = listOf(Population("t", 1, "g")))
        val catalog = Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(emptyMap())))
        val stale = program.copy(contract = CONTRACT_VERSION - 1)
        val refusal = assertFailsWith<IllegalArgumentException> { expandScenario(scenario, catalog, stale) }
        assertContains(refusal.message.orEmpty(), "contract")
    }
}
