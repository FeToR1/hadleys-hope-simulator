package colony.world

import kotlinx.serialization.Serializable

/**
 * Parameters of the physical models (docs/simulation/calculations.md). Every value is in SI units: watts,
 * seconds, metres, joules, degrees Celsius, cubic metres, hit points, and money in minimal units.
 * The defaults describe the settlement of the lab; a scenario overrides what it needs.
 */
@Serializable
data class WorldConfig(
    val version: Int = 1,
    val climate: Climate = Climate(),
    val power: PowerConfig = PowerConfig(),
    val water: WaterConfig = WaterConfig(),
    val house: HouseConfig = HouseConfig(),
    val human: HumanConfig = HumanConfig(),
    val repair: RepairConfig = RepairConfig(),
    val tariffs: Tariffs = Tariffs(),
    val sight: SightConfig = SightConfig(),
) {
    fun validate() {
        require(version == 1) { "Unsupported world version $version" }
        climate.validate(); power.validate(); water.validate(); house.validate()
        human.validate(); repair.validate(); tariffs.validate(); sight.validate()
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
    val upsMaxPower: Double = 300_000.0,
    val upsEfficiency: Double = 0.95,
    val housesPerPole: Int = 20,
    val poleHealth: Double = 100.0,
    /** Lower class is served first; the class that runs out of power is cut proportionally. */
    val priorities: Map<String, Int> = mapOf("Pump" to 0, "Heater" to 1, "Kettle" to 2),
) {
    fun validate() {
        require(reactorPower >= 0 && solarPeak >= 0 && upsCapacity >= 0 && upsMaxPower >= 0) { "Power cannot be negative" }
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
        require(conductance > 0 && capacity > 0 && kettleVolume > 0) { "Bad thermal parameters" }
        require(heaterEfficiency in 0.0..1.0) { "Efficiency is a fraction" }
    }

    /** Joules per kelvin of the water in a kettle. */
    val kettleCapacity: Double get() = kettleVolume * 4186.0
}

@Serializable
data class HumanConfig(
    /** A resident starts losing health below this temperature. */
    val harmThreshold: Double = 5.0,
    /** Hit points per kelvin per hour of exposure. */
    val harmPerKelvinHour: Double = 0.5,
    val vandalRadius: Double = 12.0,
) {
    fun validate() { require(harmPerKelvinHour >= 0 && vandalRadius >= 0) { "Bad exposure parameters" } }

    val harmPerKelvinSecond: Double get() = harmPerKelvinHour / 3600.0
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
    val materials: Int = 24,
    /** How fast a crew drives, in metres per second. */
    val roverSpeed: Double = 9.0,
) {
    fun validate() {
        require(duration.values.all { it > 0 } && parts.values.all { it >= 0 }) { "Bad repair parameters" }
        require(hourlyRate >= 0 && workRadius > 0 && materials >= 0 && roverSpeed > 0) { "Bad crew parameters" }
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
    /** How far a xenomorph sees infrastructure and people. */
    val sightRadius: Double = 260.0,
    /** Most objects an observation list may hold. */
    val listLimit: Int = 16,
    val attackRadius: Double = 3.0,
) {
    fun validate() { require(sightRadius >= 0 && listLimit in 1..4096 && attackRadius >= 0) { "Bad observation limits" } }
}
