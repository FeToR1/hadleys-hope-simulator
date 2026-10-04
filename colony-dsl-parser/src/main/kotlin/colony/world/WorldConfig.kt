package colony.world

import kotlinx.serialization.Serializable

private fun requireFinite(vararg values: Double) = require(values.all(Double::isFinite)) { "World parameters must be finite" }

/**
 * Parameters of the physical models (docs/technical-reference.md#world). Every value is in SI units: watts,
 * seconds, metres, joules, degrees Celsius, cubic metres, hit points, and money in minimal units.
 * The defaults describe the settlement of the lab; a scenario overrides what it needs.
 */
@Serializable
data class SeaConfig(
    val enabled: Boolean = false,
    val coastalZoneWidth: Double = 120.0,
    val erosionDamageRate: Double = 1.2, // HP per second for coastal buildings
    val fogPeriodSeconds: Double = 180.0, // Fog cycle period
    val fogDurationSeconds: Double = 75.0,  // How long fog stays active
    val fogMaxPenetration: Double = 350.0, // How deep fog penetrates inland from coast
    val fogAdvanceSpeed: Double = 8.0,     // Metres per second fog front moves inland
    val fogSuffocationDamageRate: Double = 4.0, // Damage per second for humans in fog without respirator
    val cloudWidth: Double = 1200.0,       // Width of localized fog bank (meters, ~100 houses)
    val cloudHeight: Double = 1400.0,      // Height of localized fog bank (meters, ~100 houses)
    val initialRespirators: Int = 0,
    val respiratorRestockPerSecond: Double = 0.0
) {
    fun validate() {
        requireFinite(coastalZoneWidth, erosionDamageRate, fogPeriodSeconds, fogDurationSeconds, fogMaxPenetration,
            fogAdvanceSpeed, fogSuffocationDamageRate, cloudWidth, cloudHeight, respiratorRestockPerSecond)
        require(coastalZoneWidth >= 0.0 && erosionDamageRate >= 0.0 && fogPeriodSeconds > 0.0 &&
            fogDurationSeconds in 0.0..fogPeriodSeconds && fogMaxPenetration >= 0.0 && fogAdvanceSpeed >= 0.0 &&
            fogSuffocationDamageRate >= 0.0 && cloudWidth > 0.0 && cloudHeight > 0.0 &&
            initialRespirators >= 0 && respiratorRestockPerSecond >= 0.0) { "Bad sea config" }
    }
}

@Serializable
data class DefenseConfig(
    val enabled: Boolean = false,
    val initialAmmoPerUnit: Int = 0,
    val powerPerUnit: Double = 0.0,
    val ammoCostPerRound: Long = 150_000L,
    /** Ammunition units restored per second, capped at initialAmmoPerUnit. Cost is charged when fired. */
    val ammoRestockPerSecond: Double = 0.0,
) {
    fun validate() {
        requireFinite(powerPerUnit, ammoRestockPerSecond)
        require(powerPerUnit >= 0.0 && initialAmmoPerUnit >= 0 && ammoCostPerRound >= 0 && ammoRestockPerSecond >= 0.0) { "Bad defense config" }
    }
}

/** Optional scenario coordinates and perimeter openings. Null locations preserve generated legacy geometry. */
@Serializable
data class GeometryConfig(
    val coastX: Double? = null,
    val mine: Point? = null,
    val depository: Point? = null,
    val medicalCenter: Point? = null,
    /** Centers of openings on the generated perimeter. */
    val gates: List<Point> = emptyList(),
    val gateWidth: Double = 12.0,
) {
    fun validate() {
        coastX?.let { require(it.isFinite()) { "coastX must be finite" } }
        listOfNotNull(mine, depository, medicalCenter).forEach { require(it.x.isFinite() && it.y.isFinite()) { "Geometry points must be finite" } }
        require(gateWidth.isFinite() && gateWidth > 0.0) { "gateWidth must be positive and finite" }
        require(gates.all { it.x.isFinite() && it.y.isFinite() }) { "Gate points must be finite" }
        require(gates.distinct().size == gates.size) { "Gate points must be unique" }
    }
}

