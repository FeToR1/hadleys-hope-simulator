package colony.runtime

import java.nio.file.Path
import kotlin.test.*

class NativeFleetTest {
    @Test fun workerCountsProduceIdenticalCommittedWorlds() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        for (workers in listOf(2, 4, 8)) {
            SimulationRun(prepared, "same", NativeFleet(prepared, 1)).use { serial ->
                SimulationRun(prepared, "same", NativeFleet(prepared, workers)).use { parallel ->
                    repeat(180) { tick -> assertEquals(serial.step().withoutPids(), parallel.step().withoutPids(), "workers=$workers tick=$tick") }
                }
            }
        }
    }

    @Test fun invalidWorkerCountAndUseAfterCloseAreRejected() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
        assertFailsWith<IllegalArgumentException> { NativeFleet(prepared, 0) }
        val fleet = NativeFleet(prepared, 2)
        fleet.close()
        fleet.close()
        assertFailsWith<IllegalStateException> { fleet.step(emptyMap()) }
    }
}
