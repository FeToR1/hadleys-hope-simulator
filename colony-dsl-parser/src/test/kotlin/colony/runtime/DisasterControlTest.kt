package colony.runtime

import colony.world.Disaster
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.*

class DisasterControlTest {
    private fun prepared() = prepareScenario(Path.of("../examples/physics/ecosystem-small.json"))

    @Test fun pausedCommandCommitsOnNextStepWithCausalLinksAndResetDropsTheQueue() {
        RunController(prepared()).use { controller ->
            controller.stepOnce()
            val id = controller.health().getValue("runId").jsonPrimitive.content
            controller.queueDisaster(Disaster.ReactorExplosion(0.0, 0.0), id)
            assertEquals(RunController.Status.PAUSED, controller.currentStatus)
            assertEquals(0, controller.health().getValue("tick").jsonPrimitive.int)
            controller.stepOnce()
            val snapshot = assertNotNull(controller.awaitAfter(-1, 0)?.snapshot)
            val explosion = snapshot.events.single { it.type == "ReactorExploded" }
            val damage = snapshot.events.single { it.type == "DamageApplied" && it.entityId == "grid/reactor" }
            val broken = snapshot.events.single { it.type == "ObjectBroken" && it.entityId == "grid/reactor" }
            assertEquals(explosion.id, damage.causationId)
            assertEquals(damage.id, broken.causationId)
            assertEquals(0.0, snapshot.entities.single { it.id == "grid/reactor" }.metrics.getValue("health").jsonPrimitive.double)
            controller.queueDisaster(Disaster.Crocodiles(2), id)
            controller.reset()
            assertEquals(0, controller.health().getValue("disasters").jsonObject.getValue("pending").jsonPrimitive.int)
            assertFalse(assertNotNull(controller.awaitAfter(-1, 0)?.snapshot).events.any { it.type == "CrocodileSpawned" })
            assertFailsWith<IllegalArgumentException> { controller.queueDisaster(Disaster.Crocodiles(1), id) }
        }
    }

    @Test fun httpControlsValidateBeforeQueuingAndExposeLimits() {
        RunController(prepared()).use { controller ->
            ObserverGateway(controller, 0).start().use { gateway ->
                val client = HttpClient.newHttpClient()
                val id = controller.health().getValue("runId").jsonPrimitive.content
                fun post(query: String, origin: String? = null): HttpResponse<String> {
                    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${gateway.port}/control/disaster?$query"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                    origin?.let { request.header("Origin", it) }
                    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                }
                assertEquals(400, post("kind=crocodiles&count=1").statusCode())
                assertEquals(400, post("kind=reactor&radius=NaN&damage=100&runId=$id").statusCode())
                assertEquals(400, post("kind=crocodiles&count=1.5&runId=$id").statusCode())
                assertEquals(400, post("kind=monsters&count=100&duration=90&runId=$id").statusCode())
                assertEquals(403, post("kind=crocodiles&count=1&runId=$id", "https://evil.example").statusCode())
                val response = post("kind=crocodiles&count=3&runId=$id")
                assertEquals(200, response.statusCode())
                val health = Json.parseToJsonElement(response.body()).jsonObject
                assertEquals("waiting", health.getValue("status").jsonPrimitive.content)
                assertEquals(-1, health.getValue("tick").jsonPrimitive.int)
                assertEquals(1, health.getValue("disasters").jsonObject.getValue("pending").jsonPrimitive.int)
                controller.stepOnce()
                assertEquals(3, assertNotNull(controller.awaitAfter(-1, 0)?.snapshot).entities.count { it.type == "crocodile" })
            }
        }
    }

    @Test fun finishedAndNoWorldRunsRejectInterventions() {
        val prepared = prepared()
        RunController(prepared.copy(scenario = prepared.scenario.copy(ticks = 1))).use { controller ->
            controller.stepOnce()
            assertFailsWith<IllegalArgumentException> {
                controller.queueDisaster(Disaster.Crocodiles(1), controller.health().getValue("runId").jsonPrimitive.content)
            }
        }
        RunController(prepareScenario(Path.of("../examples/integration/small.json"))).use { controller ->
            assertNull(controller.health()["disasters"])
            assertFailsWith<IllegalArgumentException> {
                controller.queueDisaster(Disaster.Crocodiles(1), controller.health().getValue("runId").jsonPrimitive.content)
            }
        }
    }
}
