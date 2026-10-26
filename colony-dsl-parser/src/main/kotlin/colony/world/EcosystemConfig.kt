package colony.world

import kotlinx.serialization.Serializable

/** Finite first-version ecology services and stocks. Disabled by default for the legacy scenarios. */
@Serializable
data class EcosystemConfig(
    val enabled: Boolean = false,
    val zoneSize: Double = 140.0,
    val forest: ForestConfig = ForestConfig(),
    val predator: PredatorConfig = PredatorConfig(),
    val cleanup: CleanupConfig = CleanupConfig(),
    val plankton: PlanktonConfig = PlanktonConfig(),
    val humanFactors: HumanFactorsConfig = HumanFactorsConfig(),
    val crocodileManurePerSecond: Double = 0.15,
) {
    fun validate() {
        require(zoneSize > 0.0 && zoneSize.isFinite())
        require(crocodileManurePerSecond.isFinite() && crocodileManurePerSecond >= 0.0)
        forest.validate(); predator.validate(); cleanup.validate(); plankton.validate(); humanFactors.validate()
    }
}

@Serializable data class ForestConfig(
    val enabled: Boolean = false,
    val initialBiomassPerZone: Double = 100.0,
    val regrowthPerSecond: Double = 0.02,
    val clearingPerSecond: Double = 2.0,
) {
    fun validate() { require(listOf(initialBiomassPerZone, regrowthPerSecond, clearingPerSecond).all { it.isFinite() && it >= 0.0 }) }
}

@Serializable data class PredatorConfig(
    val enabled: Boolean = false,
    val mutations: List<String> = listOf("armored", "swift", "venomous"),
    val attackPeriodSeconds: Double = 600.0,
    val attackDurationSeconds: Double = 180.0,
) {
    fun validate() { require(mutations.isNotEmpty() && mutations.all { it in setOf("armored", "swift", "venomous", "pack") } &&
        attackPeriodSeconds.isFinite() && attackDurationSeconds.isFinite() && attackPeriodSeconds > 0.0 &&
        attackDurationSeconds in 0.0..attackPeriodSeconds) }
}

@Serializable data class CleanupConfig(
    val enabled: Boolean = false,
    val loadCapacity: Double = 600.0,
    val cleanPerSecond: Double = 2.0,
    val unloadAt: Point = Point(0.0, 0.0),
    val unloadRadius: Double = 12.0,
    val costPerUnit: Long = 2,
    val fuelPricePerUnit: Long = 100,
    val initialFuel: Double = 100000.0,
    val fuelPerUnit: Double = 0.04,
    val fuelPerMetre: Double = 0.1,
    val initialDepotFuel: Double = 0.0,
    val depotFuelCapacity: Double = 100000.0,
    val fuelRestockPerSecond: Double = 0.0,
) {
    fun validate() { require(loadCapacity > 0 && cleanPerSecond > 0 && unloadRadius > 0 && costPerUnit >= 0 && fuelPricePerUnit >= 0 && listOf(loadCapacity, cleanPerSecond, unloadRadius, initialFuel, fuelPerUnit, fuelPerMetre, initialDepotFuel, depotFuelCapacity, fuelRestockPerSecond).all { it.isFinite() && it >= 0.0 } && initialDepotFuel <= depotFuelCapacity && unloadAt.x.isFinite() && unloadAt.y.isFinite()) }
}

@Serializable data class PlanktonConfig(
    val enabled: Boolean = false,
    val coastalWidth: Double = 120.0,
    val wavePeriodSeconds: Double = 3600.0,
    val initialBiomassPerCoastalZone: Double = 500.0,
    val regrowthPerSecond: Double = 0.05,
    val damagePerSecond: Double = 0.04,
    val burningPerSecond: Double = 5.0,
    val burnFuelPerBiomass: Double = 0.2,
    val burnPowerPerSecond: Double = 4000.0,
    val initialBurnFuel: Double = 10000.0,
    val fuelCostPerUnit: Long = 3,
    val fuelCapacity: Double = 10000.0,
    val fuelRestockPerSecond: Double = 0.0,
    val burnerCount: Int = 1,
    val burnerSpacing: Double = 140.0,
) {
    fun validate() { require(fuelCostPerUnit >= 0 && burnerCount in 1..64 && listOf(coastalWidth, wavePeriodSeconds, initialBiomassPerCoastalZone, regrowthPerSecond, damagePerSecond, burningPerSecond, burnFuelPerBiomass, burnPowerPerSecond, initialBurnFuel, fuelCapacity, fuelRestockPerSecond, burnerSpacing).all { it.isFinite() && it >= 0.0 } && initialBurnFuel <= fuelCapacity && burnerSpacing > 0 && coastalWidth > 0 && wavePeriodSeconds > 0) }
}

@Serializable data class HumanFactorsConfig(
    val enabled: Boolean = false,
    val availabilityCycleSeconds: Double = 1800.0,
    val unavailableSeconds: Double = 120.0,
    val boredomSeconds: Double = 180.0,
    val vandalPeriodSeconds: Double = 2400.0,
    val vandalDamage: Double = 5.0,
    val vandalChancePercent: Int = 8,
    val vandalRadius: Double = 12.0,
    val alcoholPeriodSeconds: Double = 3600.0,
    val alcoholDurationSeconds: Double = 180.0,
) {
    fun validate() { require(vandalChancePercent in 0..100 && listOf(availabilityCycleSeconds, unavailableSeconds, boredomSeconds, vandalPeriodSeconds, vandalDamage, vandalRadius, alcoholPeriodSeconds, alcoholDurationSeconds).all { it.isFinite() && it >= 0.0 } && availabilityCycleSeconds > 0 && unavailableSeconds <= availabilityCycleSeconds && boredomSeconds <= availabilityCycleSeconds && vandalPeriodSeconds > 0 && alcoholPeriodSeconds > 0 && alcoholDurationSeconds <= alcoholPeriodSeconds) }
}
