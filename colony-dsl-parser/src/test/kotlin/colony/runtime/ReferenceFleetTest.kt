package colony.runtime

import java.nio.file.Path
import kotlin.test.*

class ReferenceFleetTest {
    @Test fun workerCountsProduceIdenticalCommittedWorlds() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        for (workers in listOf(2, 4, 8)) {
            ReferenceRun(prepared, "same", ReferenceFleet(prepared, 1)).use { serial ->
                ReferenceRun(prepared, "same", ReferenceFleet(prepared, workers)).use { parallel ->
                    repeat(180) { tick -> assertEquals(serial.step(), parallel.step(), "workers=$workers tick=$tick") }
                }
            }
        }
    }

    @Test fun invalidWorkerCountAndUseAfterCloseAreRejected() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
        assertFailsWith<IllegalArgumentException> { ReferenceFleet(prepared, 0) }
        val fleet = ReferenceFleet(prepared, 2)
        fleet.close()
        fleet.close()
        assertFailsWith<IllegalStateException> { fleet.step(emptyMap()) }
    }
}
