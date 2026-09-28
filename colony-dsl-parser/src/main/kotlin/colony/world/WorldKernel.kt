package colony.world

import colony.bytecode.Op
import colony.runtime.Instance
import colony.runtime.RunManifest
import colony.runtime.VmIntent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A fact the kernel established during one step; the run gives it an id, a cause and a place in the journal. */
data class KernelEvent(
    val type: String,
    val entityId: String,
    val actorId: String? = null,
    val fields: JsonObject = JsonObject(emptyMap()),
    val recipients: List<String> = emptyList(),
    /** The request that caused it, as the run names requests. */
    val causeRef: String? = null,
)

/** A fixture of the settlement as an observer sees it: where it stands, whether it works, and what it feeds. */
@Serializable data class FixtureState(
    val id: String, val kind: String, val at: Point, val health: Double, val powered: Boolean, val feeds: List<String>,
)

/** One line of the ledger: who paid, what for, how much, when. */
@Serializable data class Posting(val tick: Long, val owner: String, val kind: String, val amount: Long, val detail: String = "")

/** Work the crews can take: an object that is broken and how far its repair has got. */
@Serializable data class RepairJob(
    val id: String, val target: String, val kind: String, val at: Point,
    val done: Double, val duration: Double, val availableAtTick: Long = 0L,
)

/** A transport request extracted from the language-level SEND event by the reference runtime. */
data class TransportRequest(
    val ref: String, val passengerId: String, val vehicleId: String, val destination: Point,
)

/**
 * The single owner of the physical settlement (docs/simulation/calculations.md). Programs decide what they want;
 * this decides what happens. Every step runs the documented phases in order: check, damage and repair, networks
 * and resources, integration, movement and presence, events.
 */
