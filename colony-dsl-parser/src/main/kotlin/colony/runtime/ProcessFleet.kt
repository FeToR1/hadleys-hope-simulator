package colony.runtime

import colony.cvm.ArtifactWriter
import colony.cvm.compileToCvm
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

@Serializable data class BrokerConfig(val artifact: String, val executable: String, val runId: String,
    val parentPid: Long, val manifest: RunManifest, val timeoutMillis: Long, val workers: Int = 8)
@Serializable data class BrokerRequest(val version: Int = 1, val frames: Map<String, VmFrame>)
@Serializable data class BrokerReply(val version: Int = 1, val pids: Map<String, Int> = emptyMap(),
    val results: Map<String, VmResult> = emptyMap(), val error: String? = null)

/** Kernel -> separate broker -> one native OS process for each entity. No thread per VM. */
class ProcessFleet(prepared: PreparedRun, executable: Path, runId: String,
                   private val timeoutMillis: Long = 10_000, startupTimeoutMillis: Long = 120_000) : VmFleet {
    override val mode = "process"
    override val pids: Map<String, Int>
    val brokerPid: Long get() = broker.pid()
    private val closed = AtomicBoolean(false)
    private val directory: Path = Files.createTempDirectory("colony-process-run-")
    private val rpc = Executors.newSingleThreadExecutor { task -> Thread(task, "kernel-broker-io").apply { isDaemon = true } }
    private val broker: Process
    private val input: DataInputStream
    private val output: DataOutputStream
    private var children: List<ProcessHandle> = emptyList()
    private val hook = Thread({ close() }, "colony-process-cleanup")

    init {
        try {
            require(executable.isRegularFile()) { "Native VM not found: $executable; build native/ first" }
            require(timeoutMillis > 0 && startupTimeoutMillis > 0)
            require(prepared.sources.isNotEmpty()) { "Native execution requires the package sources" }
            val artifact = directory.resolve("program.cvm")
            artifact.writeBytes(ArtifactWriter.write(compileToCvm(prepared.sources, prepared.scenario.step)))
            val config = BrokerConfig(artifact.toString(), executable.toAbsolutePath().toString(), runId,
                ProcessHandle.current().pid(), prepared.manifest, timeoutMillis)
            val file = directory.resolve("broker.json")
            file.writeText(brokerJson.encodeToString(config))
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
            val ready = exchange(startupTimeoutMillis) { readBroker<BrokerReply>(input) }
            require(ready.pids.keys == prepared.manifest.instances.map { it.id }.toSet()) { "Broker READY does not match the manifest" }
            require(ready.pids.values.toSet().size == ready.pids.size) { "Each entity must have its own process" }
            pids = ready.pids
            children = pids.values.map { ProcessHandle.of(it.toLong()).orElseThrow { IllegalStateException("VM $it exited during startup") } }
        } catch (failure: Exception) { close(); throw failure }
    }

    override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
        check(!closed.get()) { "Native run is closed" }
        require(frames.keys == pids.keys) { "Frame set does not match the manifest" }
        val reply = exchange(timeoutMillis) {
            writeBroker(output, BrokerRequest(frames = frames))
            readBroker<BrokerReply>(input)
        }
        require(reply.results.keys == frames.keys) { "Incomplete broker barrier" }
        return reply.results
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
        // Only the three files created by this object; never recursively delete an external path.
        listOf("broker.json", "program.cvm").forEach { runCatching { Files.deleteIfExists(directory.resolve(it)) } }
        runCatching { Files.deleteIfExists(directory) }
    }
}

internal val brokerJson = Json { encodeDefaults = true }
private const val MAX_BROKER_MESSAGE = 64 * 1024 * 1024
internal inline fun <reified T> writeBroker(output: DataOutputStream, value: T) {
    val bytes = brokerJson.encodeToString(value).toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..MAX_BROKER_MESSAGE) { "Broker message exceeds 64 MiB" }
    output.writeInt(bytes.size); output.write(bytes); output.flush()
}
internal inline fun <reified T> readBroker(input: DataInputStream): T {
    val size = input.readInt()
    require(size in 1..MAX_BROKER_MESSAGE) { "Invalid broker message length" }
    val bytes = ByteArray(size); input.readFully(bytes)
    return brokerJson.decodeFromString(bytes.toString(Charsets.UTF_8))
}

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
