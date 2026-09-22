package colony.runtime

import colony.cvm.NativeVmConnection
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProcessFleetTest {
    private fun prepared() = prepareScenario(Path.of("../examples/integration/small.json"))
    private fun fleet(p: PreparedRun) = ProcessFleet(p, assertNotNull(NativeVmConnection.executable()), "test")
    private fun handles(f: ProcessFleet) = (f.pids.values.map { it.toLong() } + f.brokerPid)
        .map { ProcessHandle.of(it).orElseThrow() }
    private fun assertStopped(processes: List<ProcessHandle>) {
        processes.forEach { it.onExit().get(10, TimeUnit.SECONDS); assertFalse(it.isAlive, "PID ${it.pid()} leaked") }
    }

    @Test fun nativeSnapshotsMatchReferenceAcrossMessagesAndWorldEvents() {
        val p = prepared()
        val f = fleet(p)
        val processes = handles(f)
        assertEquals(p.manifest.instances.size + 1, processes.size)
        assertEquals(processes.size, processes.map { it.pid() }.toSet().size)
        assertTrue(processes.all { it.isAlive })
        ReferenceRun(p, "test", f).use { native ->
            ReferenceRun(p, "test").use { reference ->
                repeat(60) {
                    val actual = native.step()
                    assertEquals("process", actual.runtimeMode)
                    assertTrue(actual.entities.all { entity -> entity.pid == f.pids[entity.id] })
                    val normalized = actual.copy(runtimeMode = "reference", entities = actual.entities.map { entity -> entity.copy(pid = null) })
                    assertTrue(jsonEquivalent(brokerJson.encodeToJsonElement(reference.step()), brokerJson.encodeToJsonElement(normalized)), "Snapshot differs at tick $it")
                }
            }
        }
        assertStopped(processes)
    }

    @Test fun deadVmAbortsBarrierAndCleansUpTheWholeRun() = failedProcess(false)
    @Test fun deadBrokerCleansUpItsChildren() = failedProcess(true)
    private fun failedProcess(killBroker: Boolean) {
        val p = prepared(); val f = fleet(p); val processes = handles(f)
        ReferenceRun(p, "test", f).use { run ->
            run.step()
            val victim = if (killBroker) processes.last() else processes.first()
            victim.destroyForcibly(); victim.onExit().get(10, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { run.step() }
            assertFailsWith<IllegalStateException> { run.step() }
        }
        assertStopped(processes)
    }

    @Test fun resetReplacesProcessesAndCompletionReapsThem() {
        val p = prepared().let { it.copy(scenario = it.scenario.copy(ticks = 2, changes = emptyList())) }
        val fleets = mutableListOf<ProcessFleet>()
        RunController(p, fleetFactory = { prepared, _ -> fleet(prepared).also { fleets += it } }).use { controller ->
            val old = handles(fleets.single())
            controller.stepOnce()
            controller.reset()
            assertStopped(old)
            val current = handles(fleets.last())
            assertTrue(current.all { it.isAlive })
            assertEquals("process", controller.health().getValue("runtimeMode").jsonPrimitive.content)
            controller.stepOnce()
            assertEquals(RunController.Status.COMPLETED, controller.currentStatus)
            assertStopped(current)
        }
    }
}
