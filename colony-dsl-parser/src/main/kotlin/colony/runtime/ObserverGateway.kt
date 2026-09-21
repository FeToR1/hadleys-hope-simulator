package colony.runtime

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local observer gateway, not a broker. Slow UI clients only skip full snapshots. */
fun serveReference(prepared: PreparedRun, port: Int) {
    require(port in 1..65535)
    val json = Json { encodeDefaults = true }
    val run = ReferenceRun(prepared)
    val latest = AtomicReference<String?>(null)
    val status = AtomicReference("waiting")
    val failure = AtomicReference<String?>(null)
    val started = AtomicBoolean(false)
    val clients = AtomicInteger(0)
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val requests = Executors.newFixedThreadPool(8)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    var emitted = 0
    server.executor = requests
    server.createContext("/health") { exchange ->
        val body = buildJsonObject {
            put("version", 1); put("runtimeMode", "reference"); put("runId", run.runId)
            put("status", status.get()); failure.get()?.let { put("error", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(if (failure.get() == null) 200 else 503, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/stream") { exchange ->
        if (exchange.requestMethod != "GET" || clients.incrementAndGet() > 6) {
            if (exchange.requestMethod == "GET") clients.decrementAndGet()
            exchange.sendResponseHeaders(503, -1); exchange.close()
        } else {
            try {
                exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
                exchange.responseHeaders.set("Cache-Control", "no-cache")
                exchange.sendResponseHeaders(200, 0)
                if (started.compareAndSet(false, true)) {
                    status.set("running")
                    scheduler.scheduleAtFixedRate({
                        if (status.get() == "running") {
                            try {
                                latest.set(json.encodeToString(run.step()))
                                emitted++
                                if (emitted >= prepared.scenario.ticks) status.set("completed")
                            } catch (error: Exception) { failure.set(error.message ?: "VM execution failed"); status.set("failed") }
                        }
                    }, 0, 300, TimeUnit.MILLISECONDS)
                }
                exchange.responseBody.bufferedWriter(Charsets.UTF_8).use { writer ->
                    var previous: String? = null
                    while (!Thread.currentThread().isInterrupted) {
                        val current = latest.get()
                        if (current != null && current != previous) {
                            writer.write("data: $current\n\n"); previous = current
                        } else writer.write(": ${status.get()}\n\n")
                        writer.flush()
                        Thread.sleep(300)
                    }
                }
            } catch (_: java.io.IOException) {
                // Browser disconnected; simulation and other observers keep running.
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { clients.decrementAndGet(); exchange.close() }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0); scheduler.shutdownNow(); requests.shutdownNow()
    })
    server.start()
    println("Reference gateway http://127.0.0.1:$port; ${prepared.manifest.instances.size} in-process VMs. Simulation starts with the first /stream client.")
}
