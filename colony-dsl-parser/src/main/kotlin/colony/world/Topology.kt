package colony.world

import colony.runtime.Instance
import colony.runtime.RunManifest
import kotlinx.serialization.Serializable

/** A point in the settlement, in metres. */
@Serializable data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double = Math.hypot(x - other.x, y - other.y)
}

/** Kinds of object the world owns without giving them a program of their own. */
enum class FixtureKind(val repairKey: String) {
    REACTOR("device"), SOLAR("device"), UPS("device"), PUMP("device"), POLE("pole"), PIPE("pipe"), FENCE("fence"),
    AIR_DEFENSE("device"), DEPOSITORY("device"), MEDICAL_CENTER("device")
}

/** An axis-aligned rectangle; the fence runs along its border. */
@Serializable data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun contains(p: Point): Boolean = p.x > minX && p.x < maxX && p.y > minY && p.y < maxY
}

/**
 * An object of the settlement that has no VM: a pole, a pipe, a source, an air defense turret, depository. It has a position, health and, for
 * the grid, the node it feeds from (docs/spec/runtime-tick.md#runtime).
 */
@Serializable data class Fixture(
    val id: String, val kind: FixtureKind, val at: Point, val feedsFrom: String? = null, val serves: String? = null,
    /** The ends of a fence segment; [at] is its middle. */
    val from: Point? = null, val to: Point? = null,
) {
    /** The point of the object nearest to [p]: the object itself, or the nearest point along a segment. */
    fun nearestPointTo(p: Point): Point {
        val a = from ?: return at
        val b = to ?: return at
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return Point(a.x + dx * t, a.y + dy * t)
    }
}

/**
 * Where everything stands and what feeds what. The power grid is a tree from the sources through poles to the
 * houses; the water network is the pump and one pipe per house. Appliances follow their house.
 */
@Serializable data class Topology(
    val fixtures: List<Fixture>,
    /** Entity id to the grid node that feeds it. */
    val powerFeed: Map<String, String>,
    /** House id to the pipe that serves it. */
    val waterPipe: Map<String, String>,
    val houses: List<String>,
    val sources: List<String>,
    /** Routine destinations: the mine outside the housing area, services and a meeting place in the colony. */
    val mine: Point,
    val services: Point,
    val meeting: Point,
    /** The fenced area, when the settlement has a fence. */
    val fenceBox: Box? = null,
    val depository: Point = Point((mine.x + services.x) / 2.0, (mine.y + services.y) / 2.0),
    val medicalCenter: Point = Point(services.x, services.y - 30.0),
    val seaCoastX: Double = mine.x + 80.0,
) {
    val byId: Map<String, Fixture> = fixtures.associateBy { it.id }
    val poles: List<Fixture> = fixtures.filter { it.kind == FixtureKind.POLE }
    val fence: List<Fixture> = fixtures.filter { it.kind == FixtureKind.FENCE }
    val airDefenses: List<Fixture> = fixtures.filter { it.kind == FixtureKind.AIR_DEFENSE }
}

/**
 * Builds the settlement from the manifest: a reactor, a solar station and a battery feed a bus, the bus feeds
 * one pole per group of houses, and every house hangs off its pole and off its own water pipe. Deterministic,
 * so the same manifest always gives the same settlement.
 */
