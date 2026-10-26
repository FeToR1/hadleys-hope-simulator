package colony.world

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class EcosystemEntity(val id: String, val kind: String, val at: Point, val health: Double = 100.0,
    val available: Boolean = true, val inSeaVapor: Boolean = false, val respiratorEquipped: Boolean = false)
data class EcosystemEvent(val type: String, val entityId: String, val actorId: String? = null, val fields: Map<String, String> = emptyMap())
data class EcosystemDamage(val targetId: String, val amount: Double, val reason: String, val actorId: String? = null)
data class EcosystemPosting(val owner: String, val kind: String, val amount: Long)
data class EcosystemZoneSnapshot(val id: String, val bounds: Box, val forestBiomass: Double, val manure: Double, val planktonBiomass: Double)
data class EcosystemServiceTarget(val actorId: String, val target: Point, val task: String)
data class EcosystemStepResult(
    val events: List<EcosystemEvent> = emptyList(), val damages: List<EcosystemDamage> = emptyList(),
    val postings: List<EcosystemPosting> = emptyList(), val zones: List<EcosystemZoneSnapshot> = emptyList(),
    val serviceTargets: List<EcosystemServiceTarget> = emptyList(), val unavailableHumans: Set<String> = emptySet(),
)
data class PredatorMutation(val name: String, val damageMultiplier: Double, val speedMultiplier: Double)

/** Stateful ecology services over a fixed settlement grid. WorldKernel owns and resolves every damage request. */
class EcosystemController(private val config: EcosystemConfig = EcosystemConfig(), private val seed: Long = 0L) {
    private data class Zone(val x: Int, val y: Int, val forest: Boolean, var forestMass: Double, var manure: Double = 0.0, var plankton: Double = 0.0)
    private data class CleanupTask(var zone: String? = null, var load: Double = 0.0, var at: Point? = null, var fuel: Double = 0.0)
    private val zones = sortedMapOf<String, Zone>()
    private val cleanupTasks = sortedMapOf<String, CleanupTask>()
    private val costRemainders = sortedMapOf<String, Double>()
    private val humanUnavailable = sortedSetOf<String>()
    private var bounds: Box? = null
    private var coastX = 0.0
    private var minGridX = 0
    private var maxGridX = 0
    private var minGridY = 0
    private var maxGridY = 0
    private var unloadAt = config.cleanup.unloadAt
    private var fuel = config.plankton.initialBurnFuel
    private var depotFuel = config.cleanup.initialDepotFuel
    private var lastElapsed = -1.0
    private var lastWave = -1L
    private val mutations = listOf("armored", "swift", "venomous", "pack")
    private val allowedMutations = config.predator.mutations.filter { it in mutations }.ifEmpty { mutations }
    private val cellSize = config.zoneSize
    val burnFuelRemaining: Double get() = fuel
    val depotFuelRemaining: Double get() = depotFuel

    /** Call exactly once with the static city rectangle; this creates a finite grid plus three forest margins. */
    fun configureBounds(settlement: Box, seaCoastX: Double, depository: Point = config.cleanup.unloadAt) {
        require(settlement.maxX > settlement.minX && settlement.maxY > settlement.minY && seaCoastX.isFinite())
        bounds = settlement; coastX = seaCoastX; unloadAt = depository; zones.clear()
        val minCellX = floor(settlement.minX / cellSize).toInt() - 3
        val maxCellX = floor(settlement.maxX / cellSize).toInt() + 3
        val minCellY = floor(settlement.minY / cellSize).toInt() - 3
        val maxCellY = floor(settlement.maxY / cellSize).toInt() + 3
        minGridX = minCellX; maxGridX = maxCellX; minGridY = minCellY; maxGridY = maxCellY
        for (x in minCellX..maxCellX) for (y in minCellY..maxCellY) {
            val cx = (x + .5) * cellSize; val cy = (y + .5) * cellSize
            val interior = cx >= settlement.minX && cx <= settlement.maxX && cy >= settlement.minY && cy <= settlement.maxY
            val outsideSettlement = (x + 1) * cellSize <= settlement.minX || x * cellSize >= settlement.maxX ||
                (y + 1) * cellSize <= settlement.minY || y * cellSize >= settlement.maxY
            val forest = outsideSettlement && cx < seaCoastX && (cx < settlement.minX || cy < settlement.minY || cy > settlement.maxY)
            zones[key(x, y)] = Zone(x, y, forest, if (forest && config.forest.enabled) config.forest.initialBiomassPerZone else 0.0,
                plankton = if (cx >= seaCoastX - config.plankton.coastalWidth && cx <= seaCoastX && config.plankton.enabled) config.plankton.initialBiomassPerCoastalZone else 0.0)
        }
    }

