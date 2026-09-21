package colony.runtime

import colony.bytecode.compileSource
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RunControllerTest {
    private fun small(ticks: Int): PreparedRun {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
        return prepared.copy(scenario = prepared.scenario.copy(ticks = ticks))
    }

    private fun controller(prepared: PreparedRun = small(4)): RunController {
        var next = 0
        return RunController(prepared) { "run-${++next}" }
    }

    private fun RunController.field(name: String) = health().getValue(name)
    private fun RunController.status() = field("status").jsonPrimitive.content
    private fun RunController.lastTick() = field("tick").jsonPrimitive.long
    private fun RunController.frameTick() = awaitAfter(-1, 0)?.tick

    @Test fun anUntouchedRunWaitsForItsFirstObserver() {
        val run = controller()
        assertEquals("waiting", run.status())
        assertFalse(run.tick(), "nothing runs before the first observer")
        assertNull(run.awaitAfter(-1, 0))
        run.start()
        assertEquals("running", run.status())
        assertTrue(run.tick())
        assertEquals(0L, run.frameTick())
    }

    @Test fun pauseNeverSplitsAStepAndResumeContinues() {
        val run = controller()
        run.start(); run.tick()
        run.pause()
        assertEquals("paused", run.status())
        assertFalse(run.tick())
        assertEquals(0L, run.frameTick())
        run.resume()
        assertTrue(run.tick())
        assertEquals(1L, run.frameTick())
    }

    @Test fun stepModePausesAndAdvancesExactlyOneStep() {
        val run = controller()
        run.stepOnce()
        assertEquals("paused", run.status()); assertEquals(0L, run.frameTick())
        run.stepOnce()
        assertEquals(1L, run.frameTick())
        run.resume(); run.stepOnce()   // stepping a running run pauses it after one more step
        assertEquals("paused", run.status()); assertEquals(2L, run.frameTick())
    }

    @Test fun completesAfterTheConfiguredTicksAndThenIgnoresSteps() {
        val run = controller(small(3))
        run.start()
        repeat(3) { assertTrue(run.tick()) }
        assertEquals("completed", run.status())
        assertEquals(2L, run.lastTick())
        assertFalse(run.tick())
        run.stepOnce()
        assertEquals(2L, run.frameTick())
    }

    @Test fun resetStartsAFreshRunPausedAtItsFirstStep() {
        val run = controller()
        run.start(); repeat(3) { run.tick() }
        val first = run.awaitAfter(-1, 0)!!
        run.reset()
        val second = run.awaitAfter(first.sequence, 0)!!
        assertEquals("run-1", first.runId)
        assertEquals("run-2", second.runId)
        assertEquals(0L, second.tick)
        assertEquals("paused", run.status())
        run.resume(); run.tick()
        assertEquals(1L, run.frameTick())
    }

    @Test fun aFailingStepStopsTheRunAndResetRecovers() {
        val program = compileSource("behavior Bad for House { every 1s as boom { let x = 10 / view.occupants; } }")
        val house = ObjectSpec("House", "Bad", view = buildJsonObject { put("occupants", 1); put("temperature", 20) })
        val scenario = Scenario(catalog = "inline", ticks = 10, populations = listOf(Population("t", 1, "g")),
            changes = listOf(ObservationChange(2, "g-1/house", buildJsonObject { put("occupants", 0) })))
        val catalog = Catalog(sources = listOf("inline"), templates = mapOf("t" to Template(mapOf("house" to house))))
        val run = controller(expandScenario(scenario, catalog, program))
        run.start()
        repeat(3) { run.tick() }
        assertEquals("failed", run.status())
        assertContains(run.field("error").jsonPrimitive.content, "Division by zero")
        assertFalse(run.tick())
        run.reset()
        assertEquals("paused", run.status())
        assertNull(run.health()["error"])
    }

    @Test fun speedIsValidated() {
        val run = controller()
        assertFailsWith<IllegalArgumentException> { run.setSpeed(0.0) }
        assertFailsWith<IllegalArgumentException> { run.setSpeed(1000.0) }
        run.setSpeed(5.0)
        assertEquals(5.0, run.field("stepsPerSecond").jsonPrimitive.double)
    }

    @Test fun observersAreWokenByTheNextCommit() {
        val run = controller()
        run.start(); run.tick()
        assertNull(run.awaitAfter(1, 20), "no newer frame yet")
        val received = AtomicReference<RunController.Frame?>()
        val started = CountDownLatch(1)
        val waiter = Thread { started.countDown(); received.set(run.awaitAfter(1, 5000)) }.also { it.start() }
        started.await()
        Thread.sleep(50)
        run.tick()
        waiter.join(5000)
        assertEquals(2L, received.get()?.sequence)
    }

    @Test fun thePacerStepsARunningRunToTheEnd() {
        val run = controller(small(5))
        run.setSpeed(100.0)
        RunPacer(run).use { pacer ->
            pacer.start()
            run.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (run.status() != "completed" && System.nanoTime() < deadline) Thread.sleep(10)
        }
        assertEquals("completed", run.status())
        assertEquals(4L, run.lastTick())
    }
}
