package colony.runtime

import colony.cvm.ArtifactReader
import colony.cvm.NativeBatchVmConnection
import colony.bytecode.Op
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

/** A broker starts a bounded number of shared native executors and publishes results only at the full tick barrier. */
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
    val connections = mutableListOf<NativeBatchVmConnection>()
    val entitiesByConnection = mutableListOf<List<String>>()
    val entityPid = linkedMapOf<String, Int>()
    try {
        val partitions = Array(config.workers) { mutableListOf<colony.runtime.Instance>() }
        config.manifest.instances.forEachIndexed { index, instance -> partitions[index % config.workers] += instance }
        val jobs = partitions.filter { it.isNotEmpty() }.map { partition -> pool.submit(Callable {
            NativeBatchVmConnection(Path.of(config.executable), Path.of(config.artifact), program,
                partition.map { NativeBatchVmConnection.ContextInit(it.id, it.behavior, config.manifest.seed, it.params) },
                config.runId, timeoutMillis = config.timeoutMillis, startupTimeoutMillis = config.startupTimeoutMillis,
                onProcess = { process -> registry.add(process); if (stopped.get()) process.destroyForcibly() })
        }) }
        jobs.forEachIndexed { index, job ->
            val connection = job.get()
            connections += connection
            val ids = partitions.filter { it.isNotEmpty() }[index].map { it.id }
            entitiesByConnection += ids
            ids.forEach { entityPid[it] = connection.pid }
        }
        writeBroker(output, BrokerReply(pids = entityPid))
        var nextTick = 0L
        while (!stopped.get()) {
            val firstRequest = try { readBroker<BrokerRequest>(input) } catch (_: EOFException) { break }
            require(firstRequest.version == 1 && firstRequest.tick == nextTick && firstRequest.batchIndex == 0 &&
                firstRequest.batchCount == (entityPid.size + NATIVE_BROKER_BATCH_SIZE - 1) / NATIVE_BROKER_BATCH_SIZE) { "Invalid first frame packet" }
            val packets = ArrayList<BrokerRequest>(firstRequest.batchCount)
            packets += firstRequest
            for (index in 1 until firstRequest.batchCount) {
                val packet = readBroker<BrokerRequest>(input)
                require(packet.version == 1 && packet.tick == nextTick && packet.batchIndex == index && packet.batchCount == firstRequest.batchCount) {
                    "Invalid frame packet $index"
                }
                packets += packet
            }
            val frames = LinkedHashMap<String, VmFrame>(entityPid.size)
            packets.forEach { packet ->
                require(packet.frames.size in 1..NATIVE_BROKER_BATCH_SIZE && packet.frames.values.all { it.tick == nextTick }) { "Invalid frame packet size or tick" }
                packet.frames.forEach { (id, frame) -> require(id in entityPid && frames.putIfAbsent(id, frame) == null) { "Unknown or duplicate frame context $id" } }
            }
            require(frames.keys == entityPid.keys) { "Incomplete frame barrier" }
            val steps = connections.indices.map { index -> pool.submit(Callable {
                val ids = entitiesByConnection[index]
                val executorFrames = ids.associateWith { frames.getValue(it) }
                connections[index].step(nextTick, executorFrames).map { (id, outcome) ->
                    check(outcome.failure == null) { "VM $id at tick ${frames.getValue(id).tick}: ${outcome.failure}" }
                    val intents = outcome.intents.map { intent -> VmIntent(id,
                        Op.valueOf(intent.getValue("operation").jsonPrimitive.content), intent.getValue("arguments").jsonArray.toList()) }
                    val outgoing = outcome.events.map { event -> OutgoingEvent(event.getValue("target").jsonPrimitive.content,
                        event.getValue("eventId").jsonPrimitive.int, event.getValue("fields").jsonObject, id, event.getValue("sequence").jsonPrimitive.long) }
                    require(outgoing.all { it.target in entityPid }) { "VM $id sent to an unknown entity" }
                    id to VmResult(intents, outgoing, outcome.state)
                }
            }) }
            // Manifest order, independent of the scheduling and completion order of the OS processes.
            val unordered = steps.flatMap { it.get() }.toMap()
            require(unordered.keys == frames.keys) { "Incomplete shared-process barrier" }
            // This order is semantically significant: ReferenceRun reduces intents in map iteration order.
            val results = config.manifest.instances.associate { it.id to unordered.getValue(it.id) }
            val resultBatches = results.entries.toList().chunked(NATIVE_BROKER_BATCH_SIZE)
            resultBatches.forEachIndexed { index, batch ->
                writeBroker(output, BrokerReply(results = batch.associate { it.key to it.value }, batchIndex = index,
                    batchCount = resultBatches.size, tick = nextTick))
            }
            nextTick++
        }
    } catch (failure: Exception) {
        val cause = (failure as? ExecutionException)?.cause ?: failure
        runCatching { writeBroker(output, BrokerReply(error = cause.message ?: cause.javaClass.simpleName)) }
    } finally {
        stop(); monitor.interrupt()
        connections.forEach { runCatching { it.close() } }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}
