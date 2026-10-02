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
    /** The ends of a fence segment. */
    val from: Point? = null, val to: Point? = null,
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
 * The single owner of the physical settlement (docs/technical-reference.md#world). Programs decide what they want;
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
    // Static topology is indexed once; snapshot creation must not scan the city for every fixture.
    private val fixtureFeeds: Map<String, List<String>> = run {
        val feeds = HashMap<String, MutableList<String>>()
        topology.powerFeed.forEach { (id, feed) -> feeds.getOrPut(feed) { ArrayList() }.add(id) }
        topology.fixtures.forEach { fixture ->
            if (fixture.kind == FixtureKind.POLE) fixture.feedsFrom?.let { feeds.getOrPut(it) { ArrayList() }.add(fixture.id) }
            fixture.serves?.let { feeds.getOrPut(fixture.id) { ArrayList() }.add(it) }
        }
        feeds.mapValues { (_, ids) -> ids.distinct().sorted() }
    }
    // Appliances never move. Query nearby cells, then apply the original exact distance/health predicates.
    private val applianceCellSize = maxOf(1.0, config.human.vandalRadius)
    private fun applianceCell(p: Point) = kotlin.math.floor(p.x / applianceCellSize).toLong() to
        kotlin.math.floor(p.y / applianceCellSize).toLong()
    private val applianceCells = people.appliances.groupBy { applianceCell(Point(it.x, it.y)) }
    private val powerClasses = (manifest.instances.map { it.id to it.kind } + (PUMP to "Pump"))
        .groupBy { config.power.priorityOf(it.second) }.toSortedMap()
        .mapValues { (_, consumers) -> consumers.map { it.first }.sorted() }
    private val dt = stepSeconds
    private var elapsedSeconds = 0.0
    private var currentTick = -1L
    private val routineSlots = people.residents.sortedBy { it.id }.mapIndexed { index, resident -> resident.id to index }.toMap()
    private val deviceChildren = people.childrenOf.mapValues { (_, children) ->
        children.filter { it.kind == "Heater" || it.kind == "Kettle" }.sortedBy { it.id }
    }
    private val deviceViews = HashMap<String, Pair<List<Boolean>, JsonArray>>()
    private val homeViews = people.houses.associate { it.id to pointJson(Point(it.x, it.y)) }
    private val workplaceViews = listOf(topology.mine, topology.services, topology.medicalCenter).associateWith(::pointJson)
    private val meetingView = pointJson(topology.meeting)
    private val crewDepots: Map<String, Point> = run {
        val crews = people.rovers.filter { it.id.startsWith("crew-") }.sortedBy { it.id }
        val transports = people.rovers.filter { it.id.startsWith("transport-") }.sortedBy { it.id }
        val cargos = people.rovers.filter { it.id.startsWith("cargo-") }.sortedBy { it.id }
        if (crews.size <= 4 || people.houses.size < 100) {
            emptyMap()
        } else {
            val minX = people.houses.minOf { it.x }
            val maxX = people.houses.maxOf { it.x }
            val minY = people.houses.minOf { it.y }
            val maxY = people.houses.maxOf { it.y }
            val map = HashMap<String, Point>()
            // 50 Repair rovers: 5 cols x 10 rows sector grid across entire settlement
            val cols = 5
            val rows = maxOf(1, (crews.size + cols - 1) / cols)
            val stepX = if (cols > 1) (maxX - minX) / (cols - 1) else 0.0
            val stepY = if (rows > 1) (maxY - minY) / (rows - 1) else 0.0
            crews.forEachIndexed { index, rover ->
                val col = index % cols
                val row = (index / cols) % rows
                map[rover.id] = Point(minX + col * stepX, minY + row * stepY)
            }
            // 50 Passenger rovers: 5 cols x 10 rows grid across residential sectors
            val tCols = 5
            val tRows = maxOf(1, (transports.size + tCols - 1) / tCols)
            val tStepX = if (tCols > 1) (maxX - minX) / (tCols - 1) else 0.0
            val tStepY = if (tRows > 1) (maxY - minY) / (tRows - 1) else 0.0
            transports.forEachIndexed { index, rover ->
                val col = index % tCols
                val row = (index / tCols) % tRows
                map[rover.id] = Point(minX + col * tStepX, minY + row * tStepY)
            }
            // 50 Resource/Creatine rovers: 25 near Mine, 25 near Depository
            val halfCargo = cargos.size / 2
            cargos.forEachIndexed { index, rover ->
                if (index < halfCargo) {
                    map[rover.id] = Point(topology.mine.x - 20.0 - (index % 5) * 6.0, topology.mine.y - 15.0 + (index / 5) * 6.0)
                } else {
                    val sub = index - halfCargo
                    map[rover.id] = Point(topology.depository.x - 20.0 - (sub % 5) * 6.0, topology.depository.y - 15.0 + (sub / 5) * 6.0)
                }
            }
            map
        }
    }
    private fun depotOf(instance: Instance): Point = crewDepots[instance.id] ?: Point(instance.x, instance.y)
    private val depotViews = people.rovers.associate { it.id to pointJson(depotOf(it)) }

    private fun devicesOf(id: String): JsonArray {
        val children = deviceChildren[id].orEmpty()
        val broken = children.map { isBroken(it.id) }
        deviceViews[id]?.takeIf { it.first == broken }?.let { return it.second }
        val view = JsonArray(children.mapIndexed { index, child -> buildJsonObject {
            put("id", child.id); put("kind", child.kind); put("broken", broken[index])
        } })
        deviceViews[id] = broken to view
        return view
    }

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
    private val squadTargets = HashMap<String, String>()
    /** Residents walking to each rover during the latest step, and when its first resident got on. */
    private var walkersOf: Map<String, List<String>> = emptyMap()
    private val boardingSince = HashMap<String, Double>()
    /** The closed fence segment a xenomorph last ran into. */
    private val blockedBy = HashMap<String, String>()
    /** Xenomorphs a squad has driven off, and until when they keep away. */
    private val routedUntil = HashMap<String, Double>()
    private var nextJobId = 0L
    private var upsCharge = config.power.upsCapacity
    private var poweredNow = emptySet<String>()
    private var waterNow = emptySet<String>()
    private var occupantsOf = HashMap<String, Int>()
    private val postings = ArrayList<Posting>()
    private val monthlyTotals = HashMap<String, Long>()
    private var lastBilledTick = 0L
    private var lastMonthTick = 0L

    val spatialIndex = SpatialIndex(cellSize = 60.0)
    val seaController = SeaController(topology.seaCoastX, config.sea)
    val creatineManager = CreatineManager(config.creatine)
    val crocodilePool = CrocodilePool(128)
    val airDefenseUnits = HashMap<String, AirDefenseUnit>()
    val taskQueue = TaskQueue(spatialIndex)
    val respiratorEquipped = HashMap<String, Boolean>()
    val roverCreatineCargo = HashMap<String, Double>()
    val workerCreatineCargo = HashMap<String, Double>()
    val roverLogisticsTarget = HashMap<String, String>()
    var totalCrocodilesSpawned = 0L
    var totalCrocodilesDeflected = 0L
    var totalCrocodilesDowned = 0L

    val ledger: List<Posting> get() = postings
    /** What the grid and the water network look like right now, for the dashboard and the journal. */
    fun fixtureState(): List<FixtureState> = topology.fixtures.map { fixture ->
        FixtureState(
            id = fixture.id,
            kind = fixture.kind.name.lowercase(),
            at = fixture.at,
            health = healthOf(fixture.id),
            // A source makes power, a pipe carries water and a fence just stands: none of them draws from the grid.
            powered = when (fixture.kind) {
                FixtureKind.REACTOR, FixtureKind.SOLAR, FixtureKind.UPS, FixtureKind.PIPE, FixtureKind.FENCE,
                FixtureKind.DEPOSITORY, FixtureKind.MEDICAL_CENTER -> healthOf(fixture.id) > 0
                else -> isPowered(fixture.id)
            },
            // What hangs off this fixture: the poles and consumers it feeds, or the house a pipe serves.
            feeds = fixtureFeeds[fixture.id].orEmpty(),
            from = fixture.from, to = fixture.to,
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
            val pt = Point(instance.x, instance.y)
            position[instance.id] = pt
            spatialIndex.update(instance.id, pt)
        }
        for (fixture in topology.fixtures) {
            health[fixture.id] = fullHealth(fixture.id)
            position[fixture.id] = fixture.at
            spatialIndex.update(fixture.id, fixture.at)
            if (fixture.kind == FixtureKind.AIR_DEFENSE) {
                val isRoof = fixture.id.contains("roof")
                airDefenseUnits[fixture.id] = AirDefenseUnit(fixture.id, fixture.at, isRoofMounted = isRoof)
            }
        }

        for (house in people.houses) {
            insideTemperature[house.id] = config.house.initialTemperature
            frostExposure[topology.waterPipe.getValue(house.id)] = 0.0
        }
        for (appliance in people.appliances) if (appliance.kind == "Kettle") waterTemperature[appliance.id] = 15.0
        if (people.houses.isNotEmpty()) {
            val minX = people.houses.minOf { it.x }
            val maxX = people.houses.maxOf { it.x }
            val minY = people.houses.minOf { it.y }
            val maxY = people.houses.maxOf { it.y }
            seaController.configureSettlementBounds(minX, maxX, minY, maxY)
        }
        for ((crewId, pt) in crewDepots) {
            position[crewId] = pt
            spatialIndex.update(crewId, pt)
        }
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
    fun vehicleOf(id: String): String? = passengerVehicle[id]
    /** The same site the residents observe as their workplace; exposed to the observer, not re-inferred by the UI. */
    val minePosition: Point get() = topology.mine
    private fun fullHealth(id: String): Double = when (topology.byId[id]?.kind) {
        FixtureKind.POLE -> config.power.poleHealth
        FixtureKind.FENCE -> config.fence.health
        FixtureKind.AIR_DEFENSE -> 100.0
        FixtureKind.DEPOSITORY, FixtureKind.MEDICAL_CENTER -> 200.0
        else -> 100.0
    }

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
                "devices" -> devicesOf(id)
                "reachable_breakables" -> targets(id, reachableAppliances(instance))
                "in_fog" -> JsonPrimitive(seaController.isInFog(positionOf(id)))
                "respirator_equipped" -> JsonPrimitive(respiratorEquipped[id] == true)
                "depository" -> pointJson(topology.depository)
                "medical_center" -> pointJson(topology.medicalCenter)
                "creatine_stock" -> JsonPrimitive(creatineManager.stock)
                "sea_damage" -> JsonPrimitive(seaController.totalErosionDamage)
                // The substation is fenced off; what stands in the open is the distribution poles, and the fence
                // segment the hunter has just run into.
                "visible_infrastructure" -> targets(id, topology.poles.filter { it.id != BUS && !isBroken(it.id) }.map { it.id } +
                    listOfNotNull(blockedBy[id]?.takeUnless(::isBroken)), config.sight.xenomorphRadius)
                // People indoors or inside a rover are out of reach; marines are not prey.
                "visible_humans" -> targets(id, people.residents.filter { resident ->
                    !isBroken(resident.id) && resident.id !in passengerVehicle &&
                        positionOf(resident.id).distanceTo(positionOf(resident.parent ?: resident.id)) > HOUSE_ZONE
                }.map { it.id }, config.sight.xenomorphRadius)
                "patrol_waypoint" -> pointJson(patrolPoint(id))
                "routed" -> JsonPrimitive(isRouted(id))
                "home" -> homeViews[instance.parent ?: id] ?: pointJson(positionOf(instance.parent ?: id))
                "workplace" -> workplaceViews[workplaceOf(id)] ?: pointJson(workplaceOf(id))
                "meeting_point" -> meetingView
                "routine_slot" -> JsonPrimitive(routineSlots[id] ?: 0)
                "day_minute" -> JsonPrimitive(residentDayMinute(id))
                "depot" -> vehicleRouteTarget[id]?.let(::pointJson) ?: depotViews[id] ?: pointJson(depotOf(instance))
                "active_jobs" -> jobList(id)
                "materials_remaining" -> JsonPrimitive(config.repair.materials)
                "speed_eff" -> JsonPrimitive(config.repair.roverSpeed)
                "work_radius" -> JsonPrimitive(config.repair.workRadius)
                "boarding_radius" -> JsonPrimitive(config.transport.boardRadius)
                "passenger_count" -> JsonPrimitive(vehiclePassengers[id]?.size ?: 0)
                "passenger_capacity" -> JsonPrimitive(config.transport.passengerCapacity)
                "transport_ready" -> JsonPrimitive(transportReady(id))
                "transport_target" -> pointJson(transportDestination(id) ?: depotOf(instance))
                "boarding_pending" -> JsonPrimitive(seatedWalkers(id).size)
                "available_vehicles" -> targets(id, availableTransportVehicles(id), if (squadOf(id) == null) config.transport.walkRadius else config.sight.sightRadius)
                "in_vehicle" -> JsonPrimitive(id in passengerVehicle)
                "dispatch_ready" -> JsonPrimitive(elapsedSeconds >= config.marine.responseDelaySeconds)
                "visible_xenomorphs" -> targets(id, listOfNotNull(squadTarget(id)), radius = null)
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

    private fun squadOf(id: String): String? = people.byId[id]?.takeIf { it.kind == "Marine" }
        ?.params?.get("squad")?.jsonPrimitive?.contentOrNull

    private fun availableTransportVehicles(passengerId: String): List<String> {
        val squad = squadOf(passengerId) ?: return vehiclesForResident(passengerId)
        val members = marineSquad(passengerId).filter { !isBroken(it.id) }
        if (squad != null && (members.size !in config.marine.minSquadSize..config.marine.maxSquadSize ||
                    members.size > config.transport.passengerCapacity)) return emptyList()
        // A squad keeps the first vehicle it boards, even when its target has moved since boarding.
        val reserved = members.firstNotNullOfOrNull { passengerVehicle[it.id] }
        val hasTransportFleet = people.rovers.any { it.id.startsWith("transport-") }
        return people.rovers.filter { rover ->
            if (hasTransportFleet && !rover.id.startsWith("transport-")) return@filter false
            val riders = vehiclePassengers[rover.id].orEmpty()
            !isBroken(rover.id) && riders.size < config.transport.passengerCapacity &&
                (reserved == null || reserved == rover.id) &&
                if (riders.isEmpty()) atDepot(rover)
                else riders.all { squadOf(it) == squad } && !transportReady(rover.id)
        }.map { it.id }
    }

    private fun atDepot(rover: Instance) = positionOf(rover.id).distanceTo(depotOf(rover)) <= config.transport.boardRadius

    /**
     * A resident is offered a rover waiting at its depot that still has a seat for them and, if others are
     * already aboard, goes to the same workplace. Seats go to the residents already walking to it, nearest first,
     * so a crowd that sees one rover settles in a single step instead of racing for it.
     */
    private fun vehiclesForResident(residentId: String): List<String> {
        val workplace = workplaceOf(residentId)
        val hasTransportFleet = people.rovers.any { it.id.startsWith("transport-") }
        return people.rovers.filter { rover ->
            if (hasTransportFleet && !rover.id.startsWith("transport-")) return@filter false
            val riders = vehiclePassengers[rover.id].orEmpty()
            !isBroken(rover.id) && atDepot(rover) && riders.none { squadOf(it) != null } &&
                (riders.isEmpty() || vehicleRouteTarget[rover.id]?.distanceTo(workplace)?.let { it <= 1e-6 } == true && !transportReady(rover.id)) &&
                hasSeatFor(rover.id, residentId)
        }.map { it.id }
    }

    private fun hasSeatFor(roverId: String, residentId: String): Boolean {
        val seated = seatedWalkers(roverId)
        return residentId in seated || seated.size < freeSeats(roverId)
    }

    private fun freeSeats(roverId: String) = config.transport.passengerCapacity - (vehiclePassengers[roverId]?.size ?: 0)

    /** Residents walking to this rover in the latest step who hold one of its free seats, nearest first. */
    private fun seatedWalkers(roverId: String): List<String> {
        val here = positionOf(roverId)
        return walkersOf[roverId].orEmpty().filter { it !in passengerVehicle && !isBroken(it) }
            .sortedWith(compareBy({ positionOf(it).distanceTo(here) }, { it }))
            .take(freeSeats(roverId).coerceAtLeast(0))
    }

    private fun marineSquad(marineId: String): List<Instance> {
        val marine = people.byId[marineId] ?: return emptyList()
        if (marine.kind != "Marine") return emptyList()
        val squad = marine.params["squad"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return people.marines.filter { it.params["squad"]?.jsonPrimitive?.contentOrNull == squad }
    }

    private fun isMarineLeader(marineId: String): Boolean =
        people.byId[marineId]?.params?.get("leader")?.jsonPrimitive?.booleanOrNull ?: false

    private fun squadReady(marineId: String): Boolean {
        val target = squadTarget(marineId) ?: return false
        val squad = marineSquad(marineId).filter { !isBroken(it.id) }
        if (squad.size !in config.marine.minSquadSize..config.marine.maxSquadSize) return false
        return squad.all { it.id !in passengerVehicle && positionOf(it.id).distanceTo(positionOf(target)) <= config.marine.assaultRadius }
    }

    private fun squadTarget(marineId: String): String? {
        val squad = squadOf(marineId) ?: return null
        // A pinned target that has died or been driven off no longer holds the squad: it takes the nearest one.
        squadTargets[squad]?.takeUnless { isBroken(it) || isRouted(it) }?.let { return it }
        val leader = marineSquad(marineId).filter { !isBroken(it.id) }
            .sortedWith(compareBy({ !isMarineLeader(it.id) }, { it.id })).firstOrNull() ?: return null
        return nearestVisibleXenomorph(leader.id)
    }

    private fun nearestVisibleXenomorph(marineId: String): String? {
        val here = positionOf(marineId)
        return people.xenomorphs.asSequence()
            // Behind a fence the squads defend the colony: they go after intruders, not what roams outside.
            .filter { it.kind == "Xenomorph" && !isBroken(it.id) && !isRouted(it.id) && topology.fenceBox?.contains(positionOf(it.id)) != false }
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
        if (marineGroups.isNotEmpty() || passengers.size >= config.transport.passengerCapacity) return true
        // Residents leave together: the rover waits for those still walking to it, but not for ever.
        val waited = elapsedSeconds - (boardingSince[vehicleId] ?: elapsedSeconds)
        return seatedWalkers(vehicleId).isEmpty() || waited >= config.transport.boardingWaitSeconds
    }

    private fun transportDestination(vehicleId: String): Point? {
        val marine = vehiclePassengers[vehicleId].orEmpty().firstOrNull { squadOf(it) != null }
        return marine?.let(::squadTarget)?.let(::positionOf) ?: vehicleRouteTarget[vehicleId]
    }

    private fun arrivedWithPassengers(vehicleId: String): Boolean {
        val destination = transportDestination(vehicleId) ?: return false
        val marine = vehiclePassengers[vehicleId].orEmpty().any { squadOf(it) != null }
        val radius = if (marine) config.marine.assaultRadius else 1e-6
        return transportReady(vehicleId) && positionOf(vehicleId).distanceTo(destination) <= radius
    }

    private fun unboard(vehicleId: String, events: MutableList<KernelEvent>, reason: String? = null, ref: String? = null) {
        val passengers = vehiclePassengers.remove(vehicleId).orEmpty().toList()
        vehicleRouteTarget.remove(vehicleId)
        boardingSince.remove(vehicleId)
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
            val squad = squadOf(request.passengerId)
            val riders = vehiclePassengers[request.vehicleId].orEmpty()
            val sameSquad = squad != null && riders.isNotEmpty() && riders.all { squadOf(it) == squad }
            val hasTransportFleet = people.rovers.any { it.id.startsWith("transport-") }
            val reason = when {
                !request.destination.x.isFinite() || !request.destination.y.isFinite() -> "invalid_destination"
                passenger == null -> "unknown_passenger"
                passenger.kind != "Human" && passenger.kind != "Marine" -> "passenger_not_transportable"
                request.passengerId !in accepted || isBroken(request.passengerId) -> "passenger_unable"
                vehicle == null -> "unknown_vehicle"
                vehicle.kind != "Rover" || (hasTransportFleet && !vehicle.id.startsWith("transport-")) -> "target_not_rover"
                isBroken(request.vehicleId) -> "vehicle_broken"
                passengerVehicle.containsKey(request.passengerId) -> "already_in_vehicle"
                positionOf(request.passengerId).distanceTo(positionOf(request.vehicleId)) > config.transport.boardRadius -> "out_of_boarding_range"
                (vehiclePassengers[request.vehicleId]?.size ?: 0) >= config.transport.passengerCapacity -> "vehicle_full"
                squad != null && request.vehicleId !in availableTransportVehicles(request.passengerId) -> "squad_vehicle_unavailable"
                riders.any { squadOf(it) != squad } -> "vehicle_reserved"
                !sameSquad && vehicleRouteTarget[request.vehicleId]?.distanceTo(request.destination)?.let { it > 1e-6 } == true -> "vehicle_already_committed"
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
            boardingSince.putIfAbsent(request.vehicleId, elapsedSeconds)
            vehiclePassengers.getOrPut(request.vehicleId) { LinkedHashSet() }.add(request.passengerId)
            passengerVehicle[request.passengerId] = request.vehicleId
            position[request.passengerId] = positionOf(request.vehicleId)
            vehicleRouteTarget.putIfAbsent(request.vehicleId, request.destination)
            if (squad != null) squadTarget(request.passengerId)?.let { squadTargets.putIfAbsent(squad, it) }
            events += KernelEvent(
                "ActionSucceeded", request.passengerId, request.vehicleId,
                buildJsonObject { put("action", "transport.board"); put("vehicle", request.vehicleId) },
                listOf(request.passengerId, request.vehicleId), request.ref,
            )
        }
    }

    private fun workplaceOf(id: String): Point {
        if (config.creatine.enabled && healthOf(id) < config.creatine.lowHealthThreshold) {
            return topology.medicalCenter
        }
        return if ((routineSlots[id] ?: 0) % 4 == 2) topology.services else topology.mine
    }

    private fun residentDayMinute(id: String): Long {
        if (people.houses.size < 1000) {
            return (config.human.startMinute + (elapsedSeconds / 60).toLong()) % 1440
        }
        val slot = routineSlots[id] ?: 0
        val cohort = slot % 4
        val offset = (slot / 4) % 15
        val stagger = (slot * 37) % 1200
        val cycleTime = (elapsedSeconds + stagger) % 1200.0

        return if (cohort == 0 || cohort == 1 || cohort == 3) {
            val shiftStart = if (cohort == 3) 840 + offset else 480 + offset
            val shiftEnd = if (cohort == 3) 1200 + offset else 840 + offset
            if (cycleTime < 750.0) {
                val progress = cycleTime / 750.0
                (shiftStart + 15 + (progress * 300.0).toLong()) % 1440
            } else {
                (shiftEnd + 30).toLong() % 1440
            }
        } else {
            val shiftStart = 540 + offset
            val shiftEnd = 1020 + offset
            if (cycleTime < 750.0) {
                val progress = cycleTime / 750.0
                (shiftStart + 15 + (progress * 400.0).toLong()) % 1440
            } else {
                (shiftEnd + 30).toLong() % 1440
            }
        }
    }

    private fun reachableAppliances(instance: Instance): List<String> {
        val here = positionOf(instance.id)
        val (x, y) = applianceCell(here)
        return buildList {
            for (dx in -1L..1L) for (dy in -1L..1L) {
                for (appliance in applianceCells[x + dx to y + dy].orEmpty()) {
                    if (!isBroken(appliance.id) && positionOf(appliance.id).distanceTo(here) <= config.human.vandalRadius) add(appliance.id)
                }
            }
        }
    }

    /**
     * Observed objects within [radius] (no limit when null), ordered by distance and then by id, as the contract
     * requires. A fence segment is seen at its point nearest to the observer: that is where one reaches it.
     */
    private fun targets(observerId: String, candidates: List<String>, radius: Double? = config.sight.sightRadius): JsonArray {
        val here = positionOf(observerId)
        val filteredCandidates = if (radius != null && radius > 0.0 && candidates.size > 32) {
            val nearby = spatialIndex.queryRadius(here, radius + 40.0).toSet()
            candidates.filter { it in nearby }
        } else {
            candidates
        }
        return JsonArray(filteredCandidates.asSequence()
            .map { id -> id to (topology.byId[id]?.takeIf { it.kind == FixtureKind.FENCE }?.nearestPointTo(here) ?: positionOf(id)) }
            .map { (id, at) -> Triple(id, at, at.distanceTo(here)) }
            .filter { radius == null || it.third <= radius }
            .sortedWith(compareBy({ it.third }, { it.first }))
            .take(config.sight.listLimit)
            .map { (id, at, distance) ->
                buildJsonObject {
                    put("id", id); put("kind", kindOf(id)); put("position", pointJson(at))
                    put("distance", distance); put("health", healthOf(id))
                }
            }.toList())
    }

    private fun kindOf(id: String): String =
        people.byId[id]?.kind ?: topology.byId[id]?.kind?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Unknown"

    /**
     * Where each hunter roams. Outside the fence (or around the settlement when it has none) each leg takes it
     * a random stretch along the perimeter, either way round and stopping at a corner, so the hunters wander
     * apart instead of marching in file, and a leg never cuts across the fenced area. Inside the fence it prowls between random points. The choice
     * depends only on the seed, the hunter and the leg, never on the order in which observations are computed.
     */
    private val roamBox: Box = topology.fenceBox ?: run {
        val baseFixtures = topology.fixtures.filter {
            it.kind != FixtureKind.AIR_DEFENSE && it.kind != FixtureKind.DEPOSITORY && it.kind != FixtureKind.MEDICAL_CENTER
        }
        Box(
            baseFixtures.minOf { it.at.x }, baseFixtures.minOf { it.at.y },
            baseFixtures.maxOf { it.at.x }, baseFixtures.maxOf { it.at.y }
        )
    }
    private val roamDistance = if (topology.fenceBox != null) config.fence.roamingDistance else PATROL_MARGIN
    private val patrolLeg = HashMap<String, Int>()
    private val patrolAlong = HashMap<String, Double>()

    private fun roll(id: String, leg: Int, salt: Int): Double =
        java.util.SplittableRandom(manifest.seed * 1_000_003L + id.hashCode() * 7_919L + leg * 131L + salt).nextDouble()

    /** Inside the fence, or inside the settlement itself when it has none. */
    private val settlementArea: Box get() = topology.fenceBox ?: roamBox

    /** A routed xenomorph runs until it is out of the settlement and its time to keep away has passed. */
    private fun isRouted(id: String): Boolean {
        val until = routedUntil[id] ?: return false
        return !isBroken(id) && (settlementArea.contains(positionOf(id)) || elapsedSeconds < until)
    }

    /**
     * Where a routed xenomorph runs from inside: through the nearest breach, first to its inner side and then
     * straight out, or else to the nearest stretch of the fence, which it breaks through.
     */
    private fun exitPoint(id: String): Point {
        val here = positionOf(id)
        val gap = topology.fence.filter { isBroken(it.id) }.minWithOrNull(compareBy({ it.nearestPointTo(here).distanceTo(here) }, { it.id }))
        if (gap != null) {
            val out = outwardOf(gap.at)
            val inner = Point(gap.at.x - out.x * EXIT_STEP, gap.at.y - out.y * EXIT_STEP)
            if (here.distanceTo(inner) > 1.0) return inner
            return Point(gap.at.x + out.x * EXIT_RUN, gap.at.y + out.y * EXIT_RUN)
        }
        val box = settlementArea
        val out = outwardOf(here)
        val border = when (out) {
            Point(-1.0, 0.0) -> Point(box.minX, here.y)
            Point(1.0, 0.0) -> Point(box.maxX, here.y)
            Point(0.0, -1.0) -> Point(here.x, box.minY)
            else -> Point(here.x, box.maxY)
        }
        return Point(border.x + out.x * EXIT_RUN, border.y + out.y * EXIT_RUN)
    }

    /** The outward direction of the side of the settlement nearest to [p]. */
    private fun outwardOf(p: Point): Point {
        val box = settlementArea
        return listOf(
            Math.abs(p.x - box.minX) to Point(-1.0, 0.0), Math.abs(box.maxX - p.x) to Point(1.0, 0.0),
            Math.abs(p.y - box.minY) to Point(0.0, -1.0), Math.abs(box.maxY - p.y) to Point(0.0, 1.0),
        ).minBy { it.first }.second
    }

    /** How far along the roaming perimeter, clockwise from its north-west corner, the point nearest to [p] lies. */
    private fun perimeterAlong(p: Point): Double {
        val b = roamBox
        val w = b.maxX - b.minX
        val h = b.maxY - b.minY
        val x = p.x.coerceIn(b.minX, b.maxX)
        val y = p.y.coerceIn(b.minY, b.maxY)
        val side = listOf(Math.abs(y - b.minY) to 0, Math.abs(b.maxX - x) to 1, Math.abs(b.maxY - y) to 2, Math.abs(x - b.minX) to 3)
            .minBy { it.first }.second
        return when (side) {
            0 -> x - b.minX
            1 -> w + (y - b.minY)
            2 -> w + h + (b.maxX - x)
            else -> 2 * w + h + (b.maxY - y)
        }
    }

    private fun patrolPoint(id: String): Point {
        if (isRouted(id) && settlementArea.contains(positionOf(id))) return exitPoint(id)
        val leg = patrolLeg.getOrDefault(id, 0)
        val fence = topology.fenceBox
        if (fence != null && fence.contains(positionOf(id))) {
            val inset = minOf(10.0, (fence.maxX - fence.minX) / 4, (fence.maxY - fence.minY) / 4)
            return Point(fence.minX + inset + (fence.maxX - fence.minX - 2 * inset) * roll(id, leg, 1),
                fence.minY + inset + (fence.maxY - fence.minY - 2 * inset) * roll(id, leg, 2))
        }
        return roamingPoint(id)
    }

    private fun roamingPoint(id: String): Point {
        val leg = patrolLeg.getOrDefault(id, 0)
        val along = patrolAlong.getOrPut(id) { roll(id, 0, 0) * perimeter() }
        return perimeterPoint(along, roamDistance * (0.4 + 0.6 * roll(id, leg, 3)))
    }

    private fun advancePatrol(id: String) {
        val leg = patrolLeg.getOrDefault(id, 0) + 1
        patrolLeg[id] = leg
        val box = topology.fenceBox
        if (box != null && box.contains(positionOf(id))) return
        val along = patrolAlong.getOrPut(id) { roll(id, 0, 0) * perimeter() }
        val step = (0.03 + 0.12 * roll(id, leg, 4)) * perimeter()
        val stops = listOf(0.0) + corners() + perimeter()
        patrolAlong[id] = if (roll(id, leg, 5) < 0.5) {
            minOf(along + step, stops.first { it > along + 1e-6 }) % perimeter()
        } else {
            val from = if (along <= 1e-6) perimeter() else along
            maxOf(from - step, stops.last { it < from - 1e-6 })
        }
    }

    private fun perimeter() = 2 * ((roamBox.maxX - roamBox.minX) + (roamBox.maxY - roamBox.minY))
    private fun corners(): List<Double> {
        val w = roamBox.maxX - roamBox.minX
        val h = roamBox.maxY - roamBox.minY
        return listOf(w, w + h, 2 * w + h)
    }

    /** A point [offset] metres outside the box, [along] metres clockwise from its north-west corner. */
    private fun perimeterPoint(along: Double, offset: Double): Point {
        val b = roamBox
        val w = b.maxX - b.minX
        val h = b.maxY - b.minY
        val t = ((along % perimeter()) + perimeter()) % perimeter()
        return when {
            t == 0.0 -> Point(b.minX - offset, b.minY - offset)
            t < w -> Point(b.minX + t, b.minY - offset)
            t == w -> Point(b.maxX + offset, b.minY - offset)
            t < w + h -> Point(b.maxX + offset, b.minY + (t - w))
            t == w + h -> Point(b.maxX + offset, b.maxY + offset)
            t < 2 * w + h -> Point(b.maxX - (t - w - h), b.maxY + offset)
            t == 2 * w + h -> Point(b.minX - offset, b.maxY + offset)
            else -> Point(b.minX - offset, b.maxY - (t - 2 * w - h))
        }
    }

    init {
        // Xenomorphs come from the wilds: with a fence they appear outside it, each at its own place.
        if (topology.fenceBox != null) {
            for (xenomorph in people.byId.values.filter { it.kind == "Xenomorph" }) {
                position[xenomorph.id] = roamingPoint(xenomorph.id)
                advancePatrol(xenomorph.id)
            }
        }
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

        // A sortie ends when its target is dead or the whole squad is back at its base; the next one picks anew.
        // A squad that has gathered at its target drives it off, whatever the leader's shot does.
        for (squad in people.marines.mapNotNull { squadOf(it.id) }.distinct()) {
            val member = people.marines.firstOrNull { squadOf(it.id) == squad && !isBroken(it.id) } ?: continue
            if (squadReady(member.id)) squadTarget(member.id)?.let { routedUntil[it] = elapsedSeconds + config.marine.routSeconds }
        }
        routedUntil.keys.removeIf { !isRouted(it) }
        squadTargets.entries.removeIf { (squad, target) -> isBroken(target) || isRouted(target) || squadAtBase(squad) }
        walkersOf = walkersFrom(intents, accepted)
        // Freeze each route to the same world state the VM observed before this step's movement.
        for (vehicleId in vehicleRouteTarget.keys.toList()) {
            transportDestination(vehicleId)?.let { vehicleRouteTarget[vehicleId] = it }
        }

        applyDamage(tick, intents, refs, accepted, events)
        releaseInvalidPassengers(events)
        applyTransportRequests(transportRequests, accepted, events)
        applyRepairs(tick, intents, accepted, events)
        stepSea(tick, events)
        stepThreatsAndDefense(tick, events)
        stepCreatineEconomy(tick, events)
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

    private fun stepSea(tick: Long, events: MutableList<KernelEvent>) {
        if (!config.sea.enabled) return
        val seaResult = seaController.step(elapsedSeconds, dt)
        if (seaResult.fogStarted) {
            events += KernelEvent("FogStarted", "sea", null, buildJsonObject { put("depth", seaResult.currentFogDepth) })
        }
        if (seaResult.fogCleared) {
            events += KernelEvent("FogCleared", "sea", null, buildJsonObject { put("depth", 0.0) })
        }

        // Coastal erosion damage to houses along the shore
        for (house in people.houses) {
            val pos = positionOf(house.id)
            if (seaController.isInCoastalZone(pos)) {
                val dmg = seaResult.erosionDamage
                if (dmg > 0.0) {
                    val before = healthOf(house.id)
                    val after = (before - dmg).coerceAtLeast(0.0)
                    health[house.id] = after
                    seaController.recordErosionDamage(dmg)
                    if (after < 85.0 && house.id !in jobs) {
                        openJob(house.id, tick, "device", fastDispatch = true)
                    }
                    if (before > 0.0 && after <= 0.0) {
                        events += breakOf(tick, house.id, "sea_erosion", "sea")
                    }
                }
            }
        }

        // Fog effects: break down Air Defense & respiration check for humans
        if (seaResult.isFogActive && seaResult.currentFogDepth > 0.0) {
            for (ad in topology.airDefenses) {
                if (seaController.isInFog(ad.at)) {
                    val unit = airDefenseUnits[ad.id]
                    if (unit != null && unit.isOperational) {
                        unit.breakDown("fog_corrosion")
                        health[ad.id] = 0.0
                        openJob(ad.id, tick, "device", fastDispatch = true)
                        events += breakOf(tick, ad.id, "fog_corrosion", "sea_fog")
                    }
                }
            }

            for (resident in people.residents) {
                if (isBroken(resident.id) || resident.id in passengerVehicle) continue
                val pos = positionOf(resident.id)
                if (seaController.isInFog(pos)) {
                    val hasRespirator = respiratorEquipped[resident.id] == true
                    if (!hasRespirator) {
                        // Worker equips respirator upon encountering fog
                        respiratorEquipped[resident.id] = true
                    }
                } else {
                    respiratorEquipped[resident.id] = false
                }
            }
        }
    }

    private fun stepThreatsAndDefense(tick: Long, events: MutableList<KernelEvent>) {
        if (!config.sea.enabled && people.xenomorphs.isNotEmpty()) return
        // Spawn flying crocodiles from the forest borders with doubled frequency (17.5s)
        val spawnInterval = 17.5
        if (elapsedSeconds > 0 && (elapsedSeconds % spawnInterval) < dt) {
            val croc = crocodilePool.obtain()
            if (croc != null) {
                val minX = topology.fixtures.minOf { it.at.x }
                val maxX = topology.fixtures.maxOf { it.at.x }
                val minY = topology.fixtures.minOf { it.at.y }
                val maxY = topology.fixtures.maxOf { it.at.y }

                val rnd = java.util.Random(manifest.seed + tick * 37L + totalCrocodilesSpawned * 1013L)
                val spawnSide = rnd.nextInt(3) // 0: West, 1: North, 2: South
                val startPt = when (spawnSide) {
                    0 -> Point(minX - 160.0 - rnd.nextDouble() * 40.0, minY + rnd.nextDouble() * (maxY - minY))
                    1 -> Point(minX - 80.0 + rnd.nextDouble() * (maxX - minX) * 0.6, minY - 160.0 - rnd.nextDouble() * 40.0)
                    else -> Point(minX - 80.0 + rnd.nextDouble() * (maxX - minX) * 0.6, maxY + 160.0 + rnd.nextDouble() * 40.0)
                }

                val seaTurnX = maxX + 140.0 + rnd.nextDouble() * 160.0
                val seaTurnY = minY + rnd.nextDouble() * (maxY - minY)
                val turnPt = Point(seaTurnX, seaTurnY)

                val exitSide = rnd.nextInt(3) // 0: West, 1: North, 2: South
                val exitPt = when (exitSide) {
                    0 -> Point(minX - 160.0 - rnd.nextDouble() * 40.0, minY + rnd.nextDouble() * (maxY - minY))
                    1 -> Point(minX - 80.0 + rnd.nextDouble() * (maxX - minX) * 0.6, minY - 160.0 - rnd.nextDouble() * 40.0)
                    else -> Point(minX - 80.0 + rnd.nextDouble() * (maxX - minX) * 0.6, maxY + 160.0 + rnd.nextDouble() * 40.0)
                }

                val seed = manifest.seed + tick * 47L + totalCrocodilesSpawned * 997L
                croc.spawn(startPt, turnPt, exitPt, speed = 22.0, health = 120.0, seed = seed)
                totalCrocodilesSpawned++
                events += KernelEvent("CrocodileSpawned", croc.id, null, buildJsonObject {
                    put("start", pointJson(startPt)); put("turn", pointJson(turnPt)); put("exit", pointJson(exitPt))
                })
            }
        }

        // Update Air Defense cooldowns
        for (unit in airDefenseUnits.values) {
            unit.updateCooldown(dt)
        }

        // Update crocodiles, engage with air defense, bombard buildings
        for (croc in crocodilePool.activeCrocodiles()) {
            croc.update(dt)
            if (!croc.active) {
                spatialIndex.remove(croc.id)
                continue
            }
            spatialIndex.update(croc.id, croc.position)

            // Air Defense engagement
            val nearbyDefense = spatialIndex.queryRadius(croc.position, 110.0)
            for (unitId in nearbyDefense) {
                val unit = airDefenseUnits[unitId] ?: continue
                if (unit.tryEngage(croc)) {
                    totalCrocodilesDeflected++
                    events += KernelEvent("AirDefenseFired", unit.id, croc.id, buildJsonObject {
                        put("crocodile", croc.id); put("health", croc.health); put("deflected", croc.isDeflected)
                    })
                    if (!croc.active) {
                        totalCrocodilesDowned++
                        events += KernelEvent("CrocodileDowned", croc.id, unit.id, buildJsonObject {
                            put("by", unit.id)
                        })
                        break
                    }
                }
            }

            // Bombardment of structures beneath the crocodile
            if (croc.active && croc.attackCooldown <= 0.0) {
                val buildingsBelow = spatialIndex.queryRadius(croc.position, 35.0)
                    .filter { it in people.byId && people.byId[it]?.kind == "House" }
                for (houseId in buildingsBelow) {
                    val dmg = 20.0
                    val beforeH = healthOf(houseId)
                    if (beforeH > 0.0) {
                        val afterH = (beforeH - dmg).coerceAtLeast(0.0)
                        health[houseId] = afterH
                        openJob(houseId, tick, "device", fastDispatch = true)
                        events += KernelEvent("DamageApplied", houseId, croc.id, buildJsonObject {
                            put("target", houseId); put("amount", dmg); put("reason", "crocodile_strike")
                        })
                        if (afterH <= 0.0) {
                            events += breakOf(tick, houseId, "crocodile_strike", croc.id)
                        }
                        croc.attackCooldown = 3.0
                        break
                    }
                }
            }
        }
    }

    private fun stepCreatineEconomy(tick: Long, events: MutableList<KernelEvent>) {
        if (!config.creatine.enabled) return
        // 1. Extraction: Miners working at the Mine accumulate raw creatine in mineStock
        var activeMiners = 0
        val minersAtMine = mutableListOf<String>()
        for (resident in people.residents) {
            if (!isBroken(resident.id) && resident.id !in passengerVehicle) {
                val pos = positionOf(resident.id)
                if (pos.distanceTo(topology.mine) <= 35.0) {
                    activeMiners++
                    minersAtMine.add(resident.id)
                }
            }
        }
        creatineManager.lastActiveMinersCount = activeMiners
        val synergy = 1.0 + 9.0 * (activeMiners.toDouble() / 5000.0).coerceIn(0.0, 1.0)
        if (activeMiners > 0) {
            val baseRate = config.creatine.yieldPerWorkerHour * dt / 3600.0
            val minedAmount = activeMiners * baseRate * synergy
            creatineManager.produceAtMine(minedAmount)
            if (tick % 15 == 0L) {
                events += KernelEvent("CreatineMined", "site/mine", "mine", buildJsonObject {
                    put("miners", activeMiners)
                    put("synergy_multiplier", synergy)
                    put("amount", minedAmount)
                    put("mine_stock", creatineManager.mineStock)
                })
            }
        }

        // Workers carrying creatine on foot
        for (resident in people.residents) {
            if (isBroken(resident.id) || resident.id in passengerVehicle) continue
            val pos = positionOf(resident.id)
            if (pos.distanceTo(topology.mine) <= 35.0 && resident.id !in workerCreatineCargo && creatineManager.mineStock >= 2.0) {
                creatineManager.mineStock -= 2.0
                workerCreatineCargo[resident.id] = 2.0
            } else if (pos.distanceTo(topology.depository) <= 30.0 && resident.id in workerCreatineCargo) {
                val cargo = workerCreatineCargo.remove(resident.id) ?: 0.0
                if (cargo > 0.0) {
                    val result = creatineManager.transferToDepository(cargo)
                    events += KernelEvent("CreatineDeliveredToDepository", resident.id, "storage/creatine", buildJsonObject {
                        put("delivered", cargo)
                        put("by_foot", true)
                        put("stored", result.unitsForStock)
                    })
                    val rev = creatineManager.flushRevenue()
                    if (rev > 0) {
                        post(tick, "settlement", "creatine_sale", rev, "Export of refined creatine from Depository (foot delivery)")
                    }
                }
            }
        }

        // 2. Dedicated Cargo Rovers: Haul creatine Mine -> Depository -> MedicalCenter
        val cargoRovers = people.rovers.filter { it.id.startsWith("cargo-") && !isBroken(it.id) }.sortedBy { it.id }
        if (cargoRovers.isEmpty()) {
            if (activeMiners > 0) {
                val baseRate = config.creatine.yieldPerWorkerHour * dt / 3600.0
                val minedAmount = activeMiners * baseRate * synergy
                creatineManager.deposit(minedAmount)
                val rev = creatineManager.flushRevenue()
                if (rev > 0) {
                    post(tick, "settlement", "creatine_sale", rev, "Commercial export of refined creatine ($activeMiners miners)")
                }
            }
        } else {
            val half = cargoRovers.size / 2
            val mineTeam = cargoRovers.take(half) // cargo-1 .. cargo-25: Mine <-> Depository
            val medTeam = cargoRovers.drop(half)  // cargo-26 .. cargo-50: Depository <-> MedicalCenter

            // Team 1: Mine <-> Depository (raw haul)
            for (rover in mineTeam) {
                val currentPos = positionOf(rover.id)
                val stage = roverLogisticsTarget.getOrPut(rover.id) { "mine" }
                when (stage) {
                    "mine" -> {
                        val dist = currentPos.distanceTo(topology.mine)
                        if (dist <= 25.0) {
                            val toLoad = minOf(20.0, creatineManager.mineStock.coerceAtLeast(0.0))
                            if (toLoad > 0.0) {
                                creatineManager.mineStock -= toLoad
                                roverCreatineCargo[rover.id] = (roverCreatineCargo[rover.id] ?: 0.0) + toLoad
                                events += KernelEvent("CreatineLoadedAtMine", rover.id, "mine", buildJsonObject {
                                    put("loaded", toLoad)
                                    put("cargo", roverCreatineCargo[rover.id] ?: 0.0)
                                })
                            }
                            roverLogisticsTarget[rover.id] = "depository"
                            vehicleRouteTarget[rover.id] = topology.depository
                        } else {
                            vehicleRouteTarget[rover.id] = topology.mine
                        }
                    }
                    "depository" -> {
                        val dist = currentPos.distanceTo(topology.depository)
                        if (dist <= 25.0) {
                            val cargo = roverCreatineCargo.remove(rover.id) ?: 0.0
                            if (cargo > 0.0) {
                                val result = creatineManager.transferToDepository(cargo)
                                events += KernelEvent("CreatineDeliveredToDepository", rover.id, "storage/creatine", buildJsonObject {
                                    put("delivered", cargo)
                                    put("for_sale", result.unitsForSale)
                                    put("stored", result.unitsForStock)
                                    put("total_depository_stock", creatineManager.storedStock)
                                })
                                val rev = creatineManager.flushRevenue()
                                if (rev > 0) {
                                    post(tick, "settlement", "creatine_sale", rev, "Export of refined creatine from Depository")
                                }
                            }
                            roverLogisticsTarget[rover.id] = "mine"
                            vehicleRouteTarget[rover.id] = topology.mine
                        } else {
                            vehicleRouteTarget[rover.id] = topology.depository
                        }
                    }
                    else -> {
                        roverLogisticsTarget[rover.id] = "mine"
                        vehicleRouteTarget[rover.id] = topology.mine
                    }
                }
            }

            // Team 2: Depository <-> Medical Center (medical creatine haul)
            for (rover in medTeam) {
                val currentPos = positionOf(rover.id)
                val stage = roverLogisticsTarget.getOrPut(rover.id) { "depository" }
                when (stage) {
                    "depository" -> {
                        val dist = currentPos.distanceTo(topology.depository)
                        if (dist <= 25.0) {
                            val toLoad = minOf(15.0, creatineManager.storedStock.coerceAtLeast(0.0))
                            if (toLoad > 0.0) {
                                creatineManager.storedStock -= toLoad
                                roverCreatineCargo[rover.id] = (roverCreatineCargo[rover.id] ?: 0.0) + toLoad
                                events += KernelEvent("CreatineLoadedAtDepository", rover.id, "storage/creatine", buildJsonObject {
                                    put("loaded", toLoad)
                                    put("cargo", roverCreatineCargo[rover.id] ?: 0.0)
                                })
                            }
                            roverLogisticsTarget[rover.id] = "medcenter"
                            vehicleRouteTarget[rover.id] = topology.medicalCenter
                        } else {
                            vehicleRouteTarget[rover.id] = topology.depository
                        }
                    }
                    "medcenter" -> {
                        val dist = currentPos.distanceTo(topology.medicalCenter)
                        if (dist <= 25.0) {
                            val cargo = roverCreatineCargo.remove(rover.id) ?: 0.0
                            if (cargo > 0.0) {
                                creatineManager.medicalCenterStock += cargo
                                events += KernelEvent("CreatineDeliveredToMedCenter", rover.id, "medical/center", buildJsonObject {
                                    put("delivered", cargo)
                                    put("medical_stock", creatineManager.medicalCenterStock)
                                })
                            }
                            roverLogisticsTarget[rover.id] = "depository"
                            vehicleRouteTarget[rover.id] = topology.depository
                        } else {
                            vehicleRouteTarget[rover.id] = topology.medicalCenter
                        }
                    }
                    else -> {
                        roverLogisticsTarget[rover.id] = "depository"
                        vehicleRouteTarget[rover.id] = topology.depository
                    }
                }
            }
        }

        // 3. Healing residents at the Medical Center
        for (resident in people.residents) {
            if (!isBroken(resident.id) && healthOf(resident.id) < config.creatine.lowHealthThreshold) {
                val pos = positionOf(resident.id)
                if (pos.distanceTo(topology.medicalCenter) <= 30.0) {
                    if (creatineManager.tryHeal(healthOf(resident.id))) {
                        health[resident.id] = 100.0
                        events += KernelEvent("ActionSucceeded", resident.id, "medical/center", buildJsonObject {
                            put("action", "medical.heal"); put("healed_to", 100.0)
                        })
                    }
                }
            }
        }
    }

    private fun squadAtBase(squad: String): Boolean = people.marines
        .filter { it.params["squad"]?.jsonPrimitive?.contentOrNull == squad && !isBroken(it.id) }
        .all { it.id !in passengerVehicle && positionOf(it.id).distanceTo(depotOf(it)) <= config.transport.boardRadius }

    /** Residents who ask to walk to where a rover stands at its depot are walking to that rover. */
    private fun walkersFrom(intents: List<VmIntent>, accepted: Set<String>): Map<String, List<String>> {
        val waiting = people.rovers.filter { !isBroken(it.id) && atDepot(it) }
        if (waiting.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (intent in intents) {
            if (intent.operation != Op.MOTION_REQUEST || intent.source !in accepted) continue
            if (people.byId[intent.source]?.kind != "Human" || intent.source in passengerVehicle || isBroken(intent.source)) continue
            val target = intent.arguments[0].jsonObject
            val goal = Point(target.getValue("x").jsonPrimitive.double, target.getValue("y").jsonPrimitive.double)
            val rover = waiting.firstOrNull { positionOf(it.id).distanceTo(goal) <= 1e-6 } ?: continue
            out.getOrPut(rover.id) { ArrayList() }.add(intent.source)
        }
        return out
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
        if (instance != null && instance.kind in setOf("Human", "Marine", "Xenomorph")) {
            return KernelEvent("EntityDied", target, actor, buildJsonObject { put("entity", target) })
        }
        openJob(target, tick)
        return KernelEvent("ObjectBroken", target, actor,
            buildJsonObject { put("object", target); put("reason", reason) }, listOfNotNull(ownerOf(target)))
    }

    private fun openJob(target: String, brokenAtTick: Long, customKind: String? = null, fastDispatch: Boolean = false) {
        if (target in jobs) return
        val kind = customKind ?: topology.byId[target]?.kind?.repairKey ?: "device"
        val delayTicks = if (fastDispatch) 2L else kotlin.math.ceil(config.repair.dispatchDelaySeconds / dt).toLong()
        val job = taskQueue.enqueueOrUpdate(
            targetId = target,
            kind = kind,
            position = positionOf(target),
            duration = config.repair.durationOf(kind),
            availableAtTick = brokenAtTick + delayTicks,
        )
        jobs[target] = job
    }

    /** The house that pays for an object: its own house for an appliance, the settlement for the grid. */
    private fun ownerOf(target: String): String? = people.byId[target]?.parent ?: people.byId[target]?.id?.takeIf { people.byId[it]?.kind == "House" }

    /** Phase 2. A crew makes progress while it stands next to the object it asked to repair. */
    private fun applyRepairs(tick: Long, intents: List<VmIntent>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        for (intent in intents) {
            if (intent.operation != Op.REPAIR_REQUEST || intent.source !in accepted) continue
            if (isBroken(intent.source) || vehiclePassengers[intent.source]?.isNotEmpty() == true) continue
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
            taskQueue.complete(target)
            health[target] = fullHealth(target)
            if (target in airDefenseUnits) {
                airDefenseUnits[target]?.repair()
            }
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
        val byClass = powerClasses.mapValues { (_, ids) -> ids.mapNotNull { id -> requested[id]?.let { id to it } } }
        var usedUps = 0.0
        for (klass in byClass.keys.sorted()) {
            val entries = byClass.getValue(klass)
            val wanted = entries.sumOf { it.second }
            if (wanted <= budget) {
                entries.forEach { granted[it.first] = it.second }
                budget -= wanted
                continue
            }
            // The battery covers what the grid cannot, then the class that is still short is cut in proportion.
            val fromBattery = minOf(fromUps - usedUps, wanted - budget)
            usedUps += fromBattery
            val available = budget + fromBattery
            val share = if (wanted > 0) available / wanted else 0.0
            entries.forEach { granted[it.first] = it.second * share }
            budget = 0.0
            for (rest in byClass.keys.sorted().filter { it > klass }) byClass.getValue(rest).forEach { granted[it.first] = 0.0 }
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

    private fun releaseInvalidPassengers(events: MutableList<KernelEvent>) {
        val deadPassengers = passengerVehicle.keys.filter { isBroken(it) }.toList()
        for (passenger in deadPassengers) passengerVehicle.remove(passenger)
        vehiclePassengers.values.forEach { it.removeAll(deadPassengers.toSet()) }
        for (vehicleId in vehiclePassengers.keys.toList()) {
            val passengers = vehiclePassengers.getValue(vehicleId)
            if (passengers.isEmpty()) {
                vehiclePassengers.remove(vehicleId)
                vehicleRouteTarget.remove(vehicleId)
                boardingSince.remove(vehicleId)
            } else if (passengers.any { passenger ->
                    squadOf(passenger) != null && (marineSquad(passenger).count { !isBroken(it.id) } < config.marine.minSquadSize ||
                        squadTarget(passenger) == null)
                }) {
                unboard(vehicleId, events, reason = "mission_cancelled")
            }
        }
    }

    /** Phase 5. Movement in a straight line at the speed asked for, then who is where. */
    private fun applyMovement(intents: List<VmIntent>, accepted: Set<String>, events: MutableList<KernelEvent>) {
        // Even after alighting, discard the rider's old motion request from this tick's snapshot.
        val ridersAtStart = passengerVehicle.keys.toSet()
        people.rovers.forEach { rover ->
            if (!isBroken(rover.id) && arrivedWithPassengers(rover.id)) {
                unboard(rover.id, events)
            }
        }
        for (intent in intents) {
            if (intent.operation != Op.MOTION_REQUEST || intent.source !in accepted || isBroken(intent.source)) continue
            if (intent.source in ridersAtStart) continue
            val target = intent.arguments[0].jsonObject
            val goal = Point(target.getValue("x").jsonPrimitive.double, target.getValue("y").jsonPrimitive.double)
            val isRover = people.byId[intent.source]?.kind == "Rover"
            if (vehiclePassengers[intent.source]?.isNotEmpty() == true &&
                (!transportReady(intent.source) || vehicleRouteTarget[intent.source]?.distanceTo(goal)?.let { it > 1e-6 } == true)) continue
            val requestedSpeed = intent.arguments[1].jsonPrimitive.double.coerceAtLeast(0.0)
            val speed = if (isRover) minOf(requestedSpeed, config.repair.roverSpeed) else requestedSpeed
            val here = positionOf(intent.source)
            val distance = here.distanceTo(goal)
            if (distance <= 1e-9) {
                // A patroller already standing on its waypoint is sent on, or it would wait there for ever.
                if (people.byId[intent.source]?.kind == "Xenomorph" && goal.distanceTo(patrolPoint(intent.source)) < 1.0) advancePatrol(intent.source)
                continue
            }
            var ratio = minOf(1.0, speed * dt / distance)
            var newPosition = if (ratio >= 1.0) goal else Point(here.x + (goal.x - here.x) * ratio, here.y + (goal.y - here.y) * ratio)
            if (people.byId[intent.source]?.kind == "Xenomorph") {
                fenceStop(intent.source, here, newPosition)?.let { stop -> newPosition = stop; ratio = 0.0 }
            }
            // One that has left the settlement roams on from where it came out, not from where it went in.
            if (people.byId[intent.source]?.kind == "Xenomorph" && settlementArea.contains(here) && !settlementArea.contains(newPosition)) {
                patrolAlong[intent.source] = perimeterAlong(newPosition)
            }
            position[intent.source] = newPosition
            spatialIndex.update(intent.source, newPosition)

            // Riders occupy the rover's physical position, not their previous world coordinates.
            if (isRover) {
                vehiclePassengers[intent.source].orEmpty().forEach { passenger ->
                    position[passenger] = newPosition
                    spatialIndex.update(passenger, newPosition)
                }
                if (arrivedWithPassengers(intent.source)) {
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

    /**
     * A xenomorph that would cross a closed fence segment stops half a metre short of it and remembers the
     * segment, which then shows among what it can attack. Anything else, and a broken segment, lets it through.
     */
    private fun fenceStop(id: String, from: Point, to: Point): Point? {
        val box = topology.fenceBox ?: return null
        if (box.contains(from) == box.contains(to)) { blockedBy.remove(id); return null }
        val crossing = borderCrossing(box, from, to)
        val segment = topology.fence.minWith(compareBy({ it.nearestPointTo(crossing).distanceTo(crossing) }, { it.id }))
        if (isBroken(segment.id)) { blockedBy.remove(id); return null }
        blockedBy[id] = segment.id
        val length = from.distanceTo(crossing)
        val keep = if (length <= 0.0) 0.0 else ((length - FENCE_GAP) / length).coerceAtLeast(0.0)
        return Point(from.x + (crossing.x - from.x) * keep, from.y + (crossing.y - from.y) * keep)
    }

    /** Where the segment from [p] to [q] meets the border of [box], one end being inside and the other outside. */
    private fun borderCrossing(box: Box, p: Point, q: Point): Point {
        val dx = q.x - p.x
        val dy = q.y - p.y
        var enter = 0.0
        var exit = 1.0
        for ((along, room) in listOf(-dx to p.x - box.minX, dx to box.maxX - p.x, -dy to p.y - box.minY, dy to box.maxY - p.y)) {
            if (along == 0.0) continue
            val t = room / along
            if (along < 0) enter = maxOf(enter, t) else exit = minOf(exit, t)
        }
        val t = if (box.contains(p)) exit else enter
        return Point(p.x + dx * t, p.y + dy * t)
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
        /** How far short of a closed fence a xenomorph stops. */
        const val FENCE_GAP = 0.5
        /** A routed xenomorph lines up this far inside a breach and runs this far beyond the fence. */
        const val EXIT_STEP = 5.0
        const val EXIT_RUN = 25.0
        const val BUS = "grid/bus"
        const val PUMP = "water/pump"
        const val MAX_POSTINGS = 20_000
    }
}
