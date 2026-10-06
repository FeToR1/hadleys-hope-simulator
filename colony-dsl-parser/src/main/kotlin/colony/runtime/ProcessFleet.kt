package colony.runtime

import colony.cvm.ArtifactWriter
import colony.cvm.compileToCvm
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

data class BrokerConfig(val artifact: String, val executable: String, val runId: String,
    val parentPid: Long, val manifest: RunManifest, val timeoutMillis: Long, val workers: Int = 1,
    val startupTimeoutMillis: Long = 120_000)
data class BrokerRequest(val version: Int = 1, val tick: Long, val batchIndex: Int, val batchCount: Int,
    val frames: Map<String, VmFrame>)
data class BrokerReply(val version: Int = 1, val pids: Map<String, Int> = emptyMap(),
    val results: Map<String, VmResult> = emptyMap(), val error: String? = null,
    val batchIndex: Int = 0, val batchCount: Int = 1, val tick: Long? = null)

/** Kernel -> broker -> bounded pool of native processes, each sharing its loaded program across contexts. */
class ProcessFleet(prepared: PreparedRun, executable: Path, runId: String,
                   private val timeoutMillis: Long = 10_000, startupTimeoutMillis: Long = 120_000,
                   private val workers: Int = configuredNativeWorkers()) : VmFleet {
    override val mode = "process"
    override val workerCount: Int
    override val pids: Map<String, Int>
    val brokerPid: Long get() = broker.pid()
    private val manifestIds = prepared.manifest.instances.map { it.id }
    private val closed = AtomicBoolean(false)
    private val directory: Path = Files.createTempDirectory("colony-shared-run-")
    private val rpc = Executors.newSingleThreadExecutor { task -> Thread(task, "kernel-broker-io").apply { isDaemon = true } }
    private val broker: Process
    private val input: DataInputStream
    private val output: DataOutputStream
    private var children: List<ProcessHandle> = emptyList()
    private val hook = Thread({ close() }, "colony-process-cleanup")

    init {
        try {
            require(executable.isRegularFile()) { "Native VM not found: $executable; build native/ first" }
            require(timeoutMillis > 0 && startupTimeoutMillis > 0 && workers in 1..32)
            require(prepared.sources.isNotEmpty()) { "Native execution requires the package sources" }
            val artifact = directory.resolve("program.cvm")
            artifact.writeBytes(ArtifactWriter.write(compileToCvm(prepared.sources, prepared.scenario.step)))
            val config = BrokerConfig(artifact.toString(), executable.toAbsolutePath().toString(), runId,
                ProcessHandle.current().pid(), prepared.manifest, timeoutMillis, workers, startupTimeoutMillis)
            val file = directory.resolve("broker.bin")
            DataOutputStream(Files.newOutputStream(file).buffered()).use { BrokerWire.writeConfig(it, config) }
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            broker = ProcessBuilder(java.toString(), "-Xmx512m", "-cp", runtimeClasspath(), "colony.cli.MainKt", "broker", file.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            input = DataInputStream(broker.inputStream.buffered())
            output = DataOutputStream(broker.outputStream.buffered())
        } catch (failure: Exception) {
            rpc.shutdownNow(); cleanupFiles(); throw failure
        }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            val ready = exchange(startupTimeoutMillis) { BrokerWire.readReply(input) }
            require(ready.results.isEmpty() && ready.tick == null && ready.batchIndex == 0 && ready.batchCount == 1) { "Invalid broker READY header" }
            require(ready.pids.keys == prepared.manifest.instances.map { it.id }.toSet()) { "Broker READY does not match the manifest" }
            require(ready.pids.values.toSet().size == minOf(workers, prepared.manifest.instances.size.coerceAtLeast(1))) {
                "Native process count exceeds the configured shared executor pool"
            }
            pids = ready.pids
            workerCount = pids.values.toSet().size
            children = pids.values.toSet().map { ProcessHandle.of(it.toLong()).orElseThrow { IllegalStateException("VM $it exited during startup") } }
        } catch (failure: Exception) { close(); throw failure }
    }

    override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
        check(!closed.get()) { "Native run is closed" }
        require(frames.keys == pids.keys) { "Frame set does not match the manifest" }
        val reply = exchange(timeoutMillis) {
            val ordered = manifestIds.map { it to frames.getValue(it) }
            require(ordered.all { it.second.tick == ordered.first().second.tick }) { "Frames span multiple ticks" }
            val batches = ordered.chunked(NATIVE_BROKER_BATCH_SIZE)
            batches.forEachIndexed { index, batch ->
                BrokerWire.writeRequest(output, BrokerRequest(tick = batch.first().second.tick, batchIndex = index, batchCount = batches.size,
                    frames = batch.toMap()))
            }
            val first = BrokerWire.readReply(input)
            if (first.error != null) first
            else {
                require(first.batchIndex == 0 && first.batchCount == batches.size && first.tick == ordered.first().second.tick) { "Invalid broker result batch header" }
                val merged = LinkedHashMap<String, VmResult>()
                fun merge(reply: BrokerReply, index: Int) {
                    require(reply.version == 1 && reply.error == null && reply.batchIndex == index && reply.batchCount == batches.size &&
                        reply.tick == ordered.first().second.tick) {
                        "Invalid broker result packet $index"
                    }
                    require(reply.pids.isEmpty() && reply.results.size in 1..NATIVE_BROKER_BATCH_SIZE &&
                        reply.results.keys.none { it in merged }) { "Invalid or duplicate result entity in broker barrier" }
                    merged.putAll(reply.results)
                }
                merge(first, 0)
                for (index in 1 until batches.size) merge(BrokerWire.readReply(input), index)
                require(merged.keys == frames.keys) { "Incomplete broker barrier" }
                first.copy(results = merged)
            }
        }
        require(reply.results.keys == frames.keys) { "Incomplete broker barrier" }
        // Key-set equality alone cannot establish the effect reduction order.
        return manifestIds.associateWith { reply.results.getValue(it) }
    }

    private fun exchange(deadline: Long, operation: () -> BrokerReply): BrokerReply {
        val job = rpc.submit(Callable(operation))
        try {
            val reply = job.get(deadline, TimeUnit.MILLISECONDS)
            require(reply.version == 1) { "Unsupported broker version" }
            reply.error?.let { error(it) }
            return reply
        } catch (failure: Exception) {
            job.cancel(true); close()
            val cause = (failure as? ExecutionException)?.cause ?: failure
            throw IllegalStateException(if (failure is TimeoutException) "Broker barrier timed out after $deadline ms" else "Broker failed: ${cause.message}", cause)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = (children + broker.descendants().use { it.toList() }).distinctBy { it.pid() }
        owned.forEach { if (it.isAlive) it.destroyForcibly() }
        if (broker.isAlive) broker.destroyForcibly()
        rpc.shutdownNow()
        runCatching { broker.waitFor(5, TimeUnit.SECONDS) }
        owned.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
        runCatching { output.close() }; runCatching { input.close() }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        cleanupFiles()
    }

    private fun cleanupFiles() {
        // Only files created by this object; never recursively delete an external path.
        listOf("broker.bin", "program.cvm").forEach { runCatching { Files.deleteIfExists(directory.resolve(it)) } }
        runCatching { Files.deleteIfExists(directory) }
    }
}

internal const val NATIVE_BROKER_BATCH_SIZE = 512

internal fun configuredNativeWorkers(): Int = System.getenv("HH_NATIVE_WORKERS")?.let {
    it.toIntOrNull() ?: error("HH_NATIVE_WORKERS must be a positive integer")
} ?: 1

/** Gradle tests use a URL classloader; installed CLI uses java.class.path. Support both. */
private fun runtimeClasspath(): String {
    val paths = System.getProperty("java.class.path").split(java.io.File.pathSeparator).toMutableSet()
    var loader: ClassLoader? = ProcessFleet::class.java.classLoader
    while (loader != null) {
        if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { paths += Path.of(it.toURI()).toString() }
        loader = loader.parent
    }
    return paths.joinToString(java.io.File.pathSeparator)
}