@Serializable
data class CreatineEconomyConfig(
    val enabled: Boolean = false,
    val pricePerUnit: Long = 1_200L,        // Minimal money units earned per sold creatine unit
    val sellFraction: Double = 0.45,        // Fraction sold for settlement revenue (rest stored for healing)
    val healCost: Double = 2.0,             // Units of creatine needed to fully heal a human
    val lowHealthThreshold: Double = 50.0,  // Health below which humans seek medical treatment
    val yieldPerWorkerHour: Double = 4.0,   // Base yield of creatine per hour of miner work
    val initialDepotStock: Double = 50.0,
    val initialMedicalStock: Double = 50.0
) {
    fun validate() {
        requireFinite(pricePerUnit.toDouble(), sellFraction, healCost, lowHealthThreshold, yieldPerWorkerHour, initialDepotStock, initialMedicalStock)
        require(pricePerUnit >= 0 && sellFraction.isFinite() && sellFraction in 0.0..1.0 && healCost.isFinite() &&
            healCost >= 0.0 && lowHealthThreshold.isFinite() && lowHealthThreshold in 0.0..100.0 &&
            yieldPerWorkerHour.isFinite() && yieldPerWorkerHour >= 0.0 && initialDepotStock.isFinite() &&
            initialDepotStock >= 0.0 && initialMedicalStock.isFinite() && initialMedicalStock >= 0.0) { "Bad creatine config" }
    }
}

@Serializable
data class WorldConfig(
    val version: Int = 1,
    val climate: Climate = Climate(),
    val power: PowerConfig = PowerConfig(),
    val water: WaterConfig = WaterConfig(),
    val house: HouseConfig = HouseConfig(),
    val human: HumanConfig = HumanConfig(),
    val marine: MarineConfig = MarineConfig(),
    val transport: TransportConfig = TransportConfig(),
    val repair: RepairConfig = RepairConfig(),
    val tariffs: Tariffs = Tariffs(),
    val sight: SightConfig = SightConfig(),
    val fence: FenceConfig = FenceConfig(),
    val sea: SeaConfig = SeaConfig(),
    val creatine: CreatineEconomyConfig = CreatineEconomyConfig(),
    val defense: DefenseConfig = DefenseConfig(),
    val ecosystem: EcosystemConfig = EcosystemConfig(),
    val geometry: GeometryConfig = GeometryConfig(),
) {
    fun validate() {
        require(version == 1) { "Unsupported world version $version" }
        climate.validate(); power.validate(); water.validate(); house.validate()
        human.validate(); marine.validate(); transport.validate(); repair.validate(); tariffs.validate(); sight.validate(); fence.validate()
        sea.validate(); creatine.validate(); defense.validate(); ecosystem.validate(); geometry.validate()
    }
}

/** One climate for the whole settlement; the profile is a function of model time, not a weather model. */
@Serializable
data class Climate(
    /** Mean outside temperature in degrees Celsius. */
    val meanTemperature: Double = -30.0,
    /** Half the swing between the coldest and the warmest hour. */
    val amplitude: Double = 10.0,
    val dayLengthSeconds: Double = 86_400.0,
    val sunriseSeconds: Double = 21_600.0,
    val sunsetSeconds: Double = 64_800.0,
    /** Fraction of peak solar power that gets through the clouds. */
    val weatherFactor: Double = 0.8,
) {
    fun validate() {
        requireFinite(meanTemperature, amplitude, dayLengthSeconds, sunriseSeconds, sunsetSeconds, weatherFactor)
        require(dayLengthSeconds > 0 && amplitude >= 0) { "Bad climate profile" }
        require(sunriseSeconds in 0.0..dayLengthSeconds && sunsetSeconds in sunriseSeconds..dayLengthSeconds) { "Bad daylight hours" }
        require(weatherFactor in 0.0..1.0) { "Weather factor is a fraction" }
    }

    /** Coldest before sunrise, warmest in the early afternoon. */
    fun outsideTemperature(seconds: Double): Double {
        val phase = (seconds % dayLengthSeconds) / dayLengthSeconds
        return meanTemperature - amplitude * Math.cos(2 * Math.PI * (phase - 0.6))
    }

    /** Zero at night, a half sine over the daylight hours. */
    fun solarFraction(seconds: Double): Double {
        val time = seconds % dayLengthSeconds
        if (time < sunriseSeconds || time > sunsetSeconds) return 0.0
        return Math.sin(Math.PI * (time - sunriseSeconds) / (sunsetSeconds - sunriseSeconds)) * weatherFactor
    }
}

