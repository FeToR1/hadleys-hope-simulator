package colony.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Drives a [ReferenceRun] at a controllable pace (docs/simulation, section 7 of the interaction overview).
 * Pause, single steps and speed only decide when steps happen, never what a step computes,
 * and a pause never interrupts a step that has begun. Nothing here knows about HTTP, so the whole
 * lifecycle can be tested with a plain loop.
 */
class RunController(
    private val prepared: PreparedRun,
    private val fleetFactory: (PreparedRun, String) -> VmFleet = { p, _ -> ReferenceFleet(p) },
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private fun createRun(): ReferenceRun { val id = newRunId(); return ReferenceRun(prepared, id, fleetFactory(prepared, id)) }
    enum class Status { WAITING, RUNNING, PAUSED, COMPLETED, FAILED }

    /** One committed step exactly as observers receive it. */
    data class Frame(val sequence: Long, val runId: String, val tick: Long, val json: String)

    private val lock = ReentrantLock()
    private val committed = lock.newCondition()
    private val json = Json { encodeDefaults = true }
    private var run = createRun() // fail fast: a broken program must not start a server
    private var status = Status.WAITING
    private var failure: String? = null
    private var latest: Frame? = null
    private var sequence = 0L
    private var speed = DEFAULT_STEPS_PER_SECOND

    val stepsPerSecond: Double get() = lock.withLock { speed }
    val currentStatus: Status get() = lock.withLock { status }

    /** First observer attached: an untouched run begins. Later calls change nothing. */
    fun start() = lock.withLock { if (status == Status.WAITING) status = Status.RUNNING }

    fun pause() = lock.withLock { if (status == Status.RUNNING) status = Status.PAUSED }

    fun resume() = lock.withLock { if (status == Status.PAUSED || status == Status.WAITING) status = Status.RUNNING }

    /** Step mode: pauses if needed, then performs exactly one step. */
    fun stepOnce() = lock.withLock {
        if (status == Status.RUNNING || status == Status.WAITING) status = Status.PAUSED
        if (status == Status.PAUSED) advance(Status.PAUSED)
    }

    /** New run under a new ID, evaluated to its first committed step and paused there. Also recovers a failed run. */
    fun reset() = lock.withLock {
        try {
            run.close()
            latest = null
            run = createRun()
            failure = null
            status = Status.PAUSED
            advance(Status.PAUSED)
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun setSpeed(stepsPerSecond: Double) = lock.withLock {
        require(stepsPerSecond in MIN_STEPS_PER_SECOND..MAX_STEPS_PER_SECOND) {
            "speed must be between $MIN_STEPS_PER_SECOND and $MAX_STEPS_PER_SECOND steps per second"
        }
        speed = stepsPerSecond
    }

    /** Called by the pacing loop: performs one step when the run is running. */
    fun tick(): Boolean = lock.withLock {
        if (status != Status.RUNNING) return@withLock false
        advance(Status.RUNNING)
        true
    }

    /** The newest committed step if it is newer than [afterSequence]; waits up to [timeoutMillis], else null. */
    fun awaitAfter(afterSequence: Long, timeoutMillis: Long): Frame? {
        lock.lock()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                val frame = latest
                if (frame != null && frame.sequence > afterSequence) return frame
                if (remaining <= 0) return null
                remaining = committed.awaitNanos(remaining)
            }
        } finally {
            lock.unlock()
        }
    }

    fun health(): JsonObject = lock.withLock {
        buildJsonObject {
            put("version", 1); put("runtimeMode", run.runtimeMode); put("runId", run.runId)
            put("status", status.name.lowercase()); put("tick", latest?.tick ?: -1L)
            put("ticks", prepared.scenario.ticks); put("stepsPerSecond", speed)
            failure?.let { put("error", it) }
        }
    }

    private fun advance(continueAs: Status) {
        try {
            val snapshot = run.step()
            sequence++
            latest = Frame(sequence, snapshot.runId, snapshot.tickId, json.encodeToString(snapshot))
            status = if (snapshot.tickId + 1 >= prepared.scenario.ticks) Status.COMPLETED else continueAs
            if (status == Status.COMPLETED) run.close()
        } catch (error: Exception) {
            fail(error)
        }
        committed.signalAll()
    }

    private fun fail(error: Exception) {
        run.close()
        failure = error.message ?: error.javaClass.simpleName
        status = Status.FAILED
        committed.signalAll()
    }

    override fun close() = lock.withLock { run.close() }

    companion object {
        const val DEFAULT_STEPS_PER_SECOND = 3.0
        const val MIN_STEPS_PER_SECOND = 0.1
        const val MAX_STEPS_PER_SECOND = 100.0
    }
}

/** Background thread that calls [RunController.tick] at the configured speed. */
class RunPacer(private val controller: RunController) : AutoCloseable {
    @Volatile private var stopped = false
    private val thread = Thread({
        while (!stopped) {
            val started = System.nanoTime()
            val stepped = controller.tick()
            val interval = (1000.0 / controller.stepsPerSecond).toLong()
            val spent = (System.nanoTime() - started) / 1_000_000
            try {
                Thread.sleep(if (stepped) maxOf(1L, interval - spent) else IDLE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }, "reference-run-pacer").apply { isDaemon = true }

    fun start() = thread.start()

    override fun close() {
        stopped = true
        thread.interrupt()
    }

    private companion object { const val IDLE_POLL_MILLIS = 50L }
}
