package colony.runtime

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.*

class ObserverGatewayTest {
    @Test fun onlyLocalHostAndOriginAreServed() {
        assertTrue(isLocalRequest("localhost:5173", null))
        assertTrue(isLocalRequest("127.0.0.1:8080", "http://localhost:5173"))
        assertTrue(isLocalRequest("[::1]:8080", "http://[::1]:5173"))
        assertFalse(isLocalRequest(null, null))
        assertFalse(isLocalRequest("evil.example:8080", null), "DNS rebinding presents a foreign Host")
        assertFalse(isLocalRequest("localhost:8080", "https://evil.example"), "cross-site POST presents a foreign Origin")
        assertFalse(isLocalRequest("localhost:8080", "null"))
        assertFalse(isLocalRequest("localhost.evil.example", null))
        assertTrue(isLocalRequest("backend:8080", null, setOf("backend")))
        assertFalse(isLocalRequest("backend:8080", "https://evil.example", setOf("backend")))
    }

    @Test fun controlsAndStreamWorkOverRealHttp() {
        val prepared = prepareScenario(Path.of("../examples/integration/small.json"))
        val controller = RunController(prepared.copy(scenario = prepared.scenario.copy(ticks = 6))) { "http-run" }
        ObserverGateway(controller, 0).start().use { gateway ->
            val base = "http://127.0.0.1:${gateway.port}"
            val client = HttpClient.newHttpClient()
            fun call(method: String, path: String, origin: String? = null): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI("$base$path")).method(method, HttpRequest.BodyPublishers.noBody())
                if (origin != null) request.header("Origin", origin)
                return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            }
            fun body(response: HttpResponse<String>) = Json.parseToJsonElement(response.body()).jsonObject

            assertEquals("waiting", body(call("GET", "/health")).getValue("status").jsonPrimitive.content)
            val stepped = call("POST", "/control/step")
            assertEquals(200, stepped.statusCode())
            assertEquals(0, body(stepped).getValue("tick").jsonPrimitive.int)
            assertEquals("paused", body(stepped).getValue("status").jsonPrimitive.content)

            assertEquals(405, call("GET", "/control/step").statusCode())
            assertEquals(404, call("POST", "/control/nope").statusCode())
            assertEquals(400, call("POST", "/control/speed?value=abc").statusCode())
            assertEquals(400, call("POST", "/control/speed?value=0").statusCode())
            assertEquals(5.0, body(call("POST", "/control/speed?value=5")).getValue("stepsPerSecond").jsonPrimitive.double)
            assertEquals(403, call("POST", "/control/reset", origin = "https://evil.example").statusCode())
            assertEquals(0, body(call("GET", "/health")).getValue("tick").jsonPrimitive.int, "a rejected request must not reset the run")

            val stream = client.send(HttpRequest.newBuilder(URI("$base/stream")).build(), HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200, stream.statusCode())
            val first = stream.body().bufferedReader().use { reader ->
                generateSequence { reader.readLine() }.first { it.startsWith("data:") }
            }
            val snapshot = Json.parseToJsonElement(first.removePrefix("data:").trim()).jsonObject
            assertEquals("http-run", snapshot.getValue("runId").jsonPrimitive.content)
            assertEquals(0, snapshot.getValue("tickId").jsonPrimitive.int)
            assertEquals(9, snapshot.getValue("entities").jsonArray.size)
            assertTrue(snapshot.getValue("effects").jsonArray.isNotEmpty())
        }
    }
}