@Serializable
data class PowerConfig(
    val reactorPower: Double = 2_000_000.0,
    val solarPeak: Double = 600_000.0,
    /** Energy the uninterruptible supply holds, in joules. */
    val upsCapacity: Double = 1.8e9,
    /** Charge the battery starts the run with, in joules; a scenario drains or empties it to test blackouts. */
    val upsInitialCharge: Double = upsCapacity,
    val upsMaxPower: Double = 300_000.0,
    val upsEfficiency: Double = 0.95,
    val housesPerPole: Int = 20,
    val poleHealth: Double = 100.0,
    /** Lower class is served first; the class that runs out of power is cut proportionally. */
    val priorities: Map<String, Int> = mapOf("Pump" to 0, "Ups" to 0, "Heater" to 1, "Kettle" to 2),
) {
    fun validate() {
        requireFinite(reactorPower, solarPeak, upsCapacity, upsInitialCharge, upsMaxPower, upsEfficiency, poleHealth)
        require(reactorPower >= 0 && solarPeak >= 0 && upsCapacity >= 0 && upsMaxPower >= 0) { "Power cannot be negative" }
        require(upsInitialCharge in 0.0..upsCapacity) { "The initial battery charge must fit the capacity" }
        require(upsEfficiency in 0.1..1.0) { "Efficiency is a fraction" }
        require(housesPerPole >= 1 && poleHealth > 0) { "Bad grid layout" }
    }

    fun priorityOf(kind: String): Int = priorities[kind] ?: 9
}

@Serializable
data class WaterConfig(
    val pumpPower: Double = 40_000.0,
    /** Degree seconds of frost an indoor pipe survives (docs: order 5400 K*s). */
    val freezeThresholdIndoor: Double = 5_400.0,
    /** Cubic metres per second a lived-in house draws. */
    val houseDemand: Double = 6.0e-6,
) {
    fun validate() {
        requireFinite(pumpPower, freezeThresholdIndoor, houseDemand)
        require(pumpPower >= 0 && freezeThresholdIndoor > 0 && houseDemand >= 0) { "Bad water parameters" }
    }
}

@Serializable
data class HouseConfig(
    /** Heat loss through walls, windows and doors, in watts per kelvin. */
    val conductance: Double = 200.0,
    /** Effective heat capacity of the house, in joules per kelvin. */
    val capacity: Double = 1.0e7,
    val heaterEfficiency: Double = 1.0,
    val initialTemperature: Double = 20.0,
    /** A house below this is cold, which is what a resident feels. */
    val coldThreshold: Double = 10.0,
    /** Litres of water a kettle holds. */
    val kettleVolume: Double = 1.7,
    val kettleHeatLoss: Double = 3.0,
) {
    fun validate() {
        requireFinite(conductance, capacity, heaterEfficiency, initialTemperature, coldThreshold, kettleVolume, kettleHeatLoss)
        require(conductance > 0 && capacity > 0 && kettleVolume > 0) { "Bad thermal parameters" }
        require(heaterEfficiency in 0.0..1.0) { "Efficiency is a fraction" }
    }

    /** Joules per kelvin of the water in a kettle. */
    val kettleCapacity: Double get() = kettleVolume * 4186.0
}

@Serializable
data class HumanConfig(
    /** Local time at the start of the residents' daily routine, in minutes after midnight. */
    val startMinute: Int = 480,
    /** A resident starts losing health below this temperature. Comfortable Earth atmosphere. */
    val harmThreshold: Double = 5.0,
    /** Hit points per kelvin per hour of exposure. */
    val harmPerKelvinHour: Double = 0.5,
    val vandalRadius: Double = 12.0,
) {
    fun validate() {
        requireFinite(harmThreshold, harmPerKelvinHour, vandalRadius)
        require(startMinute in 0..1439) { "Human startMinute must be in 0..1439" }
        require(harmPerKelvinHour >= 0 && vandalRadius >= 0) { "Bad exposure parameters" }
    }

    val harmPerKelvinSecond: Double get() = harmPerKelvinHour / 3600.0
}

@Serializable
data class MarineConfig(
    /** Initial response delay in seconds; the manifest's xenomorphs are present from time zero. */
    val responseDelaySeconds: Double = 300.0,
    /** Marines must gather within this radius of the selected xenomorph before the leader attacks. */
    val assaultRadius: Double = 4.0,
    /** A squad is expected to contain four or five living members. */
    val minSquadSize: Int = 4,
    val maxSquadSize: Int = 5,
    /** How long a xenomorph that a squad drove off keeps away once it is out of the settlement. */
    val routSeconds: Double = 600.0,
) {
    fun validate() {
        requireFinite(responseDelaySeconds, assaultRadius, routSeconds)
        require(responseDelaySeconds >= 0.0) { "Marine response delay cannot be negative" }
        require(assaultRadius > 0.0) { "Marine assault radius must be positive" }
        require(routSeconds >= 0.0) { "Rout time cannot be negative" }
        require(minSquadSize in 1..maxSquadSize) { "Bad marine squad size limits" }
        require(maxSquadSize <= 32) { "Marine squad is too large" }
    }
}

