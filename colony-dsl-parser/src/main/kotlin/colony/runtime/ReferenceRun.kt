package colony.runtime

import colony.bytecode.Op
import colony.semantics.Capability
import colony.semantics.SemanticEnvironment
import colony.world.KernelEvent
import colony.world.WorldKernel
import colony.world.buildTopology
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

/** A request a program made in a step; ref names it as the cause of world events: "entity@tick#index". */
@Serializable data class TraceEvent(
    val source: String, val operation: String, val arguments: List<JsonElement>, val accepted: Boolean = true, val ref: String = "",
)

/**
 * A fact the world established (docs/simulation/trigger-conditions.md, section 3). Recipients are the entities whose
 * programs can receive it and fields are the payload they receive; no recipients means the journal only.
 * The tick is the snapshot in which the event first appears.
 */
@Serializable data class WorldEvent(
    val id: String, val type: String, val tick: Long, val entityId: String, val actorId: String? = null,
    val causationId: String? = null, val fields: JsonObject = JsonObject(emptyMap()), val recipients: List<String> = emptyList(),
)

@Serializable data class TickSnapshot(
    val version: Int = 1, val runId: String, val runtimeMode: String = "reference",
    val seed: String, val tickId: Long, val timestamp: Long, val full: Boolean = true,
    val entities: List<EntitySnapshot>, val effects: List<TraceEvent>, val events: List<WorldEvent> = emptyList(),
    /** Money the world charged during this step; empty when the run has no economy. */
    val postings: List<colony.world.Posting> = emptyList(),
    val deliveredEvents: Int,
)

private const val WORLD = "world"
private val APPLIANCES = setOf("Heater", "Kettle")

/**
 * Deterministic reference harness, NOT the production world kernel or process broker.
 * Observations are scenario inputs plus what the run derives from its own state; no thermal, electrical or
 * network physics is inferred. Reducers show requested power, movement and damage. The run reports what it can
 * establish as world events: confirmation of actions, damage, broken objects and losses of power and water
 * that a scenario change causes. Events cross tick boundaries as the spec requires.
 */
