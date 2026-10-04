package colony.runtime

import kotlinx.serialization.json.*

/** JSON belongs to the external observer stream; the kernel/broker transport is binary. */
internal val observerJson = Json { encodeDefaults = true }

/** Per-connection deltas. A fresh connection or missing tick always gets a complete baseline. */
class CompactObserver {
    private var previous: TickSnapshot? = null
    private var previousEntities: Map<String, EntitySnapshot> = emptyMap()
    fun reset() { previous = null; previousEntities = emptyMap() }

    fun encode(snapshot: TickSnapshot): String {
        val base = previous?.takeIf { it.runId == snapshot.runId && it.tickId + 1 == snapshot.tickId }
        val old = if (base == null) emptyMap() else previousEntities
        val sameEntities = base != null && base.entities === snapshot.entities
        val next = if (sameEntities) null else HashMap<String, EntitySnapshot>(snapshot.entities.size)
        val current: Map<String, EntitySnapshot> = next ?: old
        val output = StringBuilder()
        fun field(name: String, value: JsonElement) {
            output.append(",\"").append(name).append("\":").append(value)
        }
        output.append("{\"version\":2")
        field("runId", JsonPrimitive(snapshot.runId))
        field("runtimeMode", JsonPrimitive(snapshot.runtimeMode))
        field("seed", JsonPrimitive(snapshot.seed))
        field("tickId", JsonPrimitive(snapshot.tickId))
        field("timestamp", JsonPrimitive(snapshot.timestamp))
        field("full", JsonPrimitive(base == null))
        field("baseTick", base?.tickId?.let(::JsonPrimitive) ?: JsonNull)
        output.append(",\"entities\":[")
        var first = true
        if (!sameEntities) for (entity in snapshot.entities) {
            next!![entity.id] = entity
            val before = old[entity.id]
            if (before === entity) continue
            val pidChanged = before == null || before.pid != entity.pid
            val typeChanged = before == null || before.type != entity.type
            val statusChanged = before == null || before.status != entity.status
            val metricsChanged = before == null || before.metrics !== entity.metrics && before.metrics != entity.metrics
            val connectionsChanged = before == null || before.connectedTo !== entity.connectedTo && before.connectedTo != entity.connectedTo
            val coordinatesChanged = before == null || before.coordinates !== entity.coordinates && before.coordinates != entity.coordinates
            val parentChanged = before == null || before.parentId != entity.parentId
            val vmChanged = before == null || before.vmState !== entity.vmState && before.vmState != entity.vmState
            if (!(pidChanged || typeChanged || statusChanged || metricsChanged || connectionsChanged || coordinatesChanged || parentChanged || vmChanged)) continue
            if (!first) output.append(',')
            first = false
            output.append("{\"id\":").append(JsonPrimitive(entity.id))
            if (pidChanged) field("pid", entity.pid?.let(::JsonPrimitive) ?: JsonNull)
            if (typeChanged) field("type", JsonPrimitive(entity.type))
            if (statusChanged) field("status", JsonPrimitive(entity.status))
            if (metricsChanged) field("metrics", entity.metrics)
            if (connectionsChanged) {
                output.append(",\"connectedTo\":[")
                entity.connectedTo.forEachIndexed { index, id ->
                    if (index > 0) output.append(',')
                    output.append(JsonPrimitive(id))
                }
                output.append(']')
            }
            if (coordinatesChanged) {
                output.append(",\"coordinates\":{\"x\":").append(JsonPrimitive(entity.coordinates.x))
                    .append(",\"y\":").append(JsonPrimitive(entity.coordinates.y)).append('}')
            }
            if (parentChanged) field("parentId", entity.parentId?.let(::JsonPrimitive) ?: JsonNull)
            if (vmChanged) field("vmState", entity.vmState)
            output.append('}')
        }
        output.append("],\"removed\":[")
        first = true
        if (!sameEntities) for (id in old.keys) if (id !in current) {
            if (!first) output.append(',')
            first = false
            output.append(JsonPrimitive(id))
        }
        output.append(']')
        // The UI consumes facts and ledger entries; per-tick power/motion requests are not displayed.
        field("events", observerJson.encodeToJsonElement(snapshot.events))
        field("postings", observerJson.encodeToJsonElement(snapshot.postings))
        field("deliveredEvents", JsonPrimitive(snapshot.deliveredEvents))
        output.append('}')
        val result = output.toString()
        previous = snapshot
        previousEntities = current
        return result
    }
}
