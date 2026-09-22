package colony.runtime

import colony.cvm.ArtifactReader
import colony.cvm.NativeVmConnection
import colony.bytecode.Op
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

/** A separate broker/supervisor process. Pipes are per-entity FIFO channels, with one request in flight each.
 * Kernel supplies the snapshot and next-tick mail; results are published only after every VM answers.
 */
fun runNativeBroker(configPath: Path) {
    val config = brokerJson.decodeFromString<BrokerConfig>(configPath.readText())
    require(config.workers in 1..32 && config.timeoutMillis > 0)
    val program = ArtifactReader.read(Path.of(config.artifact).readBytes())
    val registry = ConcurrentHashMap.newKeySet<Process>()
    val stopped = AtomicBoolean(false)
    val pool = Executors.newFixedThreadPool(config.workers)
    fun stop() {
        stopped.set(true)
        registry.forEach { it.destroyForcibly() }
        pool.shutdownNow()
    }
    val hook = Thread({ stop() }, "broker-cleanup")
    Runtime.getRuntime().addShutdownHook(hook)
    val parent = ProcessHandle.of(config.parentPid).orElseThrow { IllegalStateException("Kernel exited") }
    val monitor = Thread({
        while (!stopped.get()) {
            if (!parent.isAlive) { stop(); return@Thread }
            try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
        }
    }, "broker-parent-monitor").apply { isDaemon = true; start() }
    val input = DataInputStream(System.`in`.buffered())
    val output = DataOutputStream(System.out.buffered())
    val connections = linkedMapOf<String, NativeVmConnection>()
    try {
        val jobs = config.manifest.instances.map { instance -> pool.submit(Callable {
            instance.id to NativeVmConnection(Path.of(config.executable), Path.of(config.artifact), program,
                instance.behavior, instance.id, config.manifest.seed, instance.params, timeoutMillis = config.timeoutMillis,
                runId = config.runId, onProcess = { process -> registry.add(process); if (stopped.get()) process.destroyForcibly() })
        }) }
        jobs.forEach { job -> val (id, connection) = job.get(); connections[id] = connection }
        val pids = connections.mapValues { it.value.pid.toInt() }
        val chunkSize = maxOf(1, (connections.size + config.workers - 1) / config.workers)
        writeBroker(output, BrokerReply(pids = pids))
        var nextTick = 0L
        while (!stopped.get()) {
            val request = try { readBroker<BrokerRequest>(input) } catch (_: EOFException) { break }
            require(request.version == 1 && request.frames.keys == connections.keys) { "Invalid frame set" }
            require(request.frames.values.all { it.tick == nextTick }) { "Frames must belong to tick $nextTick" }
            // A worker sends every frame of its share before it reads any answer, so all of its VMs compute at
            // once instead of one at a time. The share is small, so the pipe buffers cannot fill up and deadlock.
            val steps = connections.entries.chunked(chunkSize).map { share -> pool.submit(Callable {
                for ((id, connection) in share) {
                    val frame = request.frames.getValue(id)
                    connection.sendFrame(frame.tick, frame.view, frame.events.map { event -> buildJsonObject {
                        put("eventId", event.eventId); put("sender", event.sender)
                        put("sequence", event.sequence); put("fields", event.fields)
                    } })
                }
                share.map { (id, connection) ->
                    val outcome = connection.receiveResult()
                    check(outcome.failure == null) { "VM $id at tick ${request.frames.getValue(id).tick}: ${outcome.failure}" }
                    val intents = outcome.intents.map { intent -> VmIntent(id,
                        Op.valueOf(intent.getValue("operation").jsonPrimitive.content), intent.getValue("arguments").jsonArray.toList()) }
                    val outgoing = outcome.events.map { event -> OutgoingEvent(event.getValue("target").jsonPrimitive.content,
                        event.getValue("eventId").jsonPrimitive.int, event.getValue("fields").jsonObject, id, event.getValue("sequence").jsonPrimitive.long) }
                    require(outgoing.all { it.target in connections }) { "VM $id sent to an unknown entity" }
                    id to VmResult(intents, outgoing, outcome.state)
                }
            }) }
            // Manifest order, independent of the scheduling and completion order of the OS processes.
            val results = steps.flatMap { it.get() }.toMap()
            writeBroker(output, BrokerReply(results = results))
            nextTick++
        }
    } catch (failure: Exception) {
        val cause = (failure as? ExecutionException)?.cause ?: failure
        runCatching { writeBroker(output, BrokerReply(error = cause.message ?: cause.javaClass.simpleName)) }
    } finally {
        stop(); monitor.interrupt()
        connections.values.forEach { runCatching { it.close() } }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}
