package colony.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.nio.file.Path

/** Measures the actual world and full observer payload without accumulating a JSONL journal. */
fun benchmarkScenario(path: Path, measuredTicks: Int = 30, compact: Boolean = false) {
    require(measuredTicks > 0) { "Benchmark ticks must be positive" }
    val started = System.nanoTime()
    val prepared = prepareScenario(path)
    val preparedAt = System.nanoTime()
    val run = ReferenceRun(prepared, "benchmark")
    val initializedAt = System.nanoTime()
    val warmup = minOf(10, maxOf(0, prepared.scenario.ticks - measuredTicks))
    val count = minOf(measuredTicks, prepared.scenario.ticks - warmup)
    val stepTimes = ArrayList<Double>()
    val encodeTimes = ArrayList<Double>()
    val phases = ArrayList<ReferenceRun.StepTimings>()
    val json = Json { encodeDefaults = true }
    val observer = if (compact) CompactObserver() else null
    var last: TickSnapshot? = null
    var payloadBytes = 0L
    var snapshotHash = ""
    val gc = ManagementFactory.getGarbageCollectorMXBeans()
    val gcBefore = gc.sumOf { maxOf(0, it.collectionTime) }
    run.use {
        repeat(warmup + count) { index ->
            val before = System.nanoTime()
            val snapshot = run.step()
            val after = System.nanoTime()
            val encoded = observer?.encode(snapshot) ?: json.encodeToString(snapshot)
            val afterEncode = System.nanoTime()
            if (index >= warmup) {
                stepTimes += (after - before) / 1e6
                encodeTimes += (afterEncode - after) / 1e6
                phases += run.lastTimings
                payloadBytes += encoded.toByteArray(Charsets.UTF_8).size
            }
            last = snapshot
            if (index == warmup + count - 1) snapshotHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    fun p95(values: List<Double>) = values.sorted()[kotlin.math.ceil(values.size * 0.95).toInt() - 1]
    println(buildJsonObject {
        put("scenario", path.toString()); put("runtimeMode", run.runtimeMode)
        put("observerEncoding", if (compact) "compact" else "full")
        put("behaviorWorkers", run.behaviorWorkers); put("nativeProcesses", run.nativeProcesses)
        put("logicalProcessors", Runtime.getRuntime().availableProcessors())
        put("houses", prepared.manifest.instances.count { it.kind == "House" })
        put("contexts", prepared.manifest.instances.size); put("snapshotEntities", last!!.entities.size)
        put("warmupTicks", warmup); put("measuredTicks", count); put("lastTick", last!!.tickId)
        put("prepareMs", (preparedAt - started) / 1e6); put("initializeMs", (initializedAt - preparedAt) / 1e6)
        put("stepMeanMs", stepTimes.average()); put("stepP95Ms", p95(stepTimes))
        put("encodeMeanMs", encodeTimes.average()); put("encodeP95Ms", p95(encodeTimes))
        put("observeMeanMs", phases.map { it.observeMs }.average())
        put("behaviorMeanMs", phases.map { it.behaviorMs }.average())
        put("worldMeanMs", phases.map { it.worldMs }.average())
        put("snapshotMeanMs", phases.map { it.snapshotMs }.average())
        put("stepsPerSecondWithEncoding", 1000.0 / (stepTimes.average() + encodeTimes.average()))
        put("snapshotMeanBytes", payloadBytes / count)
        put("lastSnapshotSha256", snapshotHash)
        put("heapUsedMiB", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024.0 * 1024))
        put("gcMillisIncludingWarmup", gc.sumOf { maxOf(0, it.collectionTime) } - gcBefore)
    })
}