fun buildTopology(manifest: RunManifest, config: WorldConfig): Topology {
    val houses = manifest.instances.filter { it.kind == "House" }.map { it.id }
    require(houses.isNotEmpty()) { "A physical world needs at least one house" }
    val positions = manifest.instances.associate { it.id to Point(it.x, it.y) }
    val fixtures = ArrayList<Fixture>()
    val powerFeed = HashMap<String, String>()
    val waterPipe = HashMap<String, String>()

    val westmost = houses.minOf { positions.getValue(it).x }
    val northmost = houses.minOf { positions.getValue(it).y }
    val bus = Fixture("grid/bus", FixtureKind.POLE, Point(westmost - 120.0, northmost - 120.0))
    fixtures += bus
    val sources = listOf(
        Fixture("grid/reactor", FixtureKind.REACTOR, Point(bus.at.x - 60.0, bus.at.y), feedsFrom = bus.id),
        Fixture("grid/solar", FixtureKind.SOLAR, Point(bus.at.x, bus.at.y - 60.0), feedsFrom = bus.id),
        Fixture("grid/ups", FixtureKind.UPS, Point(bus.at.x + 60.0, bus.at.y), feedsFrom = bus.id),
    )
    fixtures += sources
    val pump = Fixture("water/pump", FixtureKind.PUMP, Point(bus.at.x, bus.at.y + 60.0), feedsFrom = bus.id)
    fixtures += pump
    powerFeed[pump.id] = bus.id

    // One pole for every group of houses, standing next to the first house of its group.
    for ((index, group) in houses.chunked(config.power.housesPerPole).withIndex()) {
        val anchor = positions.getValue(group.first())
        val pole = Fixture("grid/pole-${index + 1}", FixtureKind.POLE, Point(anchor.x - 25.0, anchor.y - 25.0), feedsFrom = bus.id)
        fixtures += pole
        for (house in group) {
            powerFeed[house] = pole.id
            val pipe = Fixture("water/pipe-${house.replace('/', '-')}", FixtureKind.PIPE, positions.getValue(house), serves = house)
            fixtures += pipe
            waterPipe[house] = pipe.id
        }
    }
    val housePoints = houses.map(positions::getValue)
    val mine = Point(housePoints.maxOf { it.x } + 120.0, housePoints.map { it.y }.average())
    val services = Point(housePoints.map { it.x }.average(), housePoints.minOf { it.y } - 90.0)
    val meeting = Point(services.x, services.y + 45.0)
    val depository = Point((mine.x + services.x) / 2.0, (mine.y + services.y) / 2.0)
    val medicalCenter = Point(services.x, services.y - 30.0)
    val seaCoastX = housePoints.maxOf { it.x } + 90.0

    fixtures += Fixture("storage/creatine", FixtureKind.DEPOSITORY, depository)
    fixtures += Fixture("medical/center", FixtureKind.MEDICAL_CENTER, medicalCenter)

    // Air Defense: Roof-mounted on selected houses and along settlement perimeter
    for ((index, house) in houses.withIndex()) {
        if (index % 12 == 0) {
            val pos = positions.getValue(house)
            fixtures += Fixture("defense/roof-${house.replace('/', '-')}", FixtureKind.AIR_DEFENSE, pos, feedsFrom = powerFeed[house])
        }
    }
    val minX = housePoints.minOf { it.x }
    val maxX = housePoints.maxOf { it.x }
    val minY = housePoints.minOf { it.y }
    val maxY = housePoints.maxOf { it.y }
    val perimeterPoints = listOf(
        Point(minX - 30.0, minY - 30.0),
        Point(minX - 30.0, (minY + maxY) / 2),
        Point(minX - 30.0, maxY + 30.0),
        Point((minX + maxX) / 2, minY - 30.0),
        Point((minX + maxX) / 2, maxY + 30.0),
        Point(maxX + 30.0, minY - 30.0),
        Point(maxX + 30.0, maxY + 30.0)
    )
    for ((idx, pt) in perimeterPoints.withIndex()) {
        fixtures += Fixture("defense/perimeter-${idx + 1}", FixtureKind.AIR_DEFENSE, pt, feedsFrom = bus.id)
    }

    // The fence encloses everything the colony owns and uses; the xenomorphs/threats are the ones it keeps out.
    val fenceBox = if (!config.fence.enabled) null else {
        val enclosed = fixtures.map { it.at } + listOf(mine, services, meeting, depository, medicalCenter) +
            manifest.instances.filter { it.kind != "Xenomorph" }.map { Point(it.x, it.y) }
        val margin = config.fence.margin
        Box(enclosed.minOf { it.x } - margin, enclosed.minOf { it.y } - margin,
            enclosed.maxOf { it.x } + margin, enclosed.maxOf { it.y } + margin).also { box ->
            val corners = listOf(Point(box.minX, box.minY), Point(box.maxX, box.minY), Point(box.maxX, box.maxY), Point(box.minX, box.maxY))
            var index = 0
            for (side in corners.indices) {
                val a = corners[side]
                val b = corners[(side + 1) % corners.size]
                val pieces = maxOf(1, kotlin.math.ceil(a.distanceTo(b) / config.fence.segmentLength).toInt())
                for (piece in 0 until pieces) {
                    val from = Point(a.x + (b.x - a.x) * piece / pieces, a.y + (b.y - a.y) * piece / pieces)
                    val to = Point(a.x + (b.x - a.x) * (piece + 1) / pieces, a.y + (b.y - a.y) * (piece + 1) / pieces)
                    fixtures += Fixture("fence/${++index}", FixtureKind.FENCE, Point((from.x + to.x) / 2, (from.y + to.y) / 2), from = from, to = to)
                }
            }
        }
    }

    // An appliance draws through the house it belongs to.
    for (instance in manifest.instances) {
        val parent = instance.parent ?: continue
        if (parent in powerFeed) powerFeed[instance.id] = powerFeed.getValue(parent)
    }
    return Topology(fixtures, powerFeed, waterPipe, houses, sources.map { it.id }, mine, services, meeting, fenceBox, depository, medicalCenter, seaCoastX)
}

/** Instances by id, with the roles the kernel needs to find quickly. */
internal class Population(manifest: RunManifest) {
    val byId: Map<String, Instance> = manifest.instances.associateBy { it.id }
    val houses: List<Instance> = manifest.instances.filter { it.kind == "House" }
    val appliances: List<Instance> = manifest.instances.filter { it.kind == "Heater" || it.kind == "Kettle" }
    val residents: List<Instance> = manifest.instances.filter { it.kind == "Human" }
    val marines: List<Instance> = manifest.instances.filter { it.kind == "Marine" }
    val xenomorphs: List<Instance> = manifest.instances.filter { it.kind == "Xenomorph" }
    val rovers: List<Instance> = manifest.instances.filter { it.kind == "Rover" }
    val childrenOf: Map<String, List<Instance>> = manifest.instances.filter { it.parent != null }.groupBy { it.parent!! }
}
