package colony.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local observer and control gateway, not a broker. It publishes committed snapshots (GET /stream, SSE),
 * the run state (GET /health) and accepts run control (POST /control/pause, resume, step, reset, speed).
 * A slow UI client only skips full snapshots and never influences the simulation. Loopback only.
 */
class ObserverGateway(
    private val controller: RunController,
    port: Int,
    bindHost: String = System.getenv("HH_BIND_HOST")?.ifBlank { null } ?: "127.0.0.1",
    private val allowedHosts: Set<String> = configuredAllowedHosts(),
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(bindHost, port), 16)
    private val requests: ExecutorService = Executors.newFixedThreadPool(8)
    private val clients = AtomicInteger(0)

    val port: Int get() = server.address.port

    init {
        server.executor = requests
        server.createContext("/health") { exchange ->
            guarded(exchange, "GET") {
                respond(exchange, if (controller.currentStatus == RunController.Status.FAILED) 503 else 200, controller.health())
            }
        }
        server.createContext("/control/") { exchange -> guarded(exchange, "POST") { control(exchange) } }
        server.createContext("/stream") { exchange -> guarded(exchange, "GET") { stream(exchange) } }
    }

    fun start(): ObserverGateway {
        server.start()
        return this
    }

    override fun close() {
        server.stop(0)
        requests.shutdownNow()
    }

    /** Wrong method and non-local Host or Origin are rejected before anything is executed. */
    private fun guarded(exchange: HttpExchange, method: String, action: () -> Unit) {
        try {
            when {
                !isLocalRequest(exchange.requestHeaders.getFirst("Host"), exchange.requestHeaders.getFirst("Origin"), allowedHosts) ->
                    respond(exchange, 403, errorBody("Only local requests are served"))
                exchange.requestMethod != method -> respond(exchange, 405, errorBody("Use $method"))
                else -> action()
            }
        } catch (_: IOException) {
            // The client went away; nothing else to do.
        } finally {
            exchange.close()
        }
    }

    private fun control(exchange: HttpExchange) {
        try {
            when (val action = exchange.requestURI.path.removePrefix("/control/")) {
                "pause" -> controller.pause()
                "resume" -> controller.resume()
                "step" -> controller.stepOnce()
                "reset" -> controller.reset()
                "speed" -> controller.setSpeed(
                    queryParameter(exchange.requestURI, "value")?.toDoubleOrNull()
                        ?: throw IllegalArgumentException("speed needs ?value=<steps per second>"),
                )
                else -> return respond(exchange, 404, errorBody("Unknown control action $action"))
            }
            respond(exchange, 200, controller.health())
        } catch (invalid: IllegalArgumentException) {
            respond(exchange, 400, errorBody(invalid.message ?: "Invalid request"))
        }
    }

    private fun stream(exchange: HttpExchange) {
        if (clients.incrementAndGet() > MAX_STREAM_CLIENTS) {
            clients.decrementAndGet()
            return respond(exchange, 503, errorBody("Too many observers"))
        }
        try {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            controller.start() // an untouched run begins with its first observer
            val writer = exchange.responseBody.bufferedWriter(Charsets.UTF_8)
            var seen = -1L
            while (!Thread.currentThread().isInterrupted) {
                val frame = controller.awaitAfter(seen, KEEP_ALIVE_MILLIS)
                if (frame != null) {
                    writer.write("data: ${frame.json}\n\n")
                    seen = frame.sequence
                } else {
                    writer.write(": keep-alive\n\n")
                }
                writer.flush()
                Thread.sleep(MIN_PUSH_INTERVAL_MILLIS) // the UI never needs more than about ten snapshots per second
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun errorBody(message: String) = buildJsonObject { put("error", message) }

    private companion object {
        const val MAX_STREAM_CLIENTS = 6
        const val KEEP_ALIVE_MILLIS = 15_000L
        const val MIN_PUSH_INTERVAL_MILLIS = 100L
    }
}

private fun queryParameter(uri: URI, name: String): String? =
    uri.rawQuery?.split("&")?.map { it.split("=", limit = 2) }
        ?.firstOrNull { it[0] == name }?.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }

private val LOCAL_NAMES = setOf("localhost", "127.0.0.1", "[::1]")

/**
 * The gateway serves the local machine only. A page on another site can make a browser send requests to
 * 127.0.0.1 by default, so besides binding to loopback the Host header (DNS rebinding) and an Origin header
 * (cross-site POST) must both name a local or explicitly configured host.
 */
internal fun isLocalRequest(host: String?, origin: String?, allowedHosts: Set<String> = emptySet()): Boolean {
    fun localAuthority(authority: String?): Boolean {
        val value = authority?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val name = if (value.startsWith("[")) value.substringBefore("]") + "]" else value.substringBefore(":")
        return name in LOCAL_NAMES || name in allowedHosts
    }

    if (!localAuthority(host)) return false
    if (origin == null) return true
    return localAuthority(runCatching { URI(origin).authority }.getOrNull())
}

private fun configuredAllowedHosts(): Set<String> =
    System.getenv("HH_ALLOWED_HOSTS")
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

/** Entry point of the serve command: runs until the process is stopped. */
fun serveReference(prepared: PreparedRun, port: Int, fleetFactory: (PreparedRun, String) -> VmFleet = { p, _ -> ReferenceFleet(p) }) {
    require(port in 1..65535)
    val controller = RunController(prepared, fleetFactory = fleetFactory)
    val pacer = RunPacer(controller)
    val gateway = try { ObserverGateway(controller, port) } catch (error: Exception) { controller.close(); throw error }
    Runtime.getRuntime().addShutdownHook(Thread { pacer.close(); gateway.close(); controller.close() })
    gateway.start()
    pacer.start()
    println("Gateway http://127.0.0.1:$port; ${prepared.manifest.instances.size} VMs, mode=${controller.health()["runtimeMode"]}. " +
        "The run starts with the first /stream observer; control it with POST /control/pause, resume, step, reset, speed.")
}
