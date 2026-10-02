package colony.runtime

import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class CompactObserverTest {
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
                assertEquals(snapshot.entities.associate { it.id to brokerJson.encodeToJsonElement(it).jsonObject }, restored, "tick=$tick")
                assertEquals(brokerJson.encodeToJsonElement(snapshot.events), wire["events"])
                assertEquals(brokerJson.encodeToJsonElement(snapshot.postings), wire["postings"])
            }
        }
    }
}
