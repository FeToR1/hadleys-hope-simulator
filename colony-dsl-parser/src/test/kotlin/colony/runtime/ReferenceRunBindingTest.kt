package colony.runtime

import colony.semantics.SemanticEnvironment
import colony.semantics.Type
import java.nio.file.Path
import kotlin.test.*

class ReferenceRunBindingTest {
    private class RecordingFleet : VmFleet {
        override val mode = "test"
        override val pids = emptyMap<String, Int>()
        var closed = false
        var steps = 0
        override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
            steps++
            error("Binding must finish before any frame is executed")
        }
        override fun close() { closed = true }
    }

    @Test fun unsupportedObservationClosesTheFleetBeforeAnyTick() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val contracts = SemanticEnvironment().kindContracts
        val invalid = contracts + ("House" to contracts.getValue("House").copy(
            viewFields = contracts.getValue("House").viewFields + ("telepathy" to Type.Bool)))
        val fleet = RecordingFleet()
        val failure = assertFailsWith<IllegalStateException> { ReferenceRun(prepared, "test", fleet, invalid) }
        assertContains(failure.message.orEmpty(), "telepathy")
        assertTrue(fleet.closed)
        assertEquals(0, fleet.steps)
    }

    @Test fun missingKindReportsBindingErrorAndClosesTheFleet() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val fleet = RecordingFleet()
        val failure = assertFailsWith<IllegalStateException> {
            ReferenceRun(prepared, "test", fleet, SemanticEnvironment().kindContracts - "House")
        }
        assertContains(failure.message.orEmpty(), "Kind House has no observation contract")
        assertTrue(fleet.closed)
        assertEquals(0, fleet.steps)
    }
}