    private fun key(x: Int, y: Int) = "$x:$y"
    private fun key(at: Point) = key(floor(at.x / cellSize).toInt(), floor(at.y / cellSize).toInt())
    private fun center(zone: Zone) = Point((zone.x + .5) * cellSize, (zone.y + .5) * cellSize)
    private fun zoneAt(at: Point) = zones[key(at)]
    private fun stable(value: String): Long { var h = seed; value.forEach { h = h * 31L + it.code }; return h }

    /** Finite phenotype cycles deterministically by entity and model-time epoch. */
    fun mutationOf(entityId: String, elapsedSeconds: Double): PredatorMutation {
        if (!config.enabled || !config.predator.enabled) return PredatorMutation("baseline", 1.0, 1.0)
        val size = allowedMutations.size.toLong()
        val entityIndex = Math.floorMod(stable(entityId), size)
        val epoch = Math.floorMod(floor(elapsedSeconds / 600.0).toLong(), size)
        val name = allowedMutations[Math.floorMod(entityIndex + epoch, size).toInt()]
        return when (name) {
            "armored" -> PredatorMutation(name, 1.35, .8)
            "swift" -> PredatorMutation(name, .8, 1.4)
            "venomous" -> PredatorMutation(name, 1.2, 1.0)
            else -> PredatorMutation(name, 1.1, 1.15)
        }
    }

    fun attackActive(entityId: String, elapsedSeconds: Double): Boolean {
        if (!config.enabled || !config.predator.enabled) return true
        val period = config.predator.attackPeriodSeconds
        val offset = Math.floorMod(stable(entityId), 1_000_000_000L).toDouble() % period
        val phase = ((elapsedSeconds % period) + offset) % period
        return phase < config.predator.attackDurationSeconds
    }

