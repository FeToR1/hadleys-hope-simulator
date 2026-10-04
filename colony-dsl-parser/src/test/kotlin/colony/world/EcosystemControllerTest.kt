package colony.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EcosystemControllerTest {
    private val bounds = Box(0.0, 0.0, 40.0, 40.0)

    @Test fun `forest cells are bounded cleared only by a crew at the edge and regrow`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0,
            forest = ForestConfig(enabled = true, initialBiomassPerZone = 2.0, regrowthPerSecond = 1.0, clearingPerSecond = 2.0))
        val controller = EcosystemController(config, 7).also { it.configureBounds(bounds, 40.0) }
        val forest = controller.step(0.0, 1.0, emptyList()).zones.first { it.forestBiomass > 0 }
        val edge = Point(forest.bounds.minX.coerceIn(bounds.minX, bounds.maxX), forest.bounds.minY.coerceIn(bounds.minY, bounds.maxY))
        assertTrue(controller.clearForest(forest.id, "forester", Point(1000.0, 1000.0), 1.0) == null)
        assertEquals(0.0, controller.clearForest(forest.id, "forester", edge, 1.0)?.fields?.get("biomass")?.toDouble())
        val grown = controller.step(1.0, 1.0, emptyList()).zones.first { it.id == forest.id }
        assertEquals(1.0, grown.forestBiomass)
    }

    @Test fun `cleanup service moves to the nearest dirty zone then unloads only at depot`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0, crocodileManurePerSecond = 1.0,
            cleanup = CleanupConfig(enabled = true, loadCapacity = 2.0, cleanPerSecond = 2.0,
                unloadAt = Point(35.0, 35.0), unloadRadius = 2.0, initialFuel = 100.0, fuelPerUnit = .1, fuelPerMetre = .01))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val croc = EcosystemEntity("croc", "crocodile", Point(5.0, 5.0))
        val cleaner = EcosystemEntity("cleaner", "cleanup-rover", Point(35.0, 35.0))
        val first = controller.step(0.0, 1.0, listOf(croc, cleaner))
        val assignment = first.serviceTargets.single { it.actorId == "cleaner" }
        assertEquals("clean-manure", assignment.task)
        assertEquals(1.0, first.zones.first { it.id == "0:0" }.manure)
        val working = controller.step(1.0, 1.0, listOf(EcosystemEntity("cleaner", "cleanup-rover", assignment.target)))
        assertTrue(working.events.any { it.type == "ManureCleaned" })
        val routeHome = controller.step(2.0, 1.0, listOf(EcosystemEntity("cleaner", "cleanup-rover", assignment.target)))
        assertEquals("unload-manure", routeHome.serviceTargets.single { it.actorId == "cleaner" }.task)
        val unloaded = controller.step(3.0, 1.0, listOf(EcosystemEntity("cleaner", "cleanup-rover", config.cleanup.unloadAt)))
        assertTrue(unloaded.events.any { it.type == "ManureUnloaded" })
    }

    @Test fun `cleanup routes to accessible street manure when forest cells are also dirty`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0, crocodileManurePerSecond = 1.0,
            forest = ForestConfig(enabled = true), cleanup = CleanupConfig(enabled = true))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val forest = controller.step(0.0, 1.0, emptyList()).zones.filter { it.forestBiomass > 0.0 }
            .minBy { Point((it.bounds.minX + it.bounds.maxX) / 2.0, (it.bounds.minY + it.bounds.maxY) / 2.0).distanceTo(Point(35.0, 35.0)) }
        val forestPoint = Point((forest.bounds.minX + forest.bounds.maxX) / 2.0, (forest.bounds.minY + forest.bounds.maxY) / 2.0)
        val result = controller.step(1.0, 1.0, listOf(
            EcosystemEntity("forest-croc", "crocodile", forestPoint),
            EcosystemEntity("street-croc", "crocodile", Point(5.0, 5.0)),
            EcosystemEntity("cleaner", "cleanup-rover", Point(35.0, 35.0)),
        ))
        val target = result.serviceTargets.single { it.actorId == "cleaner" }
        assertEquals(Point(5.0, 5.0), target.target)
        assertTrue(result.zones.any { it.id == forest.id && it.manure > 0.0 })
    }

    @Test fun `plankton burning requires a real per installation power grant and spends finite fuel`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0, plankton = PlanktonConfig(enabled = true,
            coastalWidth = 10.0, initialBiomassPerCoastalZone = 10.0, wavePeriodSeconds = 100.0,
            damagePerSecond = 1.0, burningPerSecond = 2.0, burnFuelPerBiomass = 1.0,
            burnPowerPerSecond = 100.0, initialBurnFuel = 1.0))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 20.0) }
        val burner = EcosystemEntity("burner", "burner", Point(15.0, 5.0))
        val noGrant = controller.step(0.0, 1.0, listOf(burner), mapOf("other" to 100.0))
        assertTrue(noGrant.events.none { it.type == "PlanktonBurned" })
        val deadBurner = controller.step(0.5, 1.0, listOf(burner.copy(health = 0.0)), mapOf("burner" to 100.0))
        assertTrue(deadBurner.events.none { it.type == "PlanktonBurned" })
        val granted = controller.step(1.0, 1.0, listOf(burner), mapOf("burner" to 100.0))
        assertTrue(granted.events.any { it.type == "PlanktonBurned" })
        assertEquals(9.0, granted.zones.first { it.id == "1:0" }.planktonBiomass)
        assertEquals("burn", granted.postings.single().kind)
        val burnerAttack = controller.step(2.0, 1.0, listOf(burner))
        assertEquals("burner", burnerAttack.damages.single().targetId)
        val turretAttack = controller.step(3.0, 1.0, listOf(EcosystemEntity("turret", "ground_turret", Point(15.0, 5.0))))
        assertEquals("turret", turretAttack.damages.single().targetId)
    }

    @Test fun `mutations and human availability are stable for the same seed and inputs`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0,
            predator = PredatorConfig(enabled = true),
            humanFactors = HumanFactorsConfig(enabled = true, availabilityCycleSeconds = 100.0,
                unavailableSeconds = 100.0, boredomSeconds = 100.0, alcoholPeriodSeconds = 100.0,
                alcoholDurationSeconds = 100.0))
        val one = EcosystemController(config, 426).also { it.configureBounds(bounds, 20.0) }
        val two = EcosystemController(config, 426).also { it.configureBounds(bounds, 20.0) }
        val entities = listOf(EcosystemEntity("alien", "xenomorph", Point(15.0, 5.0)), EcosystemEntity("resident", "civilian", Point(15.0, 5.0)))
        assertEquals(one.mutationOf("alien", 1200.0), two.mutationOf("alien", 1200.0))
        val resultA = one.step(0.0, 1.0, entities)
        val resultB = two.step(0.0, 1.0, entities)
        assertEquals(resultA.unavailableHumans, resultB.unavailableHumans)
        assertTrue("resident" in resultA.unavailableHumans)
        assertEquals(resultA.events, resultB.events)
    }

    @Test fun `negative stable predator hash still produces a periodic attack window`() {
        val config = EcosystemConfig(enabled = true, predator = PredatorConfig(enabled = true,
            attackPeriodSeconds = 100.0, attackDurationSeconds = 10.0))
        val controller = EcosystemController(config, 0)
        val id = "xxxxxxxxxxxxxxxxxxxxxxxx"
        val seedHash = 0L
        val stable = id.fold(seedHash) { hash, char -> hash * 31L + char.code }
        assertTrue(stable < 0L)
        val samples = (0..99).map { controller.attackActive(id, it.toDouble()) }
        assertTrue(samples.any { it })
        assertTrue(samples.any { !it })
        assertEquals(samples, (100..199).map { controller.attackActive(id, it.toDouble()) })
    }

    @Test fun `sea vapor disables only an exposed resident without respiratory protection`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0,
            humanFactors = HumanFactorsConfig(enabled = true, availabilityCycleSeconds = 100.0,
                unavailableSeconds = 0.0, boredomSeconds = 0.0, alcoholPeriodSeconds = 100.0,
                alcoholDurationSeconds = 0.0, vandalPeriodSeconds = 100.0, vandalChancePercent = 0))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val result = controller.step(5.0, 1.0, listOf(
            EcosystemEntity("exposed", "civilian", Point(5.0, 5.0), inSeaVapor = true),
            EcosystemEntity("protected", "civilian", Point(5.0, 5.0), inSeaVapor = true, respiratorEquipped = true),
        ))
        assertEquals(setOf("exposed"), result.unavailableHumans)
    }

    @Test fun `cleanup travel fuel is finite and exhausted rover receives no further movement budget`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0, crocodileManurePerSecond = 1.0,
            cleanup = CleanupConfig(enabled = true, loadCapacity = 50.0, cleanPerSecond = 1.0,
                unloadAt = Point(0.0, 0.0), unloadRadius = 1.0, initialFuel = 10.0,
                fuelPerUnit = 0.0, fuelPerMetre = 1.0, fuelPricePerUnit = 1))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val croc = EcosystemEntity("croc", "crocodile", Point(5.0, 35.0))
        val cleaner = EcosystemEntity("cleaner", "cleanup-rover", Point(35.0, 35.0))
        val assignment = controller.step(0.0, 1.0, listOf(croc, cleaner)).serviceTargets.single()
        assertEquals(10.0, controller.movementBudgetMeters("cleaner"))

        // Model two kernel-clamped legs along the straight route: 8 m, then the final 2 m.
        assertEquals(5.0, assignment.target.x)
        assertEquals(35.0, assignment.target.y)
        val firstArrival = Point(27.0, 35.0)
        val firstLeg = controller.step(1.0, 1.0, listOf(croc, EcosystemEntity("cleaner", "cleanup-rover", firstArrival)))
        assertEquals(2.0, controller.movementBudgetMeters("cleaner"))
        val exhausted = controller.step(2.0, 1.0, listOf(croc, EcosystemEntity("cleaner", "cleanup-rover", Point(25.0, 35.0))))
        assertEquals(0.0, controller.movementBudgetMeters("cleaner"))
        assertEquals(10L, (firstLeg.postings + exhausted.postings).filter { it.owner == "cleaner" && it.kind == "cleanup" }.sumOf { it.amount })
        assertTrue(exhausted.serviceTargets.none { it.actorId == "cleaner" && it.task == "clean-manure" })
    }

    @Test fun `dead foresters and cleaners cannot perform ecology work`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0, crocodileManurePerSecond = 1.0,
            forest = ForestConfig(enabled = true), cleanup = CleanupConfig(enabled = true))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val result = controller.step(0.0, 1.0, listOf(
            EcosystemEntity("croc", "crocodile", Point(5.0, 5.0)),
            EcosystemEntity("cleanup-dead", "cleanup-rover", Point(5.0, 5.0), health = 0.0),
            EcosystemEntity("forester-dead", "forester", Point(5.0, 5.0), health = 0.0),
        ))
        assertTrue(result.serviceTargets.isEmpty())
        assertTrue(result.events.none { it.type == "ManureCleaned" || it.type == "ForestCleared" })
    }

    @Test fun `forest segment query returns a safe entry point and ignores disabled forest`() {
        val enabled = EcosystemConfig(enabled = true, zoneSize = 10.0, forest = ForestConfig(enabled = true))
        val controller = EcosystemController(enabled).also { it.configureBounds(bounds, 40.0) }
        val start = Point(35.0, 5.0)
        val end = Point(-25.0, 5.0)
        val block = controller.firstForestBlock(start, end)
        assertTrue(block != null)
        assertTrue(!controller.isBlocked(block))
        assertTrue(controller.isBlocked(Point(-5.0, 5.0)))
        val farBlock = controller.firstForestBlock(Point(1_000_000_000.0, 5.0), Point(-1_000_000_000.0, 5.0))
        assertEquals(0.0, farBlock?.x)

        val disabled = EcosystemController(EcosystemConfig(enabled = true, zoneSize = 10.0))
            .also { it.configureBounds(bounds, 40.0) }
        assertEquals(null, disabled.firstForestBlock(start, end))
    }

    @Test fun `cleanup refill uses finite depot and restock is bounded`() {
        val config = EcosystemConfig(enabled = true, zoneSize = 10.0,
            cleanup = CleanupConfig(enabled = true, initialFuel = 5.0, initialDepotFuel = 1.0,
                depotFuelCapacity = 2.0, fuelRestockPerSecond = 0.5, fuelPerMetre = 1.0,
                unloadAt = Point(35.0, 35.0), unloadRadius = 1.0))
        val controller = EcosystemController(config).also { it.configureBounds(bounds, 40.0) }
        val cleaner = EcosystemEntity("cleaner", "cleanup-rover", Point(35.0, 35.0))
        controller.step(0.0, 1.0, listOf(cleaner))
        controller.step(1.0, 1.0, listOf(cleaner.copy(at = Point(33.0, 35.0))))
        controller.step(2.0, 1.0, listOf(cleaner))
        assertEquals(0.0, controller.depotFuelRemaining, 1e-9)
        assertEquals(3.0, controller.serviceFuelRemaining("cleaner"), 1e-9,
            "four fuel units are spent on the round trip, and only two are available for refill")
        controller.step(3.0, 10.0, emptyList())
        assertEquals(2.0, controller.depotFuelRemaining, 1e-9) // finite stock never exceeds its configured capacity
    }
}
