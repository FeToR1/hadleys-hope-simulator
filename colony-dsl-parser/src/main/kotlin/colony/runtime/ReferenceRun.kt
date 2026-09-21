package colony.runtime

import colony.bytecode.Op
import colony.semantics.Capability
import colony.semantics.SemanticEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min

@Serializable data class Coordinates(val x: Double, val y: Double)
@Serializable data class EntitySnapshot(
    val id: String, val pid: Int? = null, val type: String, val status: String,
    val metrics: JsonObject, val connectedTo: List<String>, val coordinates: Coordinates, val parentId: String? = null,
    val vmState: JsonObject,
)
@Serializable data class TraceEvent(val source: String, val operation: String, val arguments: List<JsonElement>, val accepted: Boolean = true)
@Serializable data class TickSnapshot(
    val version: Int = 1, val runId: String, val runtimeMode: String = "reference",
    val seed: String, val tickId: Long, val timestamp: Long, val full: Boolean = true,
    val entities: List<EntitySnapshot>, val effects: List<TraceEvent>, val deliveredEvents: Int,
)

/** Deterministic reference harness, NOT the production world kernel or process broker.
 * Views are scenario inputs; no thermal/electrical/network physics is inferred from them.
 * Reference reducers show requested power, movement and damage, and events cross tick boundaries.
 */
class ReferenceRun(val prepared: PreparedRun, val runId: String = UUID.randomUUID().toString()) {
    private val objects = prepared.manifest.instances.associateBy { it.id }
    private val vms = objects.mapValues { (_, instance) -> ReferenceVm(instance.id, prepared.program, instance.behavior, instance.params, prepared.scenario.seed) }
    private val views = objects.mapValues { it.value.view }.toMutableMap()
    private val positions = objects.mapValues { Coordinates(it.value.x, it.value.y) }.toMutableMap()
    private val power = mutableMapOf<String, Double>()
    private val health = objects.mapValues { 100.0 }.toMutableMap()
    private var pending = emptyList<OutgoingEvent>()
    private var tick = 0L
    private var failed = false

