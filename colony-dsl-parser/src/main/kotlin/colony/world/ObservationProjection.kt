package colony.world

import kotlinx.serialization.json.JsonElement

/** A frame may evaluate fields only before the world starts its next physical step. */
internal class ObservationProjectionPhase {
    @Volatile private var open = true
    fun requireOpen() = check(open) { "An unevaluated observation escaped its world phase" }
    fun close() { open = false }
}

/** All values produced by a getter are immutable JSON trees; each getter runs at most once. */
internal class ObservationProjection(
    fields: Set<String>,
    private val phase: ObservationProjectionPhase,
    private val evaluate: (String) -> JsonElement,
) : AbstractMap<String, JsonElement>() {
    override val keys: Set<String> = fields
    override val size: Int get() = keys.size
    private val cached = HashMap<String, JsonElement>()

    override fun containsKey(key: String): Boolean = key in keys

    @Synchronized override fun get(key: String): JsonElement? {
        if (key !in keys) return null
        cached[key]?.let { return it }
        phase.requireOpen()
        return evaluate(key).also { cached[key] = it }
    }

    override val entries: Set<Map.Entry<String, JsonElement>> get() = object : AbstractSet<Map.Entry<String, JsonElement>>() {
        override val size: Int get() = keys.size
        override fun iterator(): Iterator<Map.Entry<String, JsonElement>> {
            val iterator = keys.iterator()
            return object : Iterator<Map.Entry<String, JsonElement>> {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): Map.Entry<String, JsonElement> {
                    val key = iterator.next()
                    return java.util.AbstractMap.SimpleImmutableEntry(key, this@ObservationProjection.getValue(key))
                }
            }
        }
    }
}
