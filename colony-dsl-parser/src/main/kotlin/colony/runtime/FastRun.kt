package colony.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.bufferedWriter

/** Runs without building presentation state on intermediate ticks. An optional JSONL file retains the facts. */
fun runFastScenario(path: Path, output: Path? = null) {
    val prepared = prepareScenario(path)
    require(prepared.scenario.ticks > 0) { "Fast run requires at least one tick" }
    val run = ReferenceRun(prepared, UUID.randomUUID().toString())
    val json = Json { encodeDefaults = true }
    var events = 0L
    var postings = 0L
    var postingAmount = 0L
    var finalSnapshot: TickSnapshot? = null
    val started = System.nanoTime()

    fun execute(writer: java.io.BufferedWriter?) {
        repeat(prepared.scenario.ticks) { index ->
            val snapshot = run.step(captureSnapshot = index == prepared.scenario.ticks - 1)
            events += snapshot.events.size
            postings += snapshot.postings.size
            snapshot.postings.forEach { postingAmount = Math.addExact(postingAmount, it.amount) }
            if (writer != null) writer.appendLine(json.encodeToString(snapshot))
            if (index == prepared.scenario.ticks - 1) finalSnapshot = snapshot
        }
    }

    run.use {
        if (output != null) output.bufferedWriter().use { writer -> execute(writer) } else execute(null)
    }
    val elapsedMs = (System.nanoTime() - started) / 1e6
    val last = checkNotNull(finalSnapshot)
    val finalJson = json.encodeToString(last)
    val hash = MessageDigest.getInstance("SHA-256").digest(finalJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    if (output == null) println(buildJsonObject {
        put("runId", run.runId)
        put("runtimeMode", run.runtimeMode)
        put("seed", prepared.scenario.seed.toString())
        put("ticks", prepared.scenario.ticks)
        put("elapsedMs", elapsedMs)
        put("stepsPerSecond", prepared.scenario.ticks * 1000.0 / elapsedMs)
        put("events", events)
        put("postings", postings)
        put("postingAmount", postingAmount)
        put("finalSnapshotSha256", hash)
    })
}