    fun step(): TickSnapshot {
        check(!failed) { "Run failed; create a fresh run before continuing" }
        check(tick < prepared.scenario.ticks) { "Run complete" }
        try {
            for (change in prepared.scenario.changes.filter { it.tick == tick }) {
                views[change.target] = JsonObject(views.getValue(change.target) + change.view)
            }
            // Materialize all observations before executing any VM (snapshot isolation).
            val snapshot = objects.mapValues { (id, instance) ->
                val view = views.getValue(id).toMutableMap()
                if ("position" in view) view["position"] = positionJson(positions.getValue(id))
                if ("broken" in view) view["broken"] = JsonPrimitive(view.getValue("broken").jsonPrimitive.boolean || health.getValue(id) <= 0)
                if (instance.kind == "Heater" && instance.parent != null) {
                    view["home_occupants"] = views.getValue(instance.parent).getValue("occupants")
                }
                for (field in listOf("reachable_breakables", "visible_infrastructure")) {
                    if (field !in view) continue
                    view[field] = JsonArray(view.getValue(field).jsonArray.mapNotNull { candidate ->
                        val target = candidate.jsonObject.getValue("id").jsonPrimitive.content
                        if (health.getValue(target) <= 0) return@mapNotNull null
                        val origin = positions.getValue(id); val destination = positions.getValue(target)
                        buildJsonObject {
                            put("id", target); put("position", positionJson(destination))
                            put("distance", hypot(origin.x - destination.x, origin.y - destination.y))
                        }
                    })
                }
                JsonObject(view)
            }
            val inboxes = pending.groupBy { it.target }
            val results = vms.mapValues { (id, vm) ->
                vm.step(VmFrame(tick, snapshot.getValue(id), inboxes[id].orEmpty().map { DeliveredEvent(it.eventId, it.fields, it.sender, it.sequence) }))
            }
            val outgoing = results.values.flatMap { it.events }
            require(outgoing.all { it.target in objects }) { "SEND references an unknown object" }
            val intents = results.values.flatMap { it.intents }
            // Validate entire effect batch before applying reference reducers. A failure stops the run.
            intents.forEach { intent ->
                when (intent.operation) {
                    Op.POWER_REQUEST -> require(number(intent.arguments.single()) >= 0)
                    Op.MOTION_REQUEST -> { require(number(intent.arguments[1]) >= 0); number(intent.arguments[0].jsonObject.getValue("x")); number(intent.arguments[0].jsonObject.getValue("y")) }
                    Op.DAMAGE_REQUEST -> { require(intent.arguments[0].jsonPrimitive.content in objects); require(number(intent.arguments[1]) >= 0) }
                    Op.REPAIR_REQUEST -> error("REPAIR_REQUEST requires the future repair/economy kernel")
                    else -> error("Unsupported effect ${intent.operation}")
                }
            }
            val dt = prepared.program.stepSeconds.toDouble()
            // Spec (docs/simulation): a request from an entity that could not act in S_k is rejected.
            val ableToAct = objects.keys.filterTo(HashSet()) { health.getValue(it) > 0 }
            val accepted = intents.filter { it.source in ableToAct }
            // Damage phase first: simultaneous attacks are summed in stable executor order and clamped at zero.
            for (intent in accepted.filter { it.operation == Op.DAMAGE_REQUEST }) {
                val target = intent.arguments[0].jsonPrimitive.content
                health[target] = (health.getValue(target) - number(intent.arguments[1])).coerceAtLeast(0.0)
            }
            // Power and motion requests live for one step only; entities destroyed in this step no longer act.
            power.clear()
            for (intent in accepted) {
                if (health.getValue(intent.source) <= 0) continue
                when (intent.operation) {
                    Op.POWER_REQUEST -> power[intent.source] = number(intent.arguments.single())
                    Op.MOTION_REQUEST -> {
                        val target = intent.arguments[0].jsonObject
                        val origin = positions.getValue(intent.source)
                        val dx = number(target.getValue("x")) - origin.x; val dy = number(target.getValue("y")) - origin.y
                        val distance = hypot(dx, dy)
                        val ratio = if (distance == 0.0) 0.0 else min(1.0, number(intent.arguments[1]) * dt / distance)
                        positions[intent.source] = Coordinates(origin.x + dx * ratio, origin.y + dy * ratio)
                    }
                    else -> Unit
                }
            }
            val delivered = pending.size
            pending = outgoing
            val entities = objects.map { (id, instance) ->
                val state = results.getValue(id).state
                EntitySnapshot(id = id, type = when (instance.kind) { "Human" -> "civilian"; else -> instance.kind.lowercase() },
                    status = if (health.getValue(id) <= 0) "dead" else "nominal",
                    metrics = buildJsonObject {
                        views.getValue(id)["temperature"]?.let { put("temperature", it) }
                        views.getValue(id)["water_temperature"]?.let { put("temperature", it) }
                        state["stress"]?.let { put("stress", it) }
                        put("health", health.getValue(id))
                        if (Capability.POWER_REQUEST in SemanticEnvironment().kindContract(instance.kind)!!.capabilities) {
                            put("power_consumption", power[id] ?: 0.0)
                        }
                    }, connectedTo = listOfNotNull(instance.parent), coordinates = positions.getValue(id), parentId = instance.parent, vmState = state)
            }
            return TickSnapshot(runId = runId, seed = prepared.scenario.seed.toString(), tickId = tick,
                timestamp = ((tick + 1) * dt * 1000).toLong(), entities = entities,
                effects = intents.map { TraceEvent(it.source, it.operation.name, it.arguments, it.source in ableToAct) }, deliveredEvents = delivered).also { tick++ }
        } catch (failure: Exception) { failed = true; throw failure }
    }

    private fun positionJson(position: Coordinates) = buildJsonObject { put("x", position.x); put("y", position.y) }
}
