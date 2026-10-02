package colony.runtime

import kotlinx.serialization.json.*

/** Per-connection deltas. A fresh connection or missing tick always gets a complete baseline. */
class CompactObserver {
    private var previous: TickSnapshot? = null
    fun reset() { previous = null }
    fun encode(snapshot: TickSnapshot): String {
        val base = previous?.takeIf { it.runId == snapshot.runId && it.tickId + 1 == snapshot.tickId }
        val old = base?.entities?.associateBy { it.id }.orEmpty()
        val ids = snapshot.entities.mapTo(HashSet()) { it.id }
        val entities = buildJsonArray {
            for (entity in snapshot.entities) {
                val before = old[entity.id]
                if (before == entity) continue
                add(buildJsonObject {
                    put("id", entity.id)
                    if (before == null || before.pid != entity.pid) put("pid", entity.pid?.let(::JsonPrimitive) ?: JsonNull)
                    if (before == null || before.type != entity.type) put("type", entity.type)
                    if (before == null || before.status != entity.status) put("status", entity.status)
                    if (before == null || before.metrics != entity.metrics) put("metrics", entity.metrics)
                    if (before == null || before.connectedTo != entity.connectedTo) put("connectedTo", JsonArray(entity.connectedTo.map(::JsonPrimitive)))
                    if (before == null || before.coordinates != entity.coordinates) put("coordinates", buildJsonObject {
                        put("x", entity.coordinates.x); put("y", entity.coordinates.y)
                    })
                    if (before == null || before.parentId != entity.parentId) put("parentId", entity.parentId?.let(::JsonPrimitive) ?: JsonNull)
                    if (before == null || before.vmState != entity.vmState) put("vmState", entity.vmState)
                })
            }
        }
        val result = buildJsonObject {
            put("version", 2); put("runId", snapshot.runId); put("runtimeMode", snapshot.runtimeMode)
            put("seed", snapshot.seed); put("tickId", snapshot.tickId); put("timestamp", snapshot.timestamp)
            put("full", base == null); put("baseTick", base?.tickId?.let(::JsonPrimitive) ?: JsonNull)
            put("entities", entities); put("removed", JsonArray(old.keys.filter { it !in ids }.map(::JsonPrimitive)))
            // The UI consumes facts and ledger entries; per-tick power/motion requests are not displayed.
            put("events", brokerJson.encodeToJsonElement(snapshot.events))
            put("postings", brokerJson.encodeToJsonElement(snapshot.postings))
            put("deliveredEvents", snapshot.deliveredEvents)
        }.toString()
        previous = snapshot
        return result
    }
}
