package colony.world

import colony.bytecode.Op
import colony.runtime.Instance
import colony.runtime.RunManifest
import colony.runtime.VmIntent
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class UpsPowerRegressionTest {
    private fun world(power: PowerConfig, heaterCount: Int = 0, pumpPower: Double = 40_000.0): WorldKernel {
        val empty = JsonObject(emptyMap())
        val instances = listOf(Instance("house", "House", "House", empty, empty, null, 0.0, 0.0)) +
            (1..heaterCount).map { Instance("heater-$it", "Heater", "Heater", empty, empty, "house", 0.0, 0.0) }
        val manifest = RunManifest(seed = 1, stepSeconds = "1", instances = instances)
        val config = WorldConfig(power = power, water = WaterConfig(pumpPower = pumpPower))
        return WorldKernel(manifest, config, buildTopology(manifest, config), 1.0)
    }

    private fun step(world: WorldKernel, tick: Int, vararg watts: Double): List<KernelEvent> {
        val intents = watts.mapIndexed { index, power ->
            VmIntent("heater-${index + 1}", Op.POWER_REQUEST, listOf(JsonPrimitive(power)))
        }
        return world.step(tick.toLong(), intents, intents.map { "${it.source}@$tick" }, intents.mapTo(HashSet()) { it.source })
    }

    @Test fun isolatedBatteryFeedsOnlyRealLoadsAndInvalidatesTheNetworkWhenEmpty() {
        val world = world(PowerConfig(reactorPower = 0.0, solarPeak = 0.0, upsCapacity = 1_000_000.0,
            upsInitialCharge = 500_000.0, upsMaxPower = 300_000.0, upsEfficiency = 1.0))
        repeat(12) { tick ->
            assertTrue(step(world, tick).none { it.type == "PowerLost" })
            assertEquals(40_000.0, world.grantedOf("water/pump"), 1e-9)
            assertEquals(0.0, world.grantedOf("grid/ups"))
        }
        step(world, 12)
        assertEquals(20_000.0, world.grantedOf("water/pump"), 1e-9)
        assertTrue(world.isPowered("house"), "phase 3 precedes this step's final discharge")
        val lost = step(world, 13)
        assertFalse(world.isPowered("house"), "cached connectivity must refresh after the UPS empties")
        assertTrue(lost.any { it.type == "PowerLost" && it.entityId == "house" })
        assertTrue(step(world, 14).none { it.type == "PowerLost" }, "loss is reported once")
    }

    @Test fun externalPowerAndBatterySupplyProportionalRealLoadsWithoutCharging() {
        val world = world(PowerConfig(reactorPower = 100_000.0, solarPeak = 0.0, upsCapacity = 1_000_000.0,
            upsInitialCharge = 100_000.0, upsMaxPower = 100_000.0, upsEfficiency = 0.5), heaterCount = 2)
        step(world, 0, 200_000.0, 100_000.0)
        assertEquals(40_000.0, world.grantedOf("water/pump"), 1e-9)
        assertEquals(110_000.0 * 2 / 3, world.grantedOf("heater-1"), 1e-9)
        assertEquals(110_000.0 / 3, world.grantedOf("heater-2"), 1e-9)
        assertEquals(0.0, world.grantedOf("grid/ups"))
        step(world, 1, 200_000.0, 100_000.0)
        assertEquals(100_000.0 * 40_000 / 140_000, world.grantedOf("water/pump"), 1e-9)
        assertEquals(0.0, world.grantedOf("heater-1"), "an empty UPS charges ahead of heating")
        assertEquals(100_000.0 * 100_000 / 140_000, world.grantedOf("grid/ups"), 1e-9)
    }

    @Test fun chargingUsesExternalPowerEvenWhenTheBatteryAlreadyHasCharge() {
        val world = world(PowerConfig(reactorPower = 100_000.0, solarPeak = 0.0, upsCapacity = 1_000_000.0,
            upsInitialCharge = 500_000.0, upsMaxPower = 300_000.0, upsEfficiency = 1.0))
        step(world, 0)
        assertEquals(100_000.0 * 40_000 / 340_000, world.grantedOf("water/pump"), 1e-9)
        assertEquals(100_000.0 * 300_000 / 340_000, world.grantedOf("grid/ups"), 1e-9)
    }

    @Test fun remainingCapacityLimitsTheChargeInputByItsEfficiency() {
        val world = world(PowerConfig(reactorPower = 1_000.0, solarPeak = 0.0, upsCapacity = 1_000.0,
            upsInitialCharge = 900.0, upsMaxPower = 1_000.0, upsEfficiency = 0.5), pumpPower = 0.0)
        step(world, 0)
        assertEquals(200.0, world.grantedOf("grid/ups"), 1e-9)
        step(world, 1)
        assertEquals(0.0, world.grantedOf("grid/ups"), "100J of headroom is filled by 200W for 1s at 50% efficiency")
    }

    @Test fun omittedInitialChargeDefaultsToTheConfiguredCapacity() {
        for (capacity in listOf(0.0, 1000.0, 2.0e9)) {
            PowerConfig(upsCapacity = capacity).validate()
            val decoded = Json.decodeFromString<PowerConfig>("""{"upsCapacity":$capacity}""")
            decoded.validate()
            assertEquals(capacity, decoded.upsInitialCharge)
        }
        assertFailsWith<IllegalArgumentException> { PowerConfig(upsCapacity = 100.0, upsInitialCharge = 101.0).validate() }
    }

    @Test fun fullScenariosKeepChargingInPriorityClassZero() {
        for (name in listOf("full.json", "full-5000.json")) {
            val scenario = Json.parseToJsonElement(Files.readString(Path.of("../examples/physics/$name"))).jsonObject
            val config = Json.decodeFromJsonElement<WorldConfig>(scenario.getValue("world"))
            assertEquals(0, config.power.priorityOf("Ups"), name)
        }
    }
}