    fun isForest(point: Point): Boolean = config.enabled && config.forest.enabled && zoneAt(point)?.forest == true
    fun isBlocked(point: Point): Boolean = isForest(point) && (zoneAt(point)?.forestMass ?: 0.0) > 1.0
    /** Returns the first point where a segment enters a currently impassable forest cell. */
    fun firstForestBlock(from: Point, to: Point): Point? {
        if (!config.enabled || !config.forest.enabled || !listOf(from.x, from.y, to.x, to.y).all { it.isFinite() }) return null
        val originalDx = to.x - from.x; val originalDy = to.y - from.y
        val gridLeft = minGridX * cellSize; val gridRight = (maxGridX + 1) * cellSize
        val gridTop = minGridY * cellSize; val gridBottom = (maxGridY + 1) * cellSize
        var enter = 0.0; var leave = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val r = q / p
            if (p < 0.0) { if (r > leave) return false; enter = max(enter, r) }
            else { if (r < enter) return false; leave = min(leave, r) }
            return true
        }
        if (!originalDx.isFinite() || !originalDy.isFinite() || !clip(-originalDx, from.x - gridLeft) || !clip(originalDx, gridRight - from.x) ||
            !clip(-originalDy, from.y - gridTop) || !clip(originalDy, gridBottom - from.y) || enter > leave) return null
        val dx = originalDx * (leave - enter); val dy = originalDy * (leave - enter)
        val startX = from.x + originalDx * enter; val startY = from.y + originalDy * enter
        var cellX = floor(startX / cellSize).toInt().coerceIn(minGridX, maxGridX)
        var cellY = floor(startY / cellSize).toInt().coerceIn(minGridY, maxGridY)
        fun blocked(x: Int, y: Int) = zones[key(x, y)]?.let { it.forest && it.forestMass > 1.0 } == true
        if (blocked(cellX, cellY)) return Point(startX, startY)
        val stepX = dx.compareTo(0.0)
        val stepY = dy.compareTo(0.0)
        val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else cellSize / kotlin.math.abs(dx)
        val deltaY = if (stepY == 0) Double.POSITIVE_INFINITY else cellSize / kotlin.math.abs(dy)
        var maxX = if (stepX > 0) ((cellX + 1) * cellSize - startX) / dx
            else if (stepX < 0) (cellX * cellSize - startX) / dx else Double.POSITIVE_INFINITY
        var maxY = if (stepY > 0) ((cellY + 1) * cellSize - startY) / dy
            else if (stepY < 0) (cellY * cellSize - startY) / dy else Double.POSITIVE_INFINITY
        var visited = 0
        val maxVisited = (maxGridX - minGridX + maxGridY - minGridY + 4).coerceAtLeast(4)
        while (min(maxX, maxY) <= 1.0 && visited++ < maxVisited) {
            val t = min(maxX, maxY).coerceAtLeast(0.0)
            val crossX = maxX <= maxY
            val crossY = maxY <= maxX
            if (crossX) { cellX += stepX; maxX += deltaX }
            if (crossY) { cellY += stepY; maxY += deltaY }
            if (blocked(cellX, cellY)) return Point(startX + dx * t, startY + dy * t)
        }
        return null
    }
    fun movementMultiplier(point: Point): Double = if ((zoneAt(point)?.manure ?: 0.0) > 0.0) .7 else 1.0
    fun movementBudgetMeters(actorId: String): Double = cleanupTasks[actorId]?.let {
        if (config.cleanup.fuelPerMetre <= 0.0) Double.POSITIVE_INFINITY else it.fuel / config.cleanup.fuelPerMetre
    } ?: Double.POSITIVE_INFINITY
    fun serviceFuelRemaining(actorId: String): Double = cleanupTasks[actorId]?.fuel ?: config.cleanup.initialFuel

    private fun postingFor(owner: String, kind: String, rawCost: Double): EcosystemPosting? {
        val key = "$owner:$kind"
        val total = costRemainders.getOrDefault(key, 0.0) + rawCost
        val amount = floor(total).toLong().coerceAtLeast(0L)
        costRemainders[key] = total - amount
        return if (amount > 0L) EcosystemPosting(owner, kind, amount) else null
    }

    /** The kernel calls this only for a physically present, authorized forestry crew. */
    fun clearForest(zoneId: String, actorId: String, actorAt: Point, dt: Double): EcosystemEvent? {
        if (!config.enabled || !config.forest.enabled || dt <= 0.0) return null
        val zone = zones[zoneId] ?: return null
        val boundary = bounds ?: return null
        val workPoint = Point(center(zone).x.coerceIn(boundary.minX, boundary.maxX), center(zone).y.coerceIn(boundary.minY, boundary.maxY))
        if (!zone.forest || actorAt.distanceTo(workPoint) > cellSize * .75 || zone.forestMass <= 0.0) return null
        zone.forestMass = max(0.0, zone.forestMass - config.forest.clearingPerSecond * dt)
        return EcosystemEvent("ForestCleared", zoneId, actorId, mapOf("biomass" to zone.forestMass.toString()))
    }

    /** WorldKernel passes only the power actually granted by its shared grid this tick. */
    fun startBurn(zoneId: String, actorId: String, actorAt: Point, dt: Double, grantedPowerW: Double): Pair<EcosystemEvent?, EcosystemPosting?> {
        if (!config.enabled || !config.plankton.enabled || dt <= 0.0 || grantedPowerW <= 0.0) return null to null
        val zone = zones[zoneId] ?: return null to null
        if (key(actorAt) != zoneId || zone.plankton <= 0.0 || fuel <= 0.0) return null to null
        val powerAvailable = min(grantedPowerW, config.plankton.burnPowerPerSecond)
        val powerLimitedBiomass = if (config.plankton.burnPowerPerSecond == 0.0) 0.0 else powerAvailable * dt / config.plankton.burnPowerPerSecond * config.plankton.burningPerSecond
        val fuelLimitedBiomass = if (config.plankton.burnFuelPerBiomass == 0.0) config.plankton.burningPerSecond * dt else fuel / config.plankton.burnFuelPerBiomass
        val amount = min(zone.plankton, min(config.plankton.burningPerSecond * dt, min(powerLimitedBiomass, fuelLimitedBiomass)))
        if (amount <= 0.0) return null to null
        val consumedFuel = amount * config.plankton.burnFuelPerBiomass
        fuel = max(0.0, fuel - consumedFuel)
        zone.plankton -= amount
        val posting = postingFor(actorId, "burn", consumedFuel * config.plankton.fuelCostPerUnit)
        return EcosystemEvent("PlanktonBurned", zoneId, actorId, mapOf("amount" to amount.toString(), "fuel" to consumedFuel.toString(), "powerW" to powerAvailable.toString())) to
            posting
    }

    fun step(elapsedSeconds: Double, dt: Double, entities: List<EcosystemEntity>, grantedBurnPowerW: Map<String, Double> = emptyMap()): EcosystemStepResult {
        if (!config.enabled || dt <= 0.0) return EcosystemStepResult()
        require(elapsedSeconds.isFinite() && dt.isFinite() && dt >= 0.0 && elapsedSeconds >= lastElapsed) { "Ecosystem time must be finite and monotonic" }
        lastElapsed = elapsedSeconds
        if (bounds == null) return EcosystemStepResult()
        if (config.cleanup.enabled) depotFuel = min(config.cleanup.depotFuelCapacity,
            depotFuel + config.cleanup.fuelRestockPerSecond * dt)
        if (config.plankton.enabled) fuel = min(config.plankton.fuelCapacity,
            fuel + config.plankton.fuelRestockPerSecond * dt)
        // Group dynamic occupants once. Moving entities outside the configured finite rectangle are ignored.
        val byZone = sortedMapOf<String, MutableList<EcosystemEntity>>()
        for (entity in entities.sortedBy { it.id }) if (zoneAt(entity.at) != null) byZone.getOrPut(key(entity.at)) { ArrayList() }.add(entity)
        val events = ArrayList<EcosystemEvent>(); val damages = ArrayList<EcosystemDamage>(); val postings = ArrayList<EcosystemPosting>()
        val unavailable = sortedSetOf<String>(); val targets = ArrayList<EcosystemServiceTarget>()
        val waveNumber = if (config.plankton.enabled) floor(elapsedSeconds / config.plankton.wavePeriodSeconds).toLong() else -1L
        val nowWave = waveNumber > lastWave
        if (nowWave) lastWave = waveNumber
        for ((zoneId, zone) in zones) {
            if (zone.forest && config.forest.enabled) zone.forestMass = min(config.forest.initialBiomassPerZone, zone.forestMass + config.forest.regrowthPerSecond * dt)
            if (config.plankton.enabled && center(zone).x >= coastX - config.plankton.coastalWidth && center(zone).x <= coastX) {
                if (nowWave) { zone.plankton = config.plankton.initialBiomassPerCoastalZone; events += EcosystemEvent("PlanktonWave", zoneId) }
                else zone.plankton = min(config.plankton.initialBiomassPerCoastalZone, zone.plankton + config.plankton.regrowthPerSecond * dt)
            }
        }
        for ((zoneId, members) in byZone) {
            val zone = zones.getValue(zoneId)
            zone.manure += members.count { it.kind == "crocodile" } * config.crocodileManurePerSecond * dt
            val local = members.sortedBy { it.id }
            if (config.plankton.enabled && zone.plankton > 0.0) {
                val victim = local.firstOrNull { it.kind in DAMAGEABLE_KINDS && it.health > 0.0 }
                if (victim != null) damages += EcosystemDamage(victim.id, min(zone.plankton, config.plankton.damagePerSecond * dt), "PredatoryPlankton")
            }
            for (burner in local.filter { it.kind == "burner" && it.available && it.health > 0.0 }) {
                if (zone.plankton > 0.0) targets += EcosystemServiceTarget(burner.id, center(zone), "burn-plankton")
                val granted = grantedBurnPowerW[burner.id] ?: 0.0
                if (zone.plankton > 0.0 && granted > 0.0) startBurn(zoneId, burner.id, burner.at, dt, granted).also { (event, posting) ->
                    event?.let(events::add); posting?.let(postings::add)
                }
            }
            val cleaners = local.filter { it.kind == "cleanup-rover" && it.available && it.health > 0.0 }.sortedBy { it.id }
            for (cleaner in cleaners) {
                val task = cleanupTasks.getOrPut(cleaner.id) { CleanupTask(at = cleaner.at, fuel = config.cleanup.initialFuel) }
                val previous = task.at ?: cleaner.at
                val travelled = previous.distanceTo(cleaner.at)
                val fuelUsed = travelled * config.cleanup.fuelPerMetre
                if (config.cleanup.enabled && fuelUsed > 0.0) {
                    val spent = min(task.fuel, fuelUsed)
                    task.fuel = max(0.0, task.fuel - spent)
                    postingFor(cleaner.id, "cleanup", spent * config.cleanup.fuelPricePerUnit)?.let(postings::add)
                }
                task.at = cleaner.at
                // Refills draw from one finite depot stock only after paying for this tick's travel.
                if (config.cleanup.enabled && cleaner.at.distanceTo(unloadAt) <= config.cleanup.unloadRadius) {
                    val refill = min(config.cleanup.initialFuel - task.fuel, depotFuel)
                    if (refill > 0.0) { task.fuel += refill; depotFuel -= refill
                        events += EcosystemEvent("CleanupFuelLoaded", cleaner.id, cleaner.id, mapOf("amount" to refill.toString(), "remaining" to depotFuel.toString())) }
                }
                if (task.load > 0.0 && task.load >= config.cleanup.loadCapacity) task.zone = null
                if (task.load >= config.cleanup.loadCapacity || (task.load > 0.0 && task.zone == null)) {
                    if (task.fuel <= 0.0 && cleaner.at.distanceTo(unloadAt) > config.cleanup.unloadRadius) continue
                    targets += EcosystemServiceTarget(cleaner.id, unloadAt, "unload-manure")
                    if (cleaner.at.distanceTo(unloadAt) <= config.cleanup.unloadRadius) {
                        val unloaded = task.load; task.load = 0.0; task.zone = null
                        events += EcosystemEvent("ManureUnloaded", cleaner.id, cleaner.id, mapOf("amount" to unloaded.toString()))
                    }
                    continue
                }
                val depotReserve = cleaner.at.distanceTo(unloadAt) * config.cleanup.fuelPerMetre +
                    config.cleanup.fuelPerUnit * config.cleanup.cleanPerSecond * dt
                if (task.fuel <= depotReserve && cleaner.at.distanceTo(unloadAt) > config.cleanup.unloadRadius && depotFuel > 0.0) {
                    targets += EcosystemServiceTarget(cleaner.id, unloadAt, "refuel-cleanup-rover")
                    continue
                }
                if (!config.cleanup.enabled || task.fuel <= 0.0 || task.load >= config.cleanup.loadCapacity) continue
                // Cleanup crews stay on accessible settlement cells; dense forest cells are reached by foresters only.
                val assigned = task.zone?.let(zones::get)?.takeIf { !it.forest && it.manure > 0.0 }
                val targetZone = assigned ?: zones.entries.asSequence().filter { !it.value.forest && it.value.manure > 0.0 }
                    .minWithOrNull(compareBy<Map.Entry<String, Zone>> { cleaner.at.distanceTo(center(it.value)) }.thenBy { it.key })?.value
                if (targetZone == null) continue
                task.zone = key(targetZone.x, targetZone.y)
                targets += EcosystemServiceTarget(cleaner.id, center(targetZone), "clean-manure")
                if (key(cleaner.at) == task.zone && task.fuel > 0.0) {
                    val returnFuelReserve = cleaner.at.distanceTo(unloadAt) * config.cleanup.fuelPerMetre
                    val availableWorkFuel = max(0.0, task.fuel - returnFuelReserve)
                    val amount = min(targetZone.manure, min(config.cleanup.cleanPerSecond * dt, min(config.cleanup.loadCapacity - task.load,
                        if (config.cleanup.fuelPerUnit <= 0.0) Double.POSITIVE_INFINITY else availableWorkFuel / config.cleanup.fuelPerUnit)))
                    if (amount > 0) {
                        val usedFuel = amount * config.cleanup.fuelPerUnit
                        task.fuel = max(0.0, task.fuel - usedFuel)
                        targetZone.manure -= amount; task.load += amount
                        events += EcosystemEvent("ManureCleaned", task.zone!!, cleaner.id, mapOf("amount" to amount.toString(), "load" to task.load.toString()))
                        postingFor(cleaner.id, "cleanup", usedFuel * config.cleanup.fuelPricePerUnit + amount * config.cleanup.costPerUnit)?.let(postings::add)
                        if (task.load >= config.cleanup.loadCapacity || targetZone.manure <= 0.0) task.zone = null
                    }
                }
            }
            if (config.humanFactors.enabled) for (human in local.filter { it.kind == "civilian" && it.health > 0.0 }) {
                val offset = (Math.floorMod(stable(human.id), 1_000_000L)).toDouble() % config.humanFactors.availabilityCycleSeconds
                val phase = (elapsedSeconds + offset) % config.humanFactors.availabilityCycleSeconds
                val bored = phase < config.humanFactors.boredomSeconds
                val alcoholPhase = (elapsedSeconds + offset / 2.0) % config.humanFactors.alcoholPeriodSeconds
                val drunk = alcoholPhase < config.humanFactors.alcoholDurationSeconds
                val vapor = human.inSeaVapor && !human.respiratorEquipped
                val isUnavailable = !human.available || (bored && phase < config.humanFactors.unavailableSeconds) || drunk || vapor
                if (isUnavailable) unavailable += human.id
                if (isUnavailable != (human.id in humanUnavailable)) {
                    events += EcosystemEvent(if (isUnavailable) "HumanUnavailable" else "HumanAvailable", human.id,
                        fields = mapOf("bored" to bored.toString(), "alcohol" to drunk.toString(), "seaVapor" to vapor.toString()))
                    if (isUnavailable) humanUnavailable += human.id else humanUnavailable -= human.id
                }
                if ((bored || drunk) && elapsedSeconds % config.humanFactors.vandalPeriodSeconds < dt &&
                    Math.floorMod(stable("vandal:${human.id}:${(elapsedSeconds / config.humanFactors.vandalPeriodSeconds).toLong()}"), 100L) < config.humanFactors.vandalChancePercent.toLong()) {
                    val victim = local.filter { it.kind in setOf("house", "power_node") && it.health > 0.0 && it.at.distanceTo(human.at) <= config.humanFactors.vandalRadius }
                        .minByOrNull { it.at.distanceTo(human.at) }
                    if (victim != null) { damages += EcosystemDamage(victim.id, config.humanFactors.vandalDamage, "HumanVandalism", human.id); events += EcosystemEvent("Vandalism", victim.id, human.id, mapOf("bored" to bored.toString(), "alcohol" to drunk.toString())) }
                }
            }
        }
        if (config.enabled && config.forest.enabled) {
            val crewIds = entities.filter { it.kind == "forester" && it.available && it.health > 0.0 }.map { it.id }.toSet()
            for (crew in entities.filter { it.id in crewIds }.sortedBy { it.id }) {
                val forest = zones.entries.asSequence().filter { it.value.forest && it.value.forestMass > 0.0 }
                    .minWithOrNull(compareBy<Map.Entry<String, Zone>> { crew.at.distanceTo(center(it.value)) }.thenBy { it.key }) ?: continue
                val b = bounds!!
                val c = center(forest.value)
                val edge = Point(c.x.coerceIn(b.minX, b.maxX), c.y.coerceIn(b.minY, b.maxY))
                targets += EcosystemServiceTarget(crew.id, edge, "clear-forest")
                clearForest(forest.key, crew.id, crew.at, dt)?.let(events::add)
            }
        }
        val snapshots = zones.map { (zoneId, zone) -> val c = center(zone); EcosystemZoneSnapshot(zoneId,
            Box(c.x - cellSize / 2, c.y - cellSize / 2, c.x + cellSize / 2, c.y + cellSize / 2), zone.forestMass, zone.manure, zone.plankton) }
        return EcosystemStepResult(events.toList(), damages.toList(), postings.toList(), snapshots.toList(), targets.toList(), unavailable.toSet())
    }

    private companion object { val DAMAGEABLE_KINDS = setOf("house", "mine", "power_node", "air_defense", "ground_turret", "burner", "depository", "medical_center", "fence", "rover") }
}
