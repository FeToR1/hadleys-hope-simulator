package colony.runtime

import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class CompactObserverTest {
    @Test fun streamingDeltasPreserveEscapesNullsRemovalsAndGapBaselines() {
        val entity = EntitySnapshot(
            id = "quoted\"\n\\id", type = "house", status = "nominal",
            metrics = buildJsonObject { put("temperature", -0.0) },
            connectedTo = listOf("neighbor\"\n"), coordinates = Coordinates(1.5, -2.0),
            parentId = "parent", vmState = buildJsonObject { put("text", "\t\"\\") },
        )
        val entities = listOf(entity, entity.copy(id = "removed"))
        val snapshot = TickSnapshot(runId = "r\"", seed = "426", tickId = 0, timestamp = 0,
            entities = entities, effects = listOf(TraceEvent(entity.id, "ignored", emptyList())), deliveredEvents = 0)
        val encoder = CompactObserver()
        val baseline = Json.parseToJsonElement(encoder.encode(snapshot)).jsonObject
        assertEquals(observerJson.encodeToJsonElement(entity), baseline.getValue("entities").jsonArray[0])
        assertFalse("effects" in baseline)
        val unchanged = Json.parseToJsonElement(encoder.encode(snapshot.copy(tickId = 1))).jsonObject
        assertEquals(JsonArray(emptyList()), unchanged["entities"])
        assertEquals(JsonArray(emptyList()), unchanged["removed"])
        val delta = Json.parseToJsonElement(encoder.encode(snapshot.copy(tickId = 2,
            entities = listOf(entity.copy(parentId = null))))).jsonObject
        assertEquals(buildJsonObject { put("id", entity.id); put("parentId", JsonNull) }, delta.getValue("entities").jsonArray.single())
        assertEquals(JsonArray(listOf(JsonPrimitive("removed"))), delta["removed"])
        val gap = Json.parseToJsonElement(encoder.encode(snapshot.copy(tickId = 4))).jsonObject
        assertEquals(JsonPrimitive(true), gap["full"])
        assertEquals(JsonNull, gap["baseTick"])
        assertEquals(2, gap.getValue("entities").jsonArray.size)
        val newRun = Json.parseToJsonElement(encoder.encode(snapshot.copy(runId = "new", tickId = 5))).jsonObject
        assertEquals(JsonPrimitive(true), newRun["full"])
    }

    @Test fun deltasReconstructEveryEntityAndPreserveFactsAndLedgerAcrossReset() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val encoder = CompactObserver()
        val restored = linkedMapOf<String, JsonObject>()
        ReferenceRun(prepared, "same").use { run ->
            repeat(1200) { tick ->
                if (tick == 600) encoder.reset()
                val snapshot = run.step()
                val wire = Json.parseToJsonElement(encoder.encode(snapshot)).jsonObject
                val full = wire.getValue("full").jsonPrimitive.boolean
                if (full) restored.clear()
                assertEquals(tick == 0 || tick == 600, full)
                if (!full) assertEquals(tick - 1, wire.getValue("baseTick").jsonPrimitive.int)
                for (patch in wire.getValue("entities").jsonArray) {
                    val fields = patch.jsonObject
                    val id = fields.getValue("id").jsonPrimitive.content
                    restored[id] = JsonObject(restored[id].orEmpty() + fields)
                }
                wire.getValue("removed").jsonArray.forEach { restored.remove(it.jsonPrimitive.content) }
                assertEquals(snapshot.entities.associate { it.id to observerJson.encodeToJsonElement(it).jsonObject }, restored, "tick=$tick")
                assertEquals(observerJson.encodeToJsonElement(snapshot.events), wire["events"])
                assertEquals(observerJson.encodeToJsonElement(snapshot.postings), wire["postings"])
            }
        }
    }
}