@Serializable
data class TransportConfig(
    /** Maximum distance at which a person can board a rover. */
    val boardRadius: Double = 6.0,
    /** Maximum number of passengers a rover can carry in the simulation. */
    val passengerCapacity: Int = 5,
    /** How far a resident is willing to walk to a rover standing at its depot. */
    val walkRadius: Double = 120.0,
    /** Longest time a rover with residents aboard waits for the others who are walking to it. */
    val boardingWaitSeconds: Double = 90.0,
) {
    fun validate() {
        requireFinite(boardRadius, walkRadius, boardingWaitSeconds)
        require(boardRadius > 0.0) { "Transport board radius must be positive" }
        require(passengerCapacity in 1..32) { "Bad transport passenger capacity" }
        require(walkRadius >= 0.0 && boardingWaitSeconds >= 0.0) { "Bad boarding parameters" }
    }
}

@Serializable
data class RepairConfig(
    /** Seconds of work a repair of each kind of object takes. */
    val duration: Map<String, Double> = mapOf("pole" to 300.0, "pipe" to 600.0, "device" to 180.0),
    /** Parts, in minimal money units. */
    val parts: Map<String, Long> = mapOf("pole" to 120_000, "pipe" to 80_000, "device" to 40_000),
    val hourlyRate: Long = 150_000,
    /** How close a crew has to be to work on an object. */
    val workRadius: Double = 6.0,
    /** Delay between an object breaking and the repair job becoming dispatchable to a rover. */
    val dispatchDelaySeconds: Double = 120.0,
    val materials: Int = 24,
    /** How fast a crew drives, in metres per second. */
    val roverSpeed: Double = 9.0,
    val initialDepotMaterials: Int = 0,
    val depotRestockPerSecond: Double = 0.0,
    val materialCost: Long = 40_000L,
) {
    fun validate() {
        requireFinite(workRadius, dispatchDelaySeconds, roverSpeed, depotRestockPerSecond)
        require(duration.values.all { it > 0 } && parts.values.all { it >= 0 }) { "Bad repair parameters" }
        require(hourlyRate >= 0 && workRadius > 0 && dispatchDelaySeconds >= 0 && materials >= 0 && roverSpeed > 0 &&
            initialDepotMaterials >= 0 && depotRestockPerSecond.isFinite() && depotRestockPerSecond >= 0.0 && materialCost >= 0) { "Bad crew parameters" }
    }

    fun durationOf(kind: String): Double = duration[kind] ?: 300.0
    fun partsOf(kind: String): Long = parts[kind] ?: 0L
}

@Serializable
data class Tariffs(
    /** Minimal money units per kilowatt hour and per cubic metre. */
    val electricityPerKwh: Long = 900,
    val waterPerCubicMetre: Long = 4_500,
    /** Seconds between postings; the remainder of the rounding is carried to the next one. */
    val billingIntervalSeconds: Long = 3_600,
    val monthSeconds: Long = 30L * 86_400L,
) {
    fun validate() {
        require(electricityPerKwh >= 0 && waterPerCubicMetre >= 0) { "Tariffs cannot be negative" }
        require(billingIntervalSeconds > 0 && monthSeconds > 0) { "Bad billing calendar" }
    }
}

@Serializable
data class SightConfig(
    /** How far marines detect xenomorphs and how far the other observation lists reach. */
    val sightRadius: Double = 260.0,
    /** Most objects an observation list may hold. */
    val listLimit: Int = 16,
    val attackRadius: Double = 3.0,
    /** How far a xenomorph sees infrastructure and people; a short sight spreads the hunters over the map. */
    val xenomorphRadius: Double = 260.0,
) {
    fun validate() {
        requireFinite(sightRadius, attackRadius, xenomorphRadius)
        require(sightRadius >= 0 && listLimit in 1..4096 && attackRadius >= 0 && xenomorphRadius >= 0) { "Bad observation limits" }
    }
}

/**
 * A perimeter fence around everything the colony uses. It stops xenomorphs only: a closed segment has to be
 * broken before one can pass, and a broken segment becomes a repair job like a pole.
 */
@Serializable
data class FenceConfig(
    val enabled: Boolean = false,
    /** Distance between the fence and the outermost object it encloses. */
    val margin: Double = 40.0,
    val segmentLength: Double = 60.0,
    val health: Double = 400.0,
    /** How far outside the fence the xenomorphs roam and where they appear. */
    val roamingDistance: Double = 60.0,
) {
    fun validate() {
        requireFinite(margin, segmentLength, health, roamingDistance)
        require(margin >= 0 && segmentLength > 0 && health > 0 && roamingDistance > 0) { "Bad fence parameters" }
    }
}
