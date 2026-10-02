package colony.world

import colony.runtime.Instance
import colony.runtime.ReferenceRun
import colony.runtime.prepareScenario
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class LargeScaleSettlementTest {

    @Test
    fun testSpatialIndexPerformanceWith5000Houses() {
        val index = SpatialIndex(cellSize = 35.0)
        // Insert 5000 houses in a 100 x 50 grid
        val ids = mutableListOf<String>()
        for (i in 0 until 5000) {
            val id = "house-$i"
            ids.add(id)
            val x = (i % 100) * 35.0
            val y = (i / 100) * 35.0
            index.update(id, Point(x, y))
        }

        // Measure radius query time: should take < 1 ms for O(1) query
        val start = System.nanoTime()
        val neighbors = index.queryRadius(Point(1750.0, 875.0), 50.0)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue(neighbors.isNotEmpty(), "Neighbors around center should be found")
        assertTrue(elapsedMs < 50.0, "Spatial hash query should be near-instant ($elapsedMs ms)")

        // Moving an entity updates the index correctly
        index.update("house-0", Point(5000.0, 5000.0))
        val oldCenter = index.queryRadius(Point(0.0, 0.0), 10.0)
        assertFalse(oldCenter.contains("house-0"), "house-0 should no longer be at old position")
        val newCenter = index.queryRadius(Point(5000.0, 5000.0), 10.0)
        assertTrue(newCenter.contains("house-0"), "house-0 should be found at new position")
    }

    @Test
    fun testFull5000ScenarioManifestAndExecution() {
        val scenarioPath = Path.of("../examples/physics/settlement-5000.json")
        val prepared = prepareScenario(scenarioPath)

        val houses = prepared.manifest.instances.filter { it.kind == "House" }
        assertEquals(5000, houses.size, "Manifest must contain exactly 5000 houses")

        val run = ReferenceRun(prepared, "scale-5000-test")
        val kernel = assertNotNull(run.kernel)

        val airDefenseUnits = kernel.topology.fixtures.filter { it.kind == FixtureKind.AIR_DEFENSE }
        assertTrue(airDefenseUnits.size >= 8, "Perimeter and roof air defense units should be present")

        val depository = kernel.topology.fixtures.find { it.kind == FixtureKind.DEPOSITORY }
        assertNotNull(depository, "Creatine depository must exist")

        val medicalCenter = kernel.topology.fixtures.find { it.kind == FixtureKind.MEDICAL_CENTER }
        assertNotNull(medicalCenter, "Medical center must exist")

        assertTrue(kernel.airDefenseUnits.isNotEmpty(), "Kernel air defense units must be active")

        // Run 20 simulation ticks to verify stability, sea, ADS, and creatine systems under 5000 houses
        val startTime = System.currentTimeMillis()
        run.use { active ->
            for (step in 0 until 20) {
                val batch = active.step()
                assertEquals(step.toLong(), batch.tickId)
                assertTrue(batch.entities.size >= 5000, "All entities should be simulated")
            }
        }
        val durationMs = System.currentTimeMillis() - startTime
        println("Simulated 20 ticks with 5000 houses in ${durationMs}ms")
        assertTrue(durationMs < 60000, "Simulation with 5000 houses should run smoothly")
    }

    @Test
    fun testSeaControllerErosionAndFogMechanics() {
        val seaConfig = SeaConfig(
            enabled = true,
            coastalZoneWidth = 100.0,
            erosionDamageRate = 0.5,
            fogPeriodSeconds = 10.0,
            fogDurationSeconds = 5.0,
            fogMaxPenetration = 200.0,
            fogAdvanceSpeed = 20.0
        )
        val controller = SeaController(seaCoastX = 1000.0, config = seaConfig)

        val coastalHouse = Point(950.0, 500.0) // 50m from sea boundary, inside 100m band
        val inlandHouse = Point(800.0, 500.0)  // 200m from sea, outside band

        assertTrue(controller.isInCoastalZone(coastalHouse), "Coastal house should be in coastal zone")
        assertFalse(controller.isInCoastalZone(inlandHouse), "Inland house should not be in coastal zone")

        // Fog cycle test:
        // At t = 0..4s: fog is active in 10s period (duration 5s)
        val step1 = controller.step(elapsedSeconds = 1.0, dt = 1.0)
        assertTrue(step1.isFogActive, "Fog should be active within duration window")
        assertTrue(controller.currentFogDepth > 0.0, "Fog should advance into settlement")

        // Point near coast inside fog penetration
        val pointInFog = Point(990.0, 500.0)
        assertTrue(controller.isInFog(pointInFog), "Point behind fog front is in fog")

        // Point far inland
        val pointInland = Point(100.0, 500.0)
        assertFalse(controller.isInFog(pointInland), "Far inland point is not in fog")

        // Advance to t = 7s (outside 5s duration): fog dissipates
        val step2 = controller.step(elapsedSeconds = 7.0, dt = 1.0)
        assertFalse(step2.isFogActive, "Fog should not be active outside duration")
    }

    @Test
    fun testFlyingCrocodilesAndAirDefenseInteraction() {
        val ads = AirDefenseUnit(
            id = "ads-1",
            position = Point(500.0, 500.0),
            range = 150.0,
            damagePerShot = 25.0
        )
        assertFalse(ads.broken, "ADS starts functional")
        assertTrue(ads.isOperational, "ADS is operational")

        // ADS disabled in sea fog
        ads.breakDown("sea_fog")
        assertTrue(ads.broken, "ADS should break in sea fog")
        assertFalse(ads.isOperational, "ADS is not operational when broken")

        // Repaired ADS
        ads.repair()
        assertFalse(ads.broken, "ADS should recover when repaired")
        assertTrue(ads.isOperational, "ADS is operational after repair")

        val pool = CrocodilePool(maxCapacity = 5)
        val croc = pool.obtain()
        assertNotNull(croc)
        assertFalse(croc.active)

        croc.spawn(
            start = Point(0.0, 500.0),
            target = Point(1000.0, 500.0),
            speed = 20.0,
            health = 50.0,
            seed = 12345L
        )
        assertTrue(croc.active)

        // Step crocodile towards settlement
        croc.update(1.0)
        assertTrue(croc.position.x > 0.0, "Crocodile moved forward")

        // ADS engagement
        croc.position = Point(480.0, 500.0) // inside ADS range
        val hit = ads.tryEngage(croc)
        assertTrue(hit, "ADS should engage crocodile in range")
        assertTrue(croc.health < 50.0, "Crocodile takes damage from ADS")
        assertTrue(croc.isDeflected, "Crocodile trajectory was deflected by ADS")

        // Despawn to pool
        croc.despanw()
        assertFalse(croc.active)
        val recycled = pool.obtain()
        assertSame(croc, recycled, "Object pool should reuse inactive crocodile instance")
    }

    @Test
    fun testCreatineEconomyMiningStorageAndHealing() {
        val config = CreatineEconomyConfig(
            enabled = true,
            yieldPerWorkerHour = 10.0,
            pricePerUnit = 50L,
            sellFraction = 0.5,
            healCost = 5.0,
            lowHealthThreshold = 70.0
        )
        val manager = CreatineManager(config)
        val initialStock = manager.stock

        // Mine produces 20 units
        val depositResult = manager.deposit(20.0)
        assertEquals(20.0, depositResult.totalMined, "Deposit returned total mined")
        assertEquals(10.0, depositResult.unitsForSale, "Half for sale")
        assertEquals(10.0, depositResult.unitsForStock, "Half for stock")
        assertEquals(10 * 50L, depositResult.revenue, "Revenue calculated")

        assertEquals(initialStock + 10.0, manager.stock, "Stock should increase from mining deposit")
        assertEquals(500L, manager.pendingSalesRevenue, "Pending sales revenue tracked")

        // Flushes revenue for world ledger
        val flushed = manager.flushRevenue()
        assertEquals(500L, flushed)
        assertEquals(0L, manager.pendingSalesRevenue)

        // Healing a wounded worker
        val healed = manager.tryHeal(currentHealth = 40.0)
        assertTrue(healed, "Worker was healed using creatine")
        assertEquals(initialStock + 10.0 - 5.0, manager.stock, 0.001, "Stock deducted by healCost")

        // Patient with full health does not consume creatine
        val healthyNotHealed = manager.tryHeal(currentHealth = 100.0)
        assertFalse(healthyNotHealed, "Healthy worker doesn't consume creatine")
    }
}