class ReferenceRun(val prepared: PreparedRun, val runId: String = UUID.randomUUID().toString(),
                   private val fleet: VmFleet = ReferenceFleet(prepared)) : AutoCloseable {
    val runtimeMode: String get() = fleet.mode
    override fun close() = fleet.close()
    private data class Delivery(val recipient: String, val event: DeliveredEvent)

    private val objects = prepared.manifest.instances.associateBy { it.id }
    /** With a world, the physical models own every observation and every consequence of a request. */
    val kernel: WorldKernel? = prepared.scenario.world?.let { world ->
        WorldKernel(prepared.manifest, world, buildTopology(prepared.manifest, world), prepared.program.stepSeconds.toDouble())
    }
    private val views = objects.mapValues { it.value.view }.toMutableMap()
    private val positions = objects.mapValues { Coordinates(it.value.x, it.value.y) }.toMutableMap()
    private val power = mutableMapOf<String, Double>()
    private val health = objects.mapValues { 100.0 }.toMutableMap()
    private var pending = emptyList<OutgoingEvent>()
    private var pendingWorld = emptyList<Delivery>()
    private var tick = 0L
    private var failed = false
    private var eventCounter = 0L
    private var worldSequence = 0L
    private val contracts = SemanticEnvironment().kindContracts
    /** A frame carries only what the entity's program reads (the frame is a projection, not a copy of the world). */
    private val behaviors = objects.mapValues { (_, instance) -> prepared.program.behaviors.single { it.name == instance.behavior } }
    private val observed = behaviors.mapValues { it.value.observes.toSet() }
    private val subscriptions = behaviors.mapValues { (_, behavior) -> behavior.handlers.mapNotNull { it.eventId }.toSet() }
    private val eventIds = prepared.program.events.associate { it.name to it.id }
    private val childrenOf = objects.values.filter { it.parent != null }.groupBy { it.parent!! }
    /** Power granted in the previous step: what a device sees as power_granted. */
    private var granted = emptyMap<String, Double>()

    private fun healthOf(id: String): Double = kernel?.healthOf(id) ?: health.getValue(id)
    private fun positionOf(id: String): Coordinates =
        kernel?.positionOf(id)?.let { Coordinates(it.x, it.y) } ?: positions.getValue(id)

    private fun effectivelyBroken(id: String): Boolean =
        views.getValue(id)["broken"]?.jsonPrimitive?.boolean == true || healthOf(id) <= 0

    /**
     * The observation of one entity at the start of a step: scenario inputs, plus what the run computes from its own
     * state (docs/simulation/trigger-conditions.md, section 6). Lists are sorted by (distance, id) and drop destroyed objects.
     */
    private fun observe(id: String, instance: Instance): JsonObject {
        kernel?.let { return JsonObject(it.view(instance, observed.getValue(id))) }
        val fields = contracts.getValue(instance.kind).viewFields
        val view = views.getValue(id).toMutableMap()
        if ("position" in fields) view["position"] = positionJson(positions.getValue(id))
        // Without a world the harness has no settlement plan, so a place is simply where the manifest put it.
        if ("home" in fields) view["home"] = positionJson(positions.getValue(instance.parent ?: id))
        if ("workplace" in fields) view["workplace"] = positionJson(positions.getValue(instance.parent ?: id))
        if ("depot" in fields) view["depot"] = positionJson(positions.getValue(id))
        if ("health" in fields) view["health"] = JsonPrimitive(health.getValue(id))
        if ("broken" in fields) view["broken"] = JsonPrimitive(effectivelyBroken(id))
        // The same rule that decides the PowerLost and PowerRestored events: an appliance follows its house.
        if ("power_connected" in fields) view["power_connected"] = JsonPrimitive(powerOn(id))
        if ("power_granted" in fields) view["power_granted"] = JsonPrimitive(granted[id] ?: 0.0)
        if ("home_occupants" in fields && instance.parent != null) view["home_occupants"] = views.getValue(instance.parent).getValue("occupants")
        if ("devices" in fields) {
            view["devices"] = JsonArray(childrenOf[id].orEmpty().filter { it.kind in APPLIANCES }.sortedBy { it.id }.map { device ->
                buildJsonObject { put("id", device.id); put("kind", device.kind); put("broken", effectivelyBroken(device.id)) }
            })
        }
        val origin = positions.getValue(id)
        for (field in listOf("reachable_breakables", "visible_infrastructure", "visible_humans")) {
            if (field !in view) continue
            view[field] = JsonArray(view.getValue(field).jsonArray.mapNotNull { candidate ->
                val target = candidate.jsonObject.getValue("id").jsonPrimitive.content
                if (health.getValue(target) <= 0) return@mapNotNull null
                val destination = positions.getValue(target)
                buildJsonObject {
                    put("id", target); put("kind", objects.getValue(target).kind); put("position", positionJson(destination))
                    put("distance", hypot(origin.x - destination.x, origin.y - destination.y)); put("health", health.getValue(target))
                }
            }.sortedWith(compareBy({ it.jsonObject.getValue("distance").jsonPrimitive.double }, { it.jsonObject.getValue("id").jsonPrimitive.content })))
        }
        return JsonObject(view.filterKeys { it in observed.getValue(id) })
    }

    private fun flag(id: String, name: String): Boolean = views.getValue(id)[name]?.jsonPrimitive?.boolean ?: true

    /** Power reaches an object when its own connection and, for an appliance, its house's connection are up. */
    private fun powerOn(id: String): Boolean {
        val instance = objects.getValue(id)
        if ("power_connected" !in contracts.getValue(instance.kind).viewFields) return true
        return flag(id, "power_connected") && (instance.parent?.let { flag(it, "power_connected") } ?: true)
    }

    private fun worldEvent(type: String, entityId: String, actorId: String?, causation: String?, fields: JsonObject, recipients: List<String>) =
        WorldEvent("e${++eventCounter}", type, tick, entityId, actorId, causation, fields, recipients)

    /** The copies handed to programs: only recipients whose program subscribed by declaring the event. */
    private fun deliveriesOf(event: WorldEvent): List<Delivery> {
        val id = eventIds[event.type] ?: return emptyList()
        val sequence = worldSequence++
        return event.recipients.filter { id in subscriptions.getValue(it) }.map { Delivery(it, DeliveredEvent(id, event.fields, WORLD, sequence)) }
    }

    private fun positionJson(position: Coordinates) = buildJsonObject { put("x", position.x); put("y", position.y) }

    /**
     * Kernel facts become journal entries. A break that follows damage in the same step is linked to the hit
     * that caused it; frost and other causes outside a request have no cause inside the world.
     */
    private fun convert(facts: List<KernelEvent>): List<WorldEvent> {
        val lastDamage = HashMap<String, String>()
        return facts.map { fact ->
            val event = worldEvent(fact.type, fact.entityId, fact.actorId, fact.causeRef ?: lastDamage[fact.entityId],
                fact.fields, fact.recipients)
            if (fact.type == "DamageApplied") lastDamage[fact.entityId] = event.id
            event
        }
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    /**
     * Scenario changes of this tick, and the losses and returns of power and water they cause. A change is the
     * transition into this snapshot, so its event is delivered in this frame together with the new observation.
     * Assumption of the reference run: residents of a house hear about its power (the spec table names house and appliances).
     */
    private fun applyChanges(): List<WorldEvent> {
        val changes = prepared.scenario.changes.filter { it.tick == tick }
        if (changes.isEmpty()) return emptyList()
        val powerBefore = objects.keys.associateWith(::powerOn)
        val waterBefore = objects.keys.filter { "water_available" in views.getValue(it) }.associateWith { flag(it, "water_available") }
        for (change in changes) views[change.target] = JsonObject(views.getValue(change.target) + change.view)
        if (tick == 0L) return emptyList() // changes of tick 0 define the initial state
        val events = mutableListOf<WorldEvent>()
        for ((id, instance) in objects) {
            val on = powerOn(id)
            if (on == powerBefore.getValue(id)) continue
            val parent = instance.parent
            if (parent != null && powerOn(parent) != powerBefore.getValue(parent)) continue // covered by the house event
            val recipients = if (instance.kind == "House") {
                listOf(id) + childrenOf[id].orEmpty().filter { it.kind == "Human" || powerOn(it.id) != powerBefore.getValue(it.id) }.map { it.id }
            } else listOf(id)
            events += worldEvent(if (on) "PowerRestored" else "PowerLost", id, null, null, JsonObject(emptyMap()), recipients)
        }
        for ((id, before) in waterBefore) {
            val now = flag(id, "water_available")
            if (now != before) events += worldEvent(if (now) "WaterRestored" else "WaterLost", id, null, null, JsonObject(emptyMap()), listOf(id))
        }
        return events
    }

    fun step(): TickSnapshot {
        check(!failed) { "Run failed; create a fresh run before continuing" }
        check(tick < prepared.scenario.ticks) { "Run complete" }
        try {
            val changeEvents = applyChanges()
            val changeDeliveries = changeEvents.flatMap(::deliveriesOf)
            // Materialize all observations before executing any VM (snapshot isolation).
            val snapshot = objects.mapValues { (id, instance) -> observe(id, instance) }
            val messageInboxes = pending.groupBy({ it.target }, { DeliveredEvent(it.eventId, it.fields, it.sender, it.sequence) })
            val worldInboxes = (pendingWorld + changeDeliveries).groupBy({ it.recipient }, { it.event })
            val frames = objects.mapValues { (id, _) ->
                VmFrame(tick, snapshot.getValue(id), messageInboxes[id].orEmpty() + worldInboxes[id].orEmpty())
            }
            val results = fleet.step(frames)
            val delivered = pending.size + pendingWorld.size + changeDeliveries.size
            val outgoing = results.values.flatMap { it.events }
            require(outgoing.all { it.target in objects }) { "SEND references an unknown object" }
            val intents = results.values.flatMap { it.intents }
            val counters = HashMap<String, Int>()
            val refs = intents.map { "${it.source}@$tick#${counters.merge(it.source, 1, Int::plus)!! - 1}" }
            // Validate the entire effect batch before applying reference reducers. A failure stops the run.
            intents.forEach { intent ->
                when (intent.operation) {
                    Op.POWER_REQUEST -> require(number(intent.arguments.single()) >= 0)
                    Op.MOTION_REQUEST -> { require(number(intent.arguments[1]) >= 0); number(intent.arguments[0].jsonObject.getValue("x")); number(intent.arguments[0].jsonObject.getValue("y")) }
                    Op.DAMAGE_REQUEST -> {
                        val target = intent.arguments[0].jsonPrimitive.content
                        require(target in objects || kernel?.healthOf(target) != null) { "Damage to an unknown object" }
                        require(number(intent.arguments[1]) >= 0)
                    }
                    Op.REPAIR_REQUEST -> {
                        checkNotNull(kernel) { "REPAIR_REQUEST needs a world with repair jobs" }
                        intent.arguments[0].jsonPrimitive.content
                    }
                    else -> error("Unsupported effect ${intent.operation}")
                }
            }
            val dt = prepared.program.stepSeconds.toDouble()
            // Spec (docs/simulation): a request from an entity that could not act in S_k is rejected.
            val ableToAct = objects.keys.filterTo(HashSet()) { healthOf(it) > 0 }
            val events = mutableListOf<WorldEvent>()
            intents.forEachIndexed { index, intent ->
                if (intent.source !in ableToAct && intent.operation == Op.DAMAGE_REQUEST) {
                    events += worldEvent("ActionRejected", intent.source, intent.source, refs[index],
                        obj("action" to "damage", "reason" to "executor_unable"), listOf(intent.source))
                }
            }
            // With a world, every consequence of the step belongs to the kernel and its phases.
            if (kernel != null) events += convert(kernel.step(tick, intents, refs, ableToAct))
            // Damage phase first: simultaneous attacks are summed in stable executor order and clamped at zero.
            if (kernel == null) intents.forEachIndexed { index, intent ->
                if (intent.source !in ableToAct || intent.operation != Op.DAMAGE_REQUEST) return@forEachIndexed
                val target = intent.arguments[0].jsonPrimitive.content
                val amount = number(intent.arguments[1])
                val reason = intent.arguments[2].jsonPrimitive.content
                val before = health.getValue(target)
                health[target] = (before - amount).coerceAtLeast(0.0)
                val owner = objects.getValue(target).takeIf { it.kind in APPLIANCES }?.parent
                events += worldEvent("ActionSucceeded", intent.source, intent.source, refs[index], obj("action" to "damage"), listOf(intent.source))
                val applied = worldEvent("DamageApplied", target, intent.source, refs[index],
                    buildJsonObject { put("target", target); put("amount", amount); put("reason", reason) }, listOfNotNull(intent.source, owner).distinct())
                events += applied
                if (before > 0 && health.getValue(target) <= 0) {
                    val kind = objects.getValue(target).kind
                    events += if (kind == "Human" || kind == "Xenomorph") {
                        worldEvent("EntityDied", target, intent.source, applied.id, obj("entity" to target), emptyList())
                    } else {
                        worldEvent("ObjectBroken", target, intent.source, applied.id, obj("object" to target, "reason" to reason), listOfNotNull(owner))
                    }
                }
            }
            // Power and motion requests live for one step only; entities destroyed in this step no longer act.
            power.clear()
            if (kernel == null) for (intent in intents) {
                if (intent.source !in ableToAct || health.getValue(intent.source) <= 0) continue
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
            granted = if (kernel == null) HashMap(power) else emptyMap()
            pending = outgoing
            pendingWorld = events.flatMap(::deliveriesOf)
            val entities = objects.map { (id, instance) ->
                val state = results.getValue(id).state
                EntitySnapshot(id = id, pid = fleet.pids[id], type = when (instance.kind) { "Human" -> "civilian"; else -> instance.kind.lowercase() },
                    status = if (healthOf(id) <= 0) "dead" else "nominal",
                    metrics = buildJsonObject {
                        if (kernel != null) {
                            kernel.temperatureOf(id)?.let { put("temperature", it) }
                            if (instance.kind == "House") {
                                put("water_level", if (kernel.hasWater(id)) 100.0 else 0.0)
                                put("occupants", kernel.occupants(id))
                                put("spend", kernel.spentBy(id))
                            }
                        } else {
                            views.getValue(id)["temperature"]?.let { put("temperature", it) }
                            views.getValue(id)["water_temperature"]?.let { put("temperature", it) }
                        }
                        state["stress"]?.let { put("stress", it) }
                        put("health", healthOf(id))
                        if (Capability.POWER_REQUEST in contracts.getValue(instance.kind).capabilities) {
                            put("power_consumption", kernel?.grantedOf(id) ?: power[id] ?: 0.0)
                        }
                    }, connectedTo = listOfNotNull(instance.parent), coordinates = positionOf(id), parentId = instance.parent, vmState = state)
            }
            return TickSnapshot(runId = runId, runtimeMode = fleet.mode, seed = prepared.scenario.seed.toString(), tickId = tick,
                timestamp = ((tick + 1) * dt * 1000).toLong(), entities = entities,
                effects = intents.mapIndexed { index, it -> TraceEvent(it.source, it.operation.name, it.arguments, it.source in ableToAct, refs[index]) },
                events = changeEvents + events, postings = kernel?.lastPostings.orEmpty(),
                deliveredEvents = delivered).also { tick++ }
        } catch (failure: Exception) { failed = true; close(); throw failure }
    }
}