class WorldKernel(
    private val manifest: RunManifest,
    val config: WorldConfig,
    val topology: Topology,
    private val stepSeconds: Double,
) {
    private val people = Population(manifest)
    private val dt = stepSeconds
    private var elapsedSeconds = 0.0
    private var currentTick = -1L
    private val routineSlots = people.residents.sortedBy { it.id }.mapIndexed { index, resident -> resident.id to index }.toMap()

    // --- state the kernel owns
    private val health = HashMap<String, Double>()
    private val position = HashMap<String, Point>()
    private val insideTemperature = HashMap<String, Double>()
    private val waterTemperature = HashMap<String, Double>()
    private val frostExposure = HashMap<String, Double>()
    private val granted = HashMap<String, Double>()
    private val requested = HashMap<String, Double>()
    private val energyMeter = HashMap<String, Double>()
    private val waterMeter = HashMap<String, Double>()
    private val roundingCarry = HashMap<String, Double>()
    private val jobs = LinkedHashMap<String, RepairJob>()
    private val passengerVehicle = LinkedHashMap<String, String>()
    private val vehiclePassengers = LinkedHashMap<String, LinkedHashSet<String>>()
    private val vehicleRouteTarget = LinkedHashMap<String, Point>()
    private var upsCharge = config.power.upsCapacity
    private var poweredNow = emptySet<String>()
    private var waterNow = emptySet<String>()
    private var occupantsOf = HashMap<String, Int>()
    private val postings = ArrayList<Posting>()
    private val monthlyTotals = HashMap<String, Long>()
    private var lastBilledTick = 0L
    private var lastMonthTick = 0L

    val ledger: List<Posting> get() = postings
    /** What the grid and the water network look like right now, for the dashboard and the journal. */
    fun fixtureState(): List<FixtureState> = topology.fixtures.map { fixture ->
        FixtureState(
            id = fixture.id,
            kind = fixture.kind.name.lowercase(),
            at = fixture.at,
            health = healthOf(fixture.id),
            // A source makes power and a pipe carries water: neither of them draws from the grid.
            powered = when (fixture.kind) {
                FixtureKind.REACTOR, FixtureKind.SOLAR, FixtureKind.UPS, FixtureKind.PIPE -> healthOf(fixture.id) > 0
                else -> isPowered(fixture.id)
            },
            // What hangs off this fixture: the poles and consumers it feeds, or the house a pipe serves.
            feeds = (topology.powerFeed.filterValues { it == fixture.id }.keys +
                topology.fixtures.filter { it.feedsFrom == fixture.id && it.kind == FixtureKind.POLE }.map { it.id } +
                listOfNotNull(fixture.serves)).distinct().sorted(),
        )
    }
    val activeJobs: Collection<RepairJob> get() = jobs.values.filter { it.availableAtTick <= currentTick }
    /** Lines posted during the step that has just finished, for the journal and the dashboard. */
    var lastPostings: List<Posting> = emptyList()
        private set

    /** What an owner has been charged so far, in minimal money units. */
    fun spentBy(owner: String): Long = monthlyTotals[owner] ?: 0L

    init {
        config.validate()
        for (instance in manifest.instances) {
            health[instance.id] = 100.0
            position[instance.id] = Point(instance.x, instance.y)
        }
        for (fixture in topology.fixtures) {
            health[fixture.id] = if (fixture.kind == FixtureKind.POLE) config.power.poleHealth else 100.0
            position[fixture.id] = fixture.at
        }
        for (house in people.houses) {
            insideTemperature[house.id] = config.house.initialTemperature
            frostExposure[topology.waterPipe.getValue(house.id)] = 0.0
        }
        for (appliance in people.appliances) if (appliance.kind == "Kettle") waterTemperature[appliance.id] = 15.0
        recomputeOccupants()
        recomputeNetworks()
    }

    // --- reading the world

    fun healthOf(id: String): Double = health[id] ?: 0.0
    fun positionOf(id: String): Point = position[id] ?: Point(0.0, 0.0)
    fun grantedOf(id: String): Double = granted[id] ?: 0.0
    fun temperatureOf(id: String): Double? = insideTemperature[id] ?: waterTemperature[id]
    fun isPowered(id: String): Boolean = id in poweredNow
    fun hasWater(id: String): Boolean = id in waterNow
    fun occupants(houseId: String): Int = occupantsOf[houseId] ?: 0
    fun isBroken(id: String): Boolean = healthOf(id) <= 0.0
    fun monthlyReport(): Map<String, Long> = monthlyTotals.toMap()

    /** The values of the observations a program reads, computed from the physical state of this moment. */
    fun view(instance: Instance, fields: Set<String>): Map<String, JsonElement> {
        val id = instance.id
        val out = LinkedHashMap<String, JsonElement>()
        val houseId = if (instance.kind == "House") id else instance.parent
        for (field in fields) {
            val value: JsonElement = when (field) {
                "occupants" -> JsonPrimitive(occupants(id))
                "home_occupants" -> JsonPrimitive(houseId?.let(::occupants) ?: 0)
                "temperature" -> JsonPrimitive(insideTemperature[id] ?: config.house.initialTemperature)
                "water_temperature" -> JsonPrimitive(waterTemperature[id] ?: 15.0)
                "power_connected" -> JsonPrimitive(isPowered(id))
                "water_available" -> JsonPrimitive(hasWater(id))
                "broken" -> JsonPrimitive(isBroken(id))
                "power_granted" -> JsonPrimitive(grantedOf(id))
                "health" -> JsonPrimitive(healthOf(id))
                "position" -> pointJson(positionOf(id))
                "cold" -> JsonPrimitive((insideTemperature[houseId] ?: config.house.initialTemperature) < config.house.coldThreshold)
                "devices" -> JsonArray(people.childrenOf[id].orEmpty().filter { it.kind == "Heater" || it.kind == "Kettle" }
                    .sortedBy { it.id }
                    .map { buildJsonObject { put("id", it.id); put("kind", it.kind); put("broken", isBroken(it.id)) } })
                "reachable_breakables" -> targets(id, reachableAppliances(instance))
                // The substation is fenced off; what stands in the open is the distribution poles.
                "visible_infrastructure" -> targets(id, topology.poles.filter { it.id != BUS && !isBroken(it.id) }.map { it.id })
                "visible_humans" -> targets(id, people.residents.filter { !isBroken(it.id) }.map { it.id })
                "patrol_waypoint" -> pointJson(patrolPoint(id))
                "home" -> pointJson(positionOf(instance.parent ?: id))
                "workplace" -> pointJson(if ((routineSlots[id] ?: 0) % 4 == 2) servicePoint else minePoint)
                "meeting_point" -> pointJson(meetingPoint)
                "routine_slot" -> JsonPrimitive(routineSlots[id] ?: 0)
                "day_minute" -> JsonPrimitive((config.human.startMinute + (elapsedSeconds / 60).toLong()) % 1440)
                "depot" -> pointJson(depotOf(instance))
                "active_jobs" -> jobList(id)
                "materials_remaining" -> JsonPrimitive(config.repair.materials)
                "speed_eff" -> JsonPrimitive(config.repair.roverSpeed)
                "passenger_count" -> JsonPrimitive(vehiclePassengers[id]?.size ?: 0)
                "passenger_capacity" -> JsonPrimitive(config.transport.passengerCapacity)
                "transport_ready" -> JsonPrimitive(transportReady(id))
                "transport_target" -> pointJson(vehicleRouteTarget[id] ?: depotOf(instance))
                "available_vehicles" -> targets(id, availableTransportVehicles())
                "in_vehicle" -> JsonPrimitive(id in passengerVehicle)
                "dispatch_ready" -> JsonPrimitive(elapsedSeconds >= config.marine.responseDelaySeconds)
                "visible_xenomorphs" -> targets(id, people.byId.values.filter { it.kind == "Xenomorph" && !isBroken(it.id) }.map { it.id })
                "squad_size" -> JsonPrimitive(marineSquad(id).count { !isBroken(it.id) })
                "squad_leader" -> JsonPrimitive(isMarineLeader(id))
                "squad_ready" -> JsonPrimitive(squadReady(id))
                else -> error("The world does not compute observation '$field'")
            }
            out[field] = value
        }
        return out
    }

    /** The jobs a crew can see, nearest first, with the distance the crew has to drive. */
    private fun jobList(observerId: String): JsonArray {
        val here = positionOf(observerId)
        return JsonArray(jobs.values
            .filter { it.availableAtTick <= currentTick }
            .sortedWith(compareBy({ here.distanceTo(it.at) }, { it.target }))
            .take(config.sight.listLimit)
            .map { job ->
                buildJsonObject {
                    put("id", job.id); put("object", job.target); put("kind", job.kind)
                    put("position", pointJson(job.at)); put("distance", here.distanceTo(job.at))
                    put("progress", job.done / job.duration)
                }
            })
    }

    private fun availableTransportVehicles(): List<String> = people.rovers
        .filter {
            !isBroken(it.id) &&
                (vehiclePassengers[it.id]?.isEmpty() != false) &&
                positionOf(it.id).distanceTo(depotOf(it)) <= config.transport.boardRadius
        }
        .map { it.id }

    private fun marineSquad(marineId: String): List<Instance> {
        val marine = people.byId[marineId] ?: return emptyList()
        if (marine.kind != "Marine") return emptyList()
        val squad = marine.params["squad"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return people.marines.filter { it.params["squad"]?.jsonPrimitive?.contentOrNull == squad }
    }

    private fun isMarineLeader(marineId: String): Boolean =
        people.byId[marineId]?.params?.get("leader")?.jsonPrimitive?.booleanOrNull ?: false

    private fun squadReady(marineId: String): Boolean {
        val target = nearestVisibleXenomorph(marineId) ?: return false
        val squad = marineSquad(marineId).filter { !isBroken(it.id) }
        if (squad.size !in config.marine.minSquadSize..config.marine.maxSquadSize) return false
        return squad.all { positionOf(it.id).distanceTo(positionOf(target)) <= config.marine.assaultRadius }
    }

    private fun nearestVisibleXenomorph(marineId: String): String? {
        val here = positionOf(marineId)
        return people.byId.values.asSequence()
            .filter { it.kind == "Xenomorph" && !isBroken(it.id) }
            .map { it.id to here.distanceTo(positionOf(it.id)) }
            .filter { it.second <= config.sight.sightRadius }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .firstOrNull()?.first
    }

    private fun transportReady(vehicleId: String): Boolean {
        val passengers = vehiclePassengers[vehicleId].orEmpty().filter { !isBroken(it) }
        if (passengers.isEmpty()) return false
        val marineGroups = passengers.mapNotNull { passenger ->
            people.byId[passenger]?.takeIf { it.kind == "Marine" }?.params?.get("squad")?.jsonPrimitive?.contentOrNull
        }.toSet()
        for (group in marineGroups) {
            val members = people.marines.filter {
                it.params["squad"]?.jsonPrimitive?.contentOrNull == group && !isBroken(it.id)
            }
            if (members.size !in config.marine.minSquadSize..config.marine.maxSquadSize ||
                members.any { it.id !in passengers }) return false
        }
        return true
    }

    private fun unboard(vehicleId: String, events: MutableList<KernelEvent>, reason: String? = null, ref: String? = null) {
        val passengers = vehiclePassengers.remove(vehicleId).orEmpty().toList()
        vehicleRouteTarget.remove(vehicleId)
        for (passenger in passengers) {
            passengerVehicle.remove(passenger)
            events += KernelEvent(
                "ActionSucceeded", passenger, vehicleId,
                buildJsonObject { put("action", "transport.alight"); reason?.let { put("reason", it) } },
                listOf(passenger, vehicleId), ref,
            )
        }
    }

    /** Apply language-level ride requests without adding a VM opcode: SEND remains the transport mechanism. */
    private fun applyTransportRequests(requests: List<TransportRequest>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        for (request in requests) {
            val passenger = people.byId[request.passengerId]
            val vehicle = people.byId[request.vehicleId]
            val reason = when {
                passenger == null -> "unknown_passenger"
                passenger.kind != "Human" && passenger.kind != "Marine" -> "passenger_not_transportable"
                request.passengerId !in accepted || isBroken(request.passengerId) -> "passenger_unable"
                vehicle == null -> "unknown_vehicle"
                vehicle.kind != "Rover" -> "target_not_rover"
                isBroken(request.vehicleId) -> "vehicle_broken"
                passengerVehicle.containsKey(request.passengerId) -> "already_in_vehicle"
                positionOf(request.passengerId).distanceTo(positionOf(request.vehicleId)) > config.transport.boardRadius -> "out_of_boarding_range"
                (vehiclePassengers[request.vehicleId]?.size ?: 0) >= config.transport.passengerCapacity -> "vehicle_full"
                vehicleRouteTarget[request.vehicleId]?.distanceTo(request.destination)?.let { it > 1e-6 } == true -> "vehicle_already_committed"
                else -> null
            }
            if (reason != null) {
                events += KernelEvent(
                    "ActionRejected", request.passengerId, request.passengerId,
                    buildJsonObject { put("action", "transport.board"); put("reason", reason) },
                    listOf(request.passengerId), request.ref,
                )
                continue
            }
            vehiclePassengers.getOrPut(request.vehicleId) { LinkedHashSet() }.add(request.passengerId)
            passengerVehicle[request.passengerId] = request.vehicleId
            vehicleRouteTarget.putIfAbsent(request.vehicleId, request.destination)
            events += KernelEvent(
                "ActionSucceeded", request.passengerId, request.vehicleId,
                buildJsonObject { put("action", "transport.board"); put("vehicle", request.vehicleId) },
                listOf(request.passengerId, request.vehicleId), request.ref,
            )
        }
    }

    /** Routine destinations: mine outside the housing area, services and a meeting place in the colony. */
    private val minePoint = Point(people.houses.maxOf { it.x } + 120.0, people.houses.map { it.y }.average())
    private val servicePoint = Point(people.houses.map { it.x }.average(), people.houses.minOf { it.y } - 90.0)
    private val meetingPoint = Point(servicePoint.x, servicePoint.y + 45.0)

    /** A crew returns to where its manifest put it. */
    private fun depotOf(instance: Instance): Point = Point(instance.x, instance.y)

    private fun reachableAppliances(instance: Instance): List<String> {
        val here = positionOf(instance.id)
        return people.appliances.filter { !isBroken(it.id) && positionOf(it.id).distanceTo(here) <= config.human.vandalRadius }.map { it.id }
    }

    /** Observed objects within sight, ordered by distance and then by id, as the contract requires. */
    private fun targets(observerId: String, candidates: List<String>): JsonArray {
        val here = positionOf(observerId)
        return JsonArray(candidates.asSequence()
            .map { it to positionOf(it).distanceTo(here) }
            .filter { it.second <= config.sight.sightRadius }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(config.sight.listLimit)
            .map { (id, distance) ->
                buildJsonObject {
                    put("id", id); put("kind", kindOf(id)); put("position", pointJson(positionOf(id)))
                    put("distance", distance); put("health", healthOf(id))
                }
            }.toList())
    }

    private fun kindOf(id: String): String =
        people.byId[id]?.kind ?: topology.byId[id]?.kind?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Unknown"

    /**
     * Each patroller walks a lap of the settlement: four corners in turn. Arriving moves it on to the next one,
     * which is what the specification means by a waypoint that updates on ArrivalConfirmed.
     */
    private val patrolCorners: List<Point> = run {
        val minX = topology.fixtures.minOf { it.at.x } - PATROL_MARGIN
        val maxX = topology.fixtures.maxOf { it.at.x } + PATROL_MARGIN
        val minY = topology.fixtures.minOf { it.at.y } - PATROL_MARGIN
        val maxY = topology.fixtures.maxOf { it.at.y } + PATROL_MARGIN
        listOf(Point(minX, minY), Point(maxX, minY), Point(maxX, maxY), Point(minX, maxY))
    }
    private val patrolLeg = HashMap<String, Int>()

    private fun patrolPoint(id: String): Point =
        patrolCorners[Math.floorMod(patrolLeg.getOrPut(id) { Math.floorMod(id.hashCode(), patrolCorners.size) }, patrolCorners.size)]

    private fun advancePatrol(id: String) {
        patrolLeg[id] = Math.floorMod(patrolLeg.getOrDefault(id, 0) + 1, patrolCorners.size)
    }

    private fun pointJson(point: Point) = buildJsonObject { put("x", point.x); put("y", point.y) }

    // --- one step of the world

    /**
     * Applies the requests of one step in the order the specification fixes and returns what the world
     * established. [accepted] names the entities that were able to act at the start of the step.
     */
    fun step(
        tick: Long, intents: List<VmIntent>, refs: List<String>, accepted: Set<String>,
        transportRequests: List<TransportRequest> = emptyList(),
    ): List<KernelEvent> {
        currentTick = tick
        val events = ArrayList<KernelEvent>()
        val postedBefore = postings.size
        val poweredBefore = poweredNow
        val waterBefore = waterNow

        applyDamage(tick, intents, refs, accepted, events)
        applyTransportRequests(transportRequests, accepted, events)
        applyRepairs(tick, intents, accepted, events)
        recomputeNetworks()
        distributePower(intents, accepted)
        integrate(tick, events)
        applyMovement(intents, accepted, events)
        recomputeOccupants()
        recomputeNetworks()
        reportChanges(poweredBefore, waterBefore, events)
        bill(tick, events)
        lastPostings = if (postings.size > postedBefore) postings.subList(postedBefore, postings.size).toList() else emptyList()
        elapsedSeconds += dt
        return events
    }

    /** Phase 2. Simultaneous attacks are summed against the health at the start of the step. */
    private fun applyDamage(tick: Long, intents: List<VmIntent>, refs: List<String>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        intents.forEachIndexed { index, intent ->
            if (intent.operation != Op.DAMAGE_REQUEST || intent.source !in accepted) return@forEachIndexed
            val target = intent.arguments[0].jsonPrimitive.content
            if (target !in health) return@forEachIndexed
            val amount = intent.arguments[1].jsonPrimitive.double
            val reason = intent.arguments[2].jsonPrimitive.content
            val before = health.getValue(target)
            health[target] = (before - amount).coerceAtLeast(0.0)
            val owner = ownerOf(target)
            events += KernelEvent("ActionSucceeded", intent.source, intent.source,
                buildJsonObject { put("action", "damage") }, listOf(intent.source), refs[index])
            events += KernelEvent("DamageApplied", target, intent.source,
                buildJsonObject { put("target", target); put("amount", amount); put("reason", reason) },
                listOfNotNull(intent.source, owner).distinct(), refs[index])
            if (before > 0 && health.getValue(target) <= 0) {
                events += breakOf(tick, target, reason, intent.source)
                if (people.byId[target]?.kind == "Rover") unboard(target, events, reason = "vehicle_broken", ref = refs[index])
            }
        }
    }

    /** A broken object becomes a job for the crews; a dead person or creature is only a fact of the world. */
    private fun breakOf(tick: Long, target: String, reason: String, actor: String?): KernelEvent {
        val instance = people.byId[target]
        if (instance != null && (instance.kind == "Human" || instance.kind == "Xenomorph")) {
            return KernelEvent("EntityDied", target, actor, buildJsonObject { put("entity", target) })
        }
        openJob(target, tick)
        return KernelEvent("ObjectBroken", target, actor,
            buildJsonObject { put("object", target); put("reason", reason) }, listOfNotNull(ownerOf(target)))
    }

    private fun openJob(target: String, brokenAtTick: Long) {
        if (target in jobs) return
        val kind = topology.byId[target]?.kind?.repairKey ?: "device"
        val delayTicks = kotlin.math.ceil(config.repair.dispatchDelaySeconds / dt).toLong()
        jobs[target] = RepairJob(
            "job/${jobs.size + 1}", target, kind, positionOf(target), 0.0, config.repair.durationOf(kind),
            availableAtTick = brokenAtTick + delayTicks,
        )
    }

    /** The house that pays for an object: its own house for an appliance, the settlement for the grid. */
    private fun ownerOf(target: String): String? = people.byId[target]?.parent ?: people.byId[target]?.id?.takeIf { people.byId[it]?.kind == "House" }

    /** Phase 2. A crew makes progress while it stands next to the object it asked to repair. */
    private fun applyRepairs(tick: Long, intents: List<VmIntent>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        for (intent in intents) {
            if (intent.operation != Op.REPAIR_REQUEST || intent.source !in accepted) continue
            val target = intent.arguments[0].jsonPrimitive.content
            val job = jobs[target]
            if (job == null || job.availableAtTick > currentTick) {
                events += KernelEvent("RepairRejected", intent.source, intent.source,
                    buildJsonObject { put("reason", "no_such_job") }, listOf(intent.source))
                continue
            }
            if (positionOf(intent.source).distanceTo(job.at) > config.repair.workRadius) {
                events += KernelEvent("RepairRejected", intent.source, intent.source,
                    buildJsonObject { put("reason", "out_of_range") }, listOf(intent.source))
                continue
            }
            val done = job.done + dt
            if (done < job.duration) { jobs[target] = job.copy(done = done); continue }
            jobs.remove(target)
            health[target] = if (topology.byId[target]?.kind == FixtureKind.POLE) config.power.poleHealth else 100.0
            frostExposure[target]?.let { frostExposure[target] = 0.0 }
            val cost = config.repair.partsOf(job.kind) + Math.round(config.repair.hourlyRate * job.duration / 3600.0)
            post(tick, ownerOf(target) ?: "settlement", "repair", cost, target)
            events += KernelEvent("RepairCompleted", target, intent.source,
                buildJsonObject { put("object", target) }, listOfNotNull(intent.source, ownerOf(target)).distinct())
        }
    }

    /** Phase 3. Power reaches an object when a healthy path of healthy fixtures joins it to a working source. */
    private fun recomputeNetworks() {
        val working = topology.sources.filter { !isBroken(it) && sourcePower(it) > 0.0 }
        val live = HashSet<String>()
        if (working.isNotEmpty() && !isBroken(BUS)) {
            // The grid is a tree: a source feeds the substation, it feeds the poles, a pole feeds its houses.
            live += BUS
            for (pole in topology.poles) if (pole.id != BUS && !isBroken(pole.id) && pole.feedsFrom == BUS) live += pole.id
        }
        // Everything the grid feeds: the entities with programs and the fixtures of the settlement, such as the pump.
        poweredNow = topology.powerFeed.entries
            .filter { (id, feed) -> feed in live && !isBroken(id) }
            .mapTo(HashSet()) { it.key } + live
        val pumpRunning = PUMP in poweredNow && !isBroken(PUMP)
        waterNow = topology.houses.filter { house ->
            pumpRunning && !isBroken(topology.waterPipe.getValue(house))
        }.toSet()
    }

    private fun sourcePower(id: String): Double = when (topology.byId[id]?.kind) {
        FixtureKind.REACTOR -> config.power.reactorPower
        FixtureKind.SOLAR -> config.power.solarPeak
        FixtureKind.UPS -> config.power.upsMaxPower
        else -> 0.0
    }

    /** Phase 3. Classes are served in order; the class that runs out is cut in proportion to what it asked for. */
    private fun distributePower(intents: List<VmIntent>, accepted: Set<String>) {
        requested.clear()
        for (intent in intents) {
            if (intent.operation != Op.POWER_REQUEST || intent.source !in accepted) continue
            if (isBroken(intent.source) || !isPowered(intent.source)) continue
            requested[intent.source] = intent.arguments[0].jsonPrimitive.double
        }
        // The pump is part of the settlement rather than a program, so the kernel asks for it.
        if (isPowered(PUMP) && !isBroken(PUMP)) requested[PUMP] = config.water.pumpPower

        val solar = config.power.solarPeak * climateSolar()
        val reactor = if (isBroken("grid/reactor")) 0.0 else config.power.reactorPower
        val fromUps = if (isBroken("grid/ups")) 0.0 else minOf(config.power.upsMaxPower, upsCharge * config.power.upsEfficiency / dt)
        var budget = reactor + (if (isBroken("grid/solar")) 0.0 else solar)
        granted.clear()
        val byClass = requested.entries
            .sortedWith(compareBy({ config.power.priorityOf(kindOf(it.key)) }, { it.key }))
            .groupBy { config.power.priorityOf(kindOf(it.key)) }
        var usedUps = 0.0
        for (klass in byClass.keys.sorted()) {
            val entries = byClass.getValue(klass)
            val wanted = entries.sumOf { it.value }
            if (wanted <= budget) {
                entries.forEach { granted[it.key] = it.value }
                budget -= wanted
                continue
            }
            // The battery covers what the grid cannot, then the class that is still short is cut in proportion.
            val fromBattery = minOf(fromUps - usedUps, wanted - budget)
            usedUps += fromBattery
            val available = budget + fromBattery
            val share = if (wanted > 0) available / wanted else 0.0
            entries.forEach { granted[it.key] = it.value * share }
            budget = 0.0
            for (rest in byClass.keys.sorted().filter { it > klass }) byClass.getValue(rest).forEach { granted[it.key] = 0.0 }
            break
        }
        upsCharge = if (usedUps > 0) {
            (upsCharge - usedUps * dt / config.power.upsEfficiency).coerceAtLeast(0.0)
        } else {
            (upsCharge + minOf(config.power.upsMaxPower, budget) * dt * config.power.upsEfficiency).coerceAtMost(config.power.upsCapacity)
        }
    }

    private fun climateSolar(): Double = config.climate.solarFraction(currentSeconds)
    private var currentSeconds = 0.0

    /** Phase 4. Heat, kettles, frost in the pipes, what people suffer, and the meters. */
    private fun integrate(tick: Long, events: MutableList<KernelEvent>) {
        currentSeconds = tick * dt
        val outside = config.climate.outsideTemperature(currentSeconds)
        for (house in people.houses) {
            val heaters = people.childrenOf[house.id].orEmpty().filter { it.kind == "Heater" }
            val heat = config.house.heaterEfficiency * heaters.sumOf { grantedOf(it.id) }
            val conductance = config.house.conductance
            val equilibrium = outside + heat / conductance
            val before = insideTemperature.getValue(house.id)
            // The closed form of the linear model: stable for any step, unlike an explicit Euler step.
            val after = equilibrium + (before - equilibrium) * Math.exp(-conductance * dt / config.house.capacity)
            insideTemperature[house.id] = after

            val pipe = topology.waterPipe.getValue(house.id)
            if (!isBroken(pipe)) {
                val exposure = (frostExposure.getValue(pipe) + (0.0 - after) * dt).coerceAtLeast(0.0)
                frostExposure[pipe] = exposure
                if (exposure >= config.water.freezeThresholdIndoor) {
                    health[pipe] = 0.0
                    frostExposure[pipe] = 0.0
                    events += breakOf(tick, pipe, "freezing", null)
                }
            }
            if (hasWater(house.id) && occupants(house.id) > 0) {
                waterMeter.merge(house.id, config.water.houseDemand * dt, Double::plus)
            }
        }
        for (kettle in people.appliances.filter { it.kind == "Kettle" }) {
            val ambient = insideTemperature[kettle.parent] ?: config.house.initialTemperature
            val heat = grantedOf(kettle.id) - config.house.kettleHeatLoss * (waterTemperature.getValue(kettle.id) - ambient)
            waterTemperature[kettle.id] = (waterTemperature.getValue(kettle.id) + heat * dt / config.house.kettleCapacity).coerceIn(-50.0, 100.0)
        }
        for (appliance in people.appliances) {
            val owner = appliance.parent ?: continue
            energyMeter.merge(owner, grantedOf(appliance.id) * dt, Double::plus)
        }
        for (resident in people.residents) {
            if (isBroken(resident.id) || resident.id in passengerVehicle) continue
            val inside = insideTemperature[resident.parent] ?: config.house.initialTemperature
            val deficit = config.human.harmThreshold - inside
            if (deficit <= 0) continue
            val before = healthOf(resident.id)
            val after = (before - config.human.harmPerKelvinSecond * deficit * dt).coerceAtLeast(0.0)
            health[resident.id] = after
            if (before > 0 && after <= 0) events += breakOf(tick, resident.id, "cold", null)
        }
    }

    /** Phase 5. Movement in a straight line at the speed asked for, then who is where. */
    private fun applyMovement(intents: List<VmIntent>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        val deadPassengers = passengerVehicle.keys.filter { isBroken(it) }.toList()
        for (passenger in deadPassengers) passengerVehicle.remove(passenger)
        vehiclePassengers.values.forEach { it.removeAll(deadPassengers.toSet()) }
        people.rovers.forEach { rover ->
            val route = vehicleRouteTarget[rover.id]
            if (!isBroken(rover.id) && vehiclePassengers[rover.id]?.isNotEmpty() == true && route != null &&
                route.distanceTo(positionOf(rover.id)) <= 1.0
            ) {
                unboard(rover.id, events)
            }
        }
        for (intent in intents) {
            if (intent.operation != Op.MOTION_REQUEST || intent.source !in accepted || isBroken(intent.source)) continue
            val target = intent.arguments[0].jsonObject
            val goal = Point(target.getValue("x").jsonPrimitive.double, target.getValue("y").jsonPrimitive.double)
            val speed = intent.arguments[1].jsonPrimitive.double
            val here = positionOf(intent.source)
            val distance = here.distanceTo(goal)
            if (distance <= 1e-9) continue
            val ratio = minOf(1.0, speed * dt / distance)
            val newPosition = Point(here.x + (goal.x - here.x) * ratio, here.y + (goal.y - here.y) * ratio)
            position[intent.source] = newPosition

            // Riders occupy the rover's physical position, not their previous world coordinates.
            if (people.rovers.any { it.id == intent.source }) {
                vehiclePassengers[intent.source].orEmpty().forEach { passenger -> position[passenger] = newPosition }
                val route = vehicleRouteTarget[intent.source]
                if (ratio >= 1.0 && route != null && route.distanceTo(newPosition) <= 1.0) {
                    unboard(intent.source, events)
                }
            }
            if (ratio >= 1.0) {
                events += KernelEvent("ArrivalConfirmed", intent.source, intent.source,
                    buildJsonObject { put("point", pointJson(goal)) }, listOf(intent.source))
                // A patroller that reached its corner is sent on to the next one.
                if (people.byId[intent.source]?.kind == "Xenomorph" && goal.distanceTo(patrolPoint(intent.source)) < 1.0) advancePatrol(intent.source)
            }
        }
    }

    private fun recomputeOccupants() {
        val counts = HashMap<String, Int>()
        for (house in people.houses) counts[house.id] = 0
        for (resident in people.residents) {
            if (isBroken(resident.id)) continue
            val home = resident.parent ?: continue
            if (positionOf(resident.id).distanceTo(positionOf(home)) <= HOUSE_ZONE) counts.merge(home, 1, Int::plus)
        }
        occupantsOf = counts
    }

    /** Phase 6. Only a change of state is an event; a steady supply says nothing. */
    private fun reportChanges(poweredBefore: Set<String>, waterBefore: Set<String>, events: MutableList<KernelEvent>) {
        for (house in people.houses) {
            val id = house.id
            val had = id in poweredBefore
            val has = id in poweredNow
            if (had != has) {
                val recipients = listOf(id) + people.childrenOf[id].orEmpty().map { it.id }
                events += KernelEvent(if (has) "PowerRestored" else "PowerLost", id, null, JsonObject(emptyMap()), recipients)
            }
            val hadWater = id in waterBefore
            val hasWater = id in waterNow
            if (hadWater != hasWater) {
                events += KernelEvent(if (hasWater) "WaterRestored" else "WaterLost", id, null, JsonObject(emptyMap()), listOf(id))
            }
        }
    }

    /** Consumption is metered every step and posted once an hour, carrying the rounding remainder forward. */
    private fun bill(tick: Long, events: MutableList<KernelEvent>) {
        val seconds = (tick + 1) * dt
        val interval = config.tariffs.billingIntervalSeconds
        if (seconds < (lastBilledTick + 1) * interval) return
        lastBilledTick = (seconds / interval).toLong()
        for (house in people.houses) {
            val joules = energyMeter.remove(house.id) ?: 0.0
            val cubic = waterMeter.remove(house.id) ?: 0.0
            postRounded(tick, house.id, "electricity", joules / 3.6e6 * config.tariffs.electricityPerKwh)
            postRounded(tick, house.id, "water", cubic * config.tariffs.waterPerCubicMetre)
        }
        val month = config.tariffs.monthSeconds
        if (seconds >= (lastMonthTick + 1) * month) {
            lastMonthTick = (seconds / month).toLong()
            events += KernelEvent("MonthClosed", "settlement", null,
                buildJsonObject { put("month", lastMonthTick); put("total", monthlyTotals.values.sum()) })
        }
    }

    /** Rounding to whole money units carries its remainder, so the sum of the postings matches the exact cost. */
    private fun postRounded(tick: Long, owner: String, kind: String, exact: Double) {
        if (exact <= 0.0) return
        val key = "$owner/$kind"
        val carried = roundingCarry.getOrDefault(key, 0.0) + exact
        val whole = Math.floor(carried).toLong()
        roundingCarry[key] = carried - whole
        if (whole > 0) post(tick, owner, kind, whole)
    }

    private fun post(tick: Long, owner: String, kind: String, amount: Long, detail: String = "") {
        postings += Posting(tick, owner, kind, amount, detail)
        monthlyTotals.merge(owner, amount, Long::plus)
        if (postings.size > MAX_POSTINGS) postings.subList(0, postings.size - MAX_POSTINGS).clear()
    }

    private companion object {
        /** How close a resident has to be for the house to count them as present. */
        const val HOUSE_ZONE = 25.0
        /** How far outside the settlement a patrol route runs. */
        const val PATROL_MARGIN = 30.0
        const val BUS = "grid/bus"
        const val PUMP = "water/pump"
        const val MAX_POSTINGS = 20_000
    }
}
