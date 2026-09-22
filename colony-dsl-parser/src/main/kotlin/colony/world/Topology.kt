package colony.world

import colony.runtime.Instance
import colony.runtime.RunManifest
import kotlinx.serialization.Serializable

/** A point in the settlement, in metres. */
@Serializable data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double = Math.hypot(x - other.x, y - other.y)
}

/** Kinds of object the world owns without giving them a program of their own. */
enum class FixtureKind(val repairKey: String) { REACTOR("device"), SOLAR("device"), UPS("device"), PUMP("device"), POLE("pole"), PIPE("pipe") }

/**
 * An object of the settlement that has no VM: a pole, a pipe, a source. It has a position, health and, for
 * the grid, the node it feeds from (docs/simulation/interaction-overview.md, section 1).
 */
@Serializable data class Fixture(val id: String, val kind: FixtureKind, val at: Point, val feedsFrom: String? = null, val serves: String? = null)

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
) {
    val byId: Map<String, Fixture> = fixtures.associateBy { it.id }
    val poles: List<Fixture> = fixtures.filter { it.kind == FixtureKind.POLE }
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
    // An appliance draws through the house it belongs to.
    for (instance in manifest.instances) {
        val parent = instance.parent ?: continue
        if (parent in powerFeed) powerFeed[instance.id] = powerFeed.getValue(parent)
    }
    return Topology(fixtures, powerFeed, waterPipe, houses, sources.map { it.id })
}

/** Instances by id, with the roles the kernel needs to find quickly. */
internal class Population(manifest: RunManifest) {
    val byId: Map<String, Instance> = manifest.instances.associateBy { it.id }
    val houses: List<Instance> = manifest.instances.filter { it.kind == "House" }
    val appliances: List<Instance> = manifest.instances.filter { it.kind == "Heater" || it.kind == "Kettle" }
    val residents: List<Instance> = manifest.instances.filter { it.kind == "Human" }
    val rovers: List<Instance> = manifest.instances.filter { it.kind == "Rover" }
    val childrenOf: Map<String, List<Instance>> = manifest.instances.filter { it.parent != null }.groupBy { it.parent!! }
}
