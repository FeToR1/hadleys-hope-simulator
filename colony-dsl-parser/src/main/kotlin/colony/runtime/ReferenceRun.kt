package colony.runtime

import colony.bytecode.Op
import colony.semantics.Capability
import colony.semantics.KindContract
import colony.semantics.SemanticEnvironment
import colony.world.KernelEvent
import colony.world.EcosystemZoneSnapshot
import colony.world.Point
import colony.world.TransportRequest
import colony.world.WorldKernel
import colony.world.GROUND_THREAT_KINDS
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
 * A fact the world established (docs/technical-reference.md#contract, section 3). Recipients are the entities whose
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
                   private val fleet: VmFleet = ReferenceFleet(prepared),
                   private val kindContracts: Map<String, KindContract> = SemanticEnvironment().kindContracts) : AutoCloseable {
    val runtimeMode: String get() = fleet.mode
    val behaviorWorkers: Int get() = fleet.workerCount
    val nativeProcesses: Int get() = fleet.pids.values.toSet().size
    data class StepTimings(val observeMs: Double, val behaviorMs: Double, val worldMs: Double, val snapshotMs: Double)
    var lastTimings = StepTimings(0.0, 0.0, 0.0, 0.0)
        private set
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
    /** A frame carries only what the entity's program reads (the frame is a projection, not a copy of the world). */
    private val behaviors = objects.mapValues { (_, instance) -> prepared.program.behaviors.single { it.name == instance.behavior } }
    private val observed = behaviors.mapValues { it.value.observes.toSet() }
    private val subscriptions = behaviors.mapValues { (_, behavior) -> behavior.handlers.mapNotNull { it.eventId }.toSet() }
    private val eventIds = prepared.program.events.associate { it.name to it.id }
    private val childrenOf = objects.values.filter { it.parent != null }.groupBy { it.parent!! }
    init {
        // A contract field the world cannot compute would otherwise kill the run mid-tick; the spec wants a
        // binding error before the first step (docs/technical-reference.md#world).
        try {
            for (kind in objects.values.map { it.kind }.distinct()) {
                check(kind in kindContracts) { "Kind $kind has no observation contract; the scenario cannot be bound" }
            }
            kernel?.let { world ->
                for ((kind, samples) in objects.values.groupBy { it.kind }) {
                    val contract = kindContracts.getValue(kind)
                    val failure = runCatching { world.view(samples.first(), contract.viewFields.keys) }.exceptionOrNull() ?: continue
                    error("Kind $kind declares observations the world cannot compute: ${failure.message}")
                }
            }
        } catch (failure: Exception) {
            runCatching { fleet.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private data class EntityDescriptor(val instance: Instance, val type: String, val usesPower: Boolean,
                                        val connected: List<String>)
    private val entityDescriptors = objects.values.map { instance -> EntityDescriptor(instance,
        if (instance.kind == "Human") "civilian" else instance.kind.lowercase(),
        Capability.POWER_REQUEST in kindContracts.getValue(instance.kind).capabilities, listOfNotNull(instance.parent)) }
    /** Dense mutable cache keys are private; all trees exposed in snapshots stay immutable. */
    private class MetricCache {
        var temperature: Double? = null
        var scenarioTemperature: JsonElement? = null
        var waterLevel: Double? = null
        var occupants: Int? = null
        var spend: Long? = null
        var passengerCount: JsonElement? = null
        var passengerCapacity: JsonElement? = null
        var squadSize: JsonElement? = null
        var stress: JsonElement? = null
        var health: Double = Double.NaN
        var power: Double? = null
        var inFog: Boolean = false
        var seaDamage: Double? = null
        var creatineCargo: Double? = null
        var respirator: Boolean? = null
        var mutation: String? = null
        var available: Boolean? = null
    }
    private val metricCaches = Array(entityDescriptors.size) { MetricCache() }
    private val entitySnapshots = arrayOfNulls<EntitySnapshot>(entityDescriptors.size)
    private data class FixtureSnapshotKey(
        val health: Double, val powered: Boolean, val status: String, val pumpPower: Double?,
        val creatineStock: Double?, val airDefenseBroken: Boolean?, val ammoRemaining: Double?,
        val shotsFired: Long?, val refusalReason: String?, val groundDefenseHealth: Double?,
        val groundAmmoRemaining: Double?, val groundShotsFired: Long?, val groundRefusalReason: String?,
        val burnerFuel: Double?, val inFog: Boolean,
    )
    private data class FixtureSnapshotCache(val key: FixtureSnapshotKey, val snapshot: EntitySnapshot)
    private val fixtureSnapshots = HashMap<String, FixtureSnapshotCache>()
    private var previousFixtures = emptyList<colony.world.FixtureState>()
    private var fixtureEntities = emptyList<EntitySnapshot>()
    private var previousPumpPower: Double? = null
    private val legacyFixtureFastPath = kernel?.let { world ->
        !world.config.sea.enabled && !world.config.creatine.enabled && !world.config.defense.enabled &&
            !world.config.ecosystem.enabled && world.topology.fixtures.none { fixture ->
                fixture.kind == colony.world.FixtureKind.AIR_DEFENSE || fixture.kind == colony.world.FixtureKind.GROUND_TURRET ||
                    fixture.kind == colony.world.FixtureKind.BURNER
            }
    } ?: false
    private data class SiteSnapshotKey(val workers: List<String>, val mineStock: Double?, val health: Double?, val status: String)
    private var mineSnapshot: Pair<SiteSnapshotKey, EntitySnapshot>? = null
    private data class FogSnapshotKey(val width: Double, val height: Double, val depth: Double, val x: Double, val y: Double)
    private var fogSnapshot: Pair<FogSnapshotKey, EntitySnapshot>? = null
    private data class CrocodileSnapshotKey(val health: Double, val deflected: Boolean, val progress: Double, val x: Double, val y: Double)
    private val crocodileSnapshots = HashMap<String, Pair<CrocodileSnapshotKey, EntitySnapshot>>()
    private val ecologySnapshots = HashMap<String, Pair<EcosystemZoneSnapshot, EntitySnapshot>>()
    /** Power granted in the previous step: what a device sees as power_granted. */
    private var granted = emptyMap<String, Double>()
    private val legacyEntityMetricsFastPath = kernel?.let { world ->
        !world.config.sea.enabled && !world.config.creatine.enabled &&
            !world.config.defense.enabled && !world.config.ecosystem.enabled
    } ?: true

    private fun healthOf(id: String): Double = kernel?.healthOf(id) ?: health.getValue(id)
    private fun positionOf(id: String): Coordinates =
        kernel?.positionOf(id)?.let { Coordinates(it.x, it.y) } ?: positions.getValue(id)

    private fun effectivelyBroken(id: String): Boolean =
        views.getValue(id)["broken"]?.jsonPrimitive?.boolean == true || healthOf(id) <= 0

    /**
     * The observation of one entity at the start of a step: scenario inputs, plus what the run computes from its own
     * state (docs/technical-reference.md#contract). Lists are sorted by (distance, id) and drop destroyed objects.
     */
    private fun observe(id: String, instance: Instance): JsonObject {
        kernel?.let { return JsonObject(it.lazyView(instance, observed.getValue(id))) }
        val fields = kindContracts.getValue(instance.kind).viewFields
        val view = views.getValue(id).toMutableMap()
        if ("position" in fields) view["position"] = positionJson(positions.getValue(id))
        // Without a world the harness has no settlement plan, so a place is simply where the manifest put it.
        if ("home" in fields) view["home"] = positionJson(positions.getValue(instance.parent ?: id))
        if ("workplace" in fields) view["workplace"] = positionJson(positions.getValue(instance.parent ?: id))
        if ("meeting_point" in fields) view["meeting_point"] = positionJson(positions.getValue(instance.parent ?: id))
        if ("depot" in fields) view["depot"] = positionJson(positions.getValue(id))
        if ("transport_target" in fields) view["transport_target"] = positionJson(positions.getValue(id))
        if ("health" in fields) view["health"] = JsonPrimitive(health.getValue(id))
        if ("broken" in fields) view["broken"] = JsonPrimitive(effectivelyBroken(id))
        // Ecology attack windows do not run in the no-world harness; keep the contract's permissive default.
        if ("attack_active" in fields && "attack_active" !in view) view["attack_active"] = JsonPrimitive(true)
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
        if ("power_connected" !in kindContracts.getValue(instance.kind).viewFields) return true
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
     * that caused it; cold and freezing have no request behind them, so [chainedCause] supplies the cause from
     * the journal's memory of what the world did to the same object before.
     */
    private fun convert(facts: List<KernelEvent>): List<WorldEvent> {
        val lastDamage = HashMap<String, String>()
        val repairs = HashMap<String, String>()
        val breaks = HashSet<String>()
        return facts.map { fact ->
            val event = worldEvent(fact.type, fact.entityId, fact.actorId,
                fact.causeRef ?: lastDamage[fact.entityId]?.takeIf { fact.type in setOf("ObjectBroken", "EntityDied") }
                    ?: chainedCause(fact, repairs, breaks), fact.fields, fact.recipients)
            if (fact.type == "DamageApplied") lastDamage[fact.entityId] = event.id
            when (fact.type) {
                "ObjectBroken" -> { lastBreakEvent[fact.entityId] = event; breaks += fact.entityId }
                "PowerLost" -> lastPowerLossEvent[fact.entityId] = event.id
                "RepairCompleted" -> { lastBreakEvent.remove(fact.entityId); repairs[fact.entityId] = event.id }
            }
            event
        }
    }

    private fun transportRequests(outgoing: List<OutgoingEvent>): List<TransportRequest> {
        val eventId = eventIds["VehicleTransportRequest"] ?: return emptyList()
        return outgoing.filter { it.eventId == eventId }.map { event ->
            val point = event.fields.getValue("destination").jsonObject
            val x = number(point.getValue("x"))
            val y = number(point.getValue("y"))
            TransportRequest(
                ref = "${event.sender}@$tick:event${event.sequence}",
                passengerId = event.sender,
                vehicleId = event.target,
                destination = Point(x, y),
            )
        }
    }

    /** Unrepaired breaks; completed repairs must not cause unrelated later network transitions. */
    private val lastBreakEvent = HashMap<String, WorldEvent>()
    private val lastPowerLossEvent = HashMap<String, String>()

    /** Network paths are static; causal lookup visits only the affected house's dependencies. */
    private val powerDependencies = kernel?.let { world -> world.topology.houses.associateWith { house ->
        buildList {
            add(house)
            var node = world.topology.powerFeed[house]
            while (node != null) {
                add(node)
                node = world.topology.byId[node]?.feedsFrom
            }
            addAll(world.topology.sources)
        }
    } }.orEmpty()
    private val waterDependencies = kernel?.let { world -> world.topology.houses.associateWith { house ->
        listOf(world.topology.waterPipe.getValue(house), "water/pump", "grid/bus") + world.topology.sources
    } }.orEmpty()

    /**
     * The cause of a world-internal transition (docs/technical-reference.md#contract): a frozen pipe
     * or a cold death traces to the power loss of the house it belongs to; a power or water loss traces to the
     * break that severed the network; a repair and the restoration it brings trace to the break they answer.
     */
    private fun chainedCause(fact: KernelEvent, repairs: Map<String, String>, breaks: Set<String>): String? {
        val world = kernel ?: return null
        val reason = fact.fields["reason"]?.jsonPrimitive?.content
        fun loss(dependencies: List<String>): String? = dependencies.firstNotNullOfOrNull { id ->
            lastBreakEvent[id]?.takeIf { event ->
                world.isBroken(id) &&
                    (id !in world.topology.sources || id in breaks) &&
                    // Freezing occurs after network recomputation and cannot cause this step's water loss.
                    !(fact.type == "WaterLost" && event.tick == tick && event.fields["reason"]?.jsonPrimitive?.content == "freezing")
            }?.id
        }
        fun restoration(dependencies: List<String>): String? = dependencies.firstNotNullOfOrNull { id ->
            repairs[id]?.takeUnless { world.isBroken(id) }
        }
        return when {
            fact.type == "PowerLost" -> loss(powerDependencies[fact.entityId].orEmpty())
            fact.type == "WaterLost" -> loss(waterDependencies[fact.entityId].orEmpty())
            fact.type == "ObjectBroken" && reason == "freezing" ->
                world.topology.waterPipe.entries.firstOrNull { it.value == fact.entityId }?.key?.let { lastPowerLossEvent[it] }
            fact.type == "EntityDied" -> objects.getValue(fact.entityId).parent?.let { lastPowerLossEvent[it] }
            fact.type == "RepairCompleted" -> lastBreakEvent[fact.entityId]?.id
            fact.type == "PowerRestored" -> restoration(powerDependencies[fact.entityId].orEmpty())
            fact.type == "WaterRestored" -> restoration(waterDependencies[fact.entityId].orEmpty())
            else -> null
        }
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    /**
     * Scenario changes of this tick, and the losses and returns of power and water they cause. A change is the
     * transition into this snapshot, so its event is delivered in this frame together with the new observation.
     * A house power event reaches the house and its appliances.
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
            // docs/technical-reference.md#contract: the house and its appliances, not the residents.
            val recipients = if (instance.kind == "House") {
                listOf(id) + childrenOf[id].orEmpty().filter { it.kind in APPLIANCES }.map { it.id }
            } else listOf(id)
            events += worldEvent(if (on) "PowerRestored" else "PowerLost", id, null, null, JsonObject(emptyMap()), recipients)
        }
        for ((id, before) in waterBefore) {
            val now = flag(id, "water_available")
            if (now != before) events += worldEvent(if (now) "WaterRestored" else "WaterLost", id, null, null, JsonObject(emptyMap()), listOf(id))
        }
        return events
    }

    fun step(captureSnapshot: Boolean = true): TickSnapshot {
        val startedAt = System.nanoTime()
        check(!failed) { "Run failed; create a fresh run before continuing" }
        check(tick < prepared.scenario.ticks) { "Run complete" }
        try {
            val changeEvents = applyChanges()
            val changeDeliveries = changeEvents.flatMap(::deliveriesOf)
            // The world remains read-only until the fleet completes; fields are cached when first read.
            kernel?.beginObservationPhase()
            val messageInboxes = pending.groupBy({ it.target }, { DeliveredEvent(it.eventId, it.fields, it.sender, it.sequence) })
            val worldInboxes = (pendingWorld + changeDeliveries).groupBy({ it.recipient }, { it.event })
            val frames = objects.mapValues { (id, instance) ->
                VmFrame(tick, observe(id, instance), messageInboxes[id].orEmpty() + worldInboxes[id].orEmpty())
            }
            val observedAt = System.nanoTime()
            val results = try { fleet.step(frames) } finally { kernel?.endObservationPhase() }
            val executedAt = System.nanoTime()
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
            // Spec (docs/technical-reference.md#world): a request from an entity that could not act in S_k is rejected.
            val ableToAct = objects.keys.filterTo(HashSet()) { healthOf(it) > 0 }
            val events = mutableListOf<WorldEvent>()
            intents.forEachIndexed { index, intent ->
                if (intent.source !in ableToAct && intent.operation == Op.DAMAGE_REQUEST) {
                    events += worldEvent("ActionRejected", intent.source, intent.source, refs[index],
                        obj("action" to "damage", "reason" to "executor_unable"), listOf(intent.source))
                }
            }
            // With a world, every consequence of the step belongs to the kernel and its phases.
            if (kernel != null) {
                events += convert(kernel.step(tick, intents, refs, ableToAct, transportRequests(outgoing)))
            }
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
                    events += if (kind in setOf("Human", "Marine", "Xenomorph", "Predator")) {
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
            val worldAt = System.nanoTime()
            val capturedEntities = if (captureSnapshot) {
                val entities = entityDescriptors.mapIndexed { ordinal, descriptor ->
                    val instance = descriptor.instance
                    val id = instance.id
                    val state = results.getValue(id).state
                    val world = kernel
                    val point = if (world != null) world.positionOf(id) else null
                    val inFog = !legacyEntityMetricsFastPath && world != null && world.config.sea.enabled && point != null &&
                        world.seaController.isFogActive && world.seaController.isInFog(point)
                    val roverView = if (world != null && instance.kind == "Rover")
                        world.view(instance, ROVER_METRICS) else null
                    val squadSize = if (world != null && instance.kind == "Marine")
                        world.view(instance, MARINE_METRICS)["squad_size"] else null
                    val temperature = world?.temperatureOf(id)
                    val scenarioTemperature = if (world == null) views.getValue(id)["water_temperature"] ?: views.getValue(id)["temperature"] else null
                    val waterLevel = if (world != null && instance.kind == "House") if (world.hasWater(id)) 100.0 else 0.0 else null
                    val occupants = if (world != null && instance.kind == "House") world.occupants(id) else null
                    val spend = if (world != null && instance.kind == "House") world.spentBy(id) else null
                    val seaDamage = if (!legacyEntityMetricsFastPath && world != null && world.config.sea.enabled && instance.kind == "House" && point != null && world.seaController.isInCoastalZone(point))
                        (100.0 - healthOf(id)).coerceAtLeast(0.0) else null
                    val passengerCount = roverView?.get("passenger_count")
                    val passengerCapacity = roverView?.get("passenger_capacity")
                    val creatineCargo = if (!legacyEntityMetricsFastPath && world != null && world.config.creatine.enabled && instance.kind == "Rover") world.roverCreatineCargo[id] else null
                    val respirator = if (!legacyEntityMetricsFastPath && world != null && world.config.sea.enabled && instance.kind == "Human") world.respiratorEquipped[id] == true else null
                    val mutation = if (!legacyEntityMetricsFastPath && world != null && instance.kind in GROUND_THREAT_KINDS && world.config.ecosystem.enabled && world.config.ecosystem.predator.enabled)
                        world.ecosystemController.mutationOf(id, world.elapsedSeconds).name else null
                    val available = if (!legacyEntityMetricsFastPath && world != null && instance.kind == "Human" && world.config.ecosystem.enabled && world.config.ecosystem.humanFactors.enabled)
                        id !in world.unavailableHumans else null
                    val stress = state["stress"]
                    val currentHealth = healthOf(id)
                    val powerConsumption = if (descriptor.usesPower) world?.grantedOf(id) ?: power[id] ?: 0.0 else null
                    val previous = entitySnapshots[ordinal]
                    val cache = metricCaches[ordinal]
                    val metricsUnchanged = previous != null &&
                        cache.temperature == temperature && cache.scenarioTemperature == scenarioTemperature && cache.waterLevel == waterLevel &&
                        cache.occupants == occupants && cache.spend == spend && cache.passengerCount == passengerCount &&
                        cache.passengerCapacity == passengerCapacity && cache.squadSize == squadSize && cache.stress == stress &&
                        cache.health == currentHealth && cache.power == powerConsumption &&
                        (legacyEntityMetricsFastPath || (cache.inFog == inFog && cache.seaDamage == seaDamage &&
                            cache.creatineCargo == creatineCargo && cache.respirator == respirator &&
                            cache.mutation == mutation && cache.available == available))
                    val metrics = if (metricsUnchanged) previous!!.metrics else buildJsonObject {
                        temperature?.let { put("temperature", it) }
                        scenarioTemperature?.let { put("temperature", it) }
                        waterLevel?.let { put("water_level", it) }
                        occupants?.let { put("occupants", it) }
                        spend?.let { put("spend", it) }
                        if (!legacyEntityMetricsFastPath) seaDamage?.let { put("sea_damage", it) }
                        passengerCount?.let { put("passenger_count", it) }
                        passengerCapacity?.let { put("passenger_capacity", it) }
                        if (!legacyEntityMetricsFastPath) {
                            creatineCargo?.let { put("creatine_stock", it) }
                            if (respirator == true) put("respirator_equipped", true)
                            mutation?.let { put("mutation", it) }
                            available?.let { put("available", it) }
                        }
                        squadSize?.let { put("squad_size", it) }
                        if (inFog) put("in_fog", true)
                        stress?.let { put("stress", it) }
                        put("health", currentHealth)
                        powerConsumption?.let { put("power_consumption", it) }
                    }
                    if (!metricsUnchanged) {
                        cache.temperature = temperature
                        cache.scenarioTemperature = scenarioTemperature
                        cache.waterLevel = waterLevel
                        cache.occupants = occupants
                        cache.spend = spend
                        if (!legacyEntityMetricsFastPath) {
                            cache.inFog = inFog
                            cache.seaDamage = seaDamage
                            cache.creatineCargo = creatineCargo
                            cache.respirator = respirator
                            cache.mutation = mutation
                            cache.available = available
                        }
                        cache.passengerCount = passengerCount
                        cache.passengerCapacity = passengerCapacity
                        cache.squadSize = squadSize
                        cache.stress = stress
                        cache.health = currentHealth
                        cache.power = powerConsumption
                    }
                    val vehicle = world?.vehicleOf(id)
                    val connected = if (vehicle == null) descriptor.connected else
                        previous?.connectedTo?.takeIf { it.size == descriptor.connected.size + 1 && it.lastOrNull() == vehicle } ?: listOfNotNull(instance.parent, vehicle)
                    val coordinates = if (world != null) {
                        val p = point ?: world.positionOf(id)
                        previous?.coordinates?.takeIf { it.x.toBits() == p.x.toBits() && it.y.toBits() == p.y.toBits() }
                            ?: Coordinates(p.x, p.y)
                    } else positions.getValue(id)
                    val status = if (currentHealth <= 0) "dead" else "nominal"
                    val pid = fleet.pids[id]
                    if (previous != null && previous.metrics === metrics && previous.vmState == state &&
                        previous.connectedTo == connected && previous.coordinates == coordinates && previous.status == status && previous.pid == pid) previous
                    else EntitySnapshot(id, pid, descriptor.type, status, metrics, connected, coordinates, instance.parent, state)
                        .also { entitySnapshots[ordinal] = it }
                }
                // The grid and the water network have no program of their own, but an observer has to see them.
                val fixtureStates = kernel?.fixtureState().orEmpty()
                val pumpPower = kernel?.grantedOf("water/pump")
                val fixtures = kernel?.let { world ->
                    val reuseLegacy = legacyFixtureFastPath && previousFixtures === fixtureStates && previousPumpPower == pumpPower
                    val snapshots = if (reuseLegacy) fixtureEntities else fixtureStates.mapNotNull { fixture ->
                        if (fixture.kind == "mine") return@mapNotNull null
                        val fixtureType = when (fixture.kind) {
                            "fence" -> "fence"
                            "air_defense" -> if (world.config.defense.enabled) "air_defense" else "power_node"
                            "ground_turret" -> if (world.config.defense.enabled) "ground_turret" else "power_node"
                            "burner" -> if (world.config.ecosystem.enabled && world.config.ecosystem.plankton.enabled) "burner" else "power_node"
                            "depository" -> if (world.config.creatine.enabled) "depository" else "power_node"
                            "medical_center" -> if (world.config.creatine.enabled) "medical_center" else "power_node"
                            else -> "power_node"
                        }
                        val inFog = world.config.sea.enabled && world.seaController.isFogActive && world.seaController.isInFog(fixture.at)
                        val airUnit = if (fixture.kind == "air_defense" && world.config.defense.enabled) world.airDefenseUnits[fixture.id] else null
                        val groundUnit = if (fixture.kind == "ground_turret" && world.config.defense.enabled) world.groundDefenseUnits[fixture.id] else null
                        val isBroken = fixture.health <= 0.0 || airUnit?.broken == true || groundUnit?.health?.let { it <= 0.0 } == true
                        val status = if (isBroken) "dead" else if (!fixture.powered) "warning" else "nominal"
                        val fixturePumpPower = if (fixture.id == "water/pump") pumpPower else null
                        val creatineStock = when (fixture.kind) {
                            "depository" -> if (world.config.creatine.enabled) world.creatineManager.storedStock else null
                            "medical_center" -> if (world.config.creatine.enabled) world.creatineManager.medicalCenterStock else null
                            else -> null
                        }
                        val airDefensePowered = fixture.powered && (world.grantedOf(fixture.id) >= world.config.defense.powerPerUnit)
                        val refusalReason = airUnit?.let {
                            when {
                                !it.isOperational -> "broken"
                                !airDefensePowered -> "no_power"
                                it.ammoRemaining < 1.0 -> "no_ammo"
                                it.cooldownTimer > 0.0 -> "cooldown"
                                else -> null
                            }
                        }
                        val groundDefensePowered = fixture.powered && world.grantedOf(fixture.id) >= world.config.defense.powerPerUnit
                        val groundRefusalReason = groundUnit?.let {
                            when {
                                !it.isOperational -> "broken"
                                !groundDefensePowered -> "no_power"
                                it.ammoRemaining < 1.0 -> "no_ammo"
                                it.cooldownTimer > 0.0 -> "cooldown"
                                else -> null
                            }
                        }
                        val burnerFuel = if (fixture.kind == "burner" && world.config.ecosystem.enabled && world.config.ecosystem.plankton.enabled)
                            world.ecosystemController.burnFuelRemaining else null
                        val key = FixtureSnapshotKey(fixture.health, fixture.powered, status, fixturePumpPower, creatineStock,
                            airUnit?.broken, airUnit?.ammoRemaining, airUnit?.shotsFired, refusalReason,
                            groundUnit?.health, groundUnit?.ammoRemaining, groundUnit?.shotsFired, groundRefusalReason, burnerFuel, inFog)
                        val previous = fixtureSnapshots[fixture.id]
                        if (previous?.key == key) return@mapNotNull previous.snapshot
                        val metrics = buildJsonObject {
                                put("health", fixture.health)
                                fixturePumpPower?.let { put("power_consumption", it) }
                                creatineStock?.let { put("creatine_stock", it) }
                                if (fixture.kind == "air_defense" && world.config.defense.enabled) {
                                    put("broken", isBroken)
                                    if (airUnit != null) {
                                        put("ammo_remaining", airUnit.ammoRemaining)
                                        put("shots_fired", airUnit.shotsFired)
                                        put("defense_ready", refusalReason == null)
                                        refusalReason?.let { put("refusal_reason", it) }
                                        put("power_connected", airDefensePowered)
                                    }
                                }
                                if (fixture.kind == "ground_turret" && world.config.defense.enabled && groundUnit != null) {
                                    put("health", groundUnit.health)
                                    put("ammo_remaining", groundUnit.ammoRemaining)
                                    put("shots_fired", groundUnit.shotsFired)
                                    put("defense_ready", groundRefusalReason == null)
                                    groundRefusalReason?.let { put("refusal_reason", it) }
                                    put("power_connected", groundDefensePowered)
                                }
                                if (fixture.kind == "burner" && world.config.ecosystem.enabled && world.config.ecosystem.plankton.enabled) {
                                    put("power_connected", fixture.powered && world.grantedOf(fixture.id) > 0.0)
                                    burnerFuel?.let { put("shared_fuel_remaining", it) }
                                }
                                if (inFog) put("in_fog", true)
                            }
                        val snapshot = EntitySnapshot(
                            id = fixture.id, pid = null, type = fixtureType, status = status,
                            metrics = metrics,
                            connectedTo = previous?.snapshot?.connectedTo ?: fixture.feeds,
                            coordinates = previous?.snapshot?.coordinates ?: Coordinates(fixture.at.x, fixture.at.y),
                            parentId = null, vmState = previous?.snapshot?.vmState ?: buildJsonObject {
                                put("kind", fixture.kind)
                                fixture.from?.let { put("from", buildJsonObject { put("x", it.x); put("y", it.y) }) }
                                fixture.to?.let { put("to", buildJsonObject { put("x", it.x); put("y", it.y) }) }
                            },
                        )
                        fixtureSnapshots[fixture.id] = FixtureSnapshotCache(key, snapshot)
                        snapshot
                    }
                    if (legacyFixtureFastPath && !reuseLegacy) {
                        fixtureEntities = snapshots
                        previousFixtures = fixtureStates
                        previousPumpPower = pumpPower
                    }
                    snapshots
                }.orEmpty()
                val sites = kernel?.let { world ->
                    val at = world.minePosition
                    val workers = entities.filter { entity ->
                        entity.type == "civilian" && entity.metrics["health"]?.jsonPrimitive?.double?.let { it > 0 } == true &&
                            entity.vmState["activity"]?.jsonPrimitive?.content == "Mining" && world.vehicleOf(entity.id) == null &&
                            hypot(entity.coordinates.x - at.x, entity.coordinates.y - at.y) < 1.0
                    }
                    val workerIds = workers.map { it.id }
                    val mineStock = if (world.config.creatine.enabled) world.creatineManager.mineStock else null
                    val mineHealth = world.topology.mineFixture?.let { world.healthOf(it.id) }
                    val mineStatus = if (mineHealth != null && mineHealth <= 0.0) "dead" else "nominal"
                    val seaCoastX = world.topology.seaCoastX.takeIf { world.config.sea.enabled || world.config.ecosystem.enabled }
                    val mineKey = SiteSnapshotKey(workerIds, mineStock, mineHealth, mineStatus)
                    val mineSite = mineSnapshot?.takeIf { it.first == mineKey }?.second ?: EntitySnapshot(
                        id = "site/mine", pid = null, type = "mine", status = mineStatus,
                        metrics = buildJsonObject {
                            put("workers", workers.size)
                            mineHealth?.let { put("health", it) }
                            seaCoastX?.let { put("sea_coast_x", it) }
                            val synergy = 1.0 + 9.0 * (workerIds.size.toDouble() / 5000.0).coerceIn(0.0, 1.0)
                            if (world.config.creatine.enabled) {
                                put("creatine_stock", world.creatineManager.mineStock)
                                put("synergy_multiplier", synergy)
                            }
                        },
                        connectedTo = workerIds, coordinates = Coordinates(at.x, at.y),
                        vmState = buildJsonObject { put("shift", if (workers.isEmpty()) "Idle" else "Working") },
                    ).also { mineSnapshot = mineKey to it }
                    val list = mutableListOf(mineSite)
                    if (world.seaController.isFogActive) {
                        val fogCenter = world.seaController.cloudCenter
                        val fogKey = FogSnapshotKey(world.seaController.cloudWidth, world.seaController.cloudHeight,
                            world.seaController.currentFogDepth, fogCenter.x, fogCenter.y)
                        val fog = fogSnapshot?.takeIf { it.first == fogKey }?.second ?: EntitySnapshot(
                            id = "weather/sea_fog",
                            pid = null,
                            type = "fog",
                            status = "nominal",
                            metrics = buildJsonObject {
                                put("width", world.seaController.cloudWidth)
                                put("height", world.seaController.cloudHeight)
                                put("depth", world.seaController.currentFogDepth)
                            },
                            connectedTo = emptyList(),
                            coordinates = Coordinates(fogCenter.x, fogCenter.y),
                            vmState = buildJsonObject {
                                put("active", true)
                            }
                        ).also { fogSnapshot = fogKey to it }
                        list += fog
                    }
                    list
                }.orEmpty()
                val activeCrocodileIds = HashSet<String>()
                val crocodiles = kernel?.crocodilePool?.activeCrocodiles().orEmpty().map { croc ->
                    activeCrocodileIds += croc.id
                    val key = CrocodileSnapshotKey(croc.health, croc.isDeflected, croc.progress, croc.position.x, croc.position.y)
                    crocodileSnapshots[croc.id]?.takeIf { it.first == key }?.second ?: EntitySnapshot(
                        id = croc.id,
                        pid = null,
                        type = "crocodile",
                        status = if (croc.health <= 0) "dead" else if (croc.isDeflected) "warning" else "nominal",
                        metrics = buildJsonObject {
                            put("health", croc.health)
                        },
                        connectedTo = emptyList(),
                        coordinates = Coordinates(croc.position.x, croc.position.y),
                        parentId = null,
                        vmState = buildJsonObject {
                            put("deflected", croc.isDeflected)
                            put("progress", croc.progress)
                        }
                    ).also { crocodileSnapshots[croc.id] = key to it }
                }.also { crocodileSnapshots.keys.retainAll(activeCrocodileIds) }
                val activeZoneIds = HashSet<String>()
                val ecology = kernel?.ecosystemZones.orEmpty().map { zone ->
                    activeZoneIds += zone.id
                    val key = zone
                    ecologySnapshots[zone.id]?.takeIf { it.first == key }?.second ?: EntitySnapshot(
                        id = "ecology/${zone.id}", pid = null, type = "ecology_zone", status = "nominal",
                        metrics = buildJsonObject {
                            put("forest_biomass", zone.forestBiomass)
                            put("manure", zone.manure)
                            put("plankton_biomass", zone.planktonBiomass)
                            put("min_x", zone.bounds.minX); put("min_y", zone.bounds.minY)
                            put("max_x", zone.bounds.maxX); put("max_y", zone.bounds.maxY)
                        },
                        connectedTo = emptyList(),
                        coordinates = Coordinates((zone.bounds.minX + zone.bounds.maxX) / 2.0, (zone.bounds.minY + zone.bounds.maxY) / 2.0),
                        parentId = null,
                        vmState = buildJsonObject { put("zone_id", zone.id) },
                    ).also { ecologySnapshots[zone.id] = key to it }
                }.also { ecologySnapshots.keys.retainAll(activeZoneIds) }
                entities + fixtures + sites + crocodiles + ecology
            } else emptyList()
            return TickSnapshot(runId = runId, runtimeMode = fleet.mode, seed = prepared.scenario.seed.toString(), tickId = tick,
                timestamp = ((tick + 1) * dt * 1000).toLong(), full = captureSnapshot, entities = capturedEntities,
                effects = if (captureSnapshot) intents.mapIndexed { index, it -> TraceEvent(it.source, it.operation.name, it.arguments, it.source in ableToAct, refs[index]) } else emptyList(),
                events = changeEvents + events, postings = kernel?.lastPostings.orEmpty(),
                deliveredEvents = delivered).also {
                    lastTimings = StepTimings((observedAt - startedAt) / 1e6, (executedAt - observedAt) / 1e6,
                        (worldAt - executedAt) / 1e6, (System.nanoTime() - worldAt) / 1e6)
                    tick++
                }
        } catch (failure: Exception) { kernel?.endObservationPhase(); failed = true; close(); throw failure }
    }
}

private val ROVER_METRICS = setOf("passenger_count", "passenger_capacity")
private val MARINE_METRICS = setOf("squad_size")
