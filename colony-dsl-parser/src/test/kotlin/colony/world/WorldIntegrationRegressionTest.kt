package colony.world

import colony.bytecode.Op
import colony.runtime.Instance
import colony.runtime.RunManifest
import colony.runtime.VmIntent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldIntegrationRegressionTest {
    private fun instance(
        id: String, kind: String, behavior: String = "Dummy", x: Double = 0.0, y: Double = 0.0,
        parent: String? = null,
    ) = Instance(id, kind, behavior, JsonObject(emptyMap()), JsonObject(emptyMap()), parent, x, y)

    private fun manifest(vararg instances: Instance) = RunManifest(seed = 1, stepSeconds = "1", instances = instances.toList())

    @Test fun disabledMechanicsDoNotChangeTheLegacyFixtureSetOrSpawnCrocodiles() {
        val m = manifest(instance("house", "House"), instance("xeno", "Xenomorph", x = -100.0))
        val config = WorldConfig()
        val topology = buildTopology(m, config)
        assertTrue(topology.airDefenses.isEmpty())
        assertTrue(topology.groundTurrets.isEmpty())
        assertFalse(topology.fixtures.any { it.kind == FixtureKind.BURNER })

        val world = WorldKernel(m, config, topology, 1.0)
        assertTrue("xeno" in world.spatialIndex.queryRadius(world.positionOf("xeno"), 0.0))
        repeat(25) { tick -> world.step(tick.toLong(), emptyList(), emptyList(), m.instances.map { it.id }.toSet()) }
        assertTrue(world.crocodilePool.activeCrocodiles().isEmpty())
        assertEquals(0, world.totalCrocodilesSpawned)
    }

    @Test fun legacyLargeSettlementKeepsManifestRoverPositions() {
        val houses = (0 until 100).map { index ->
            instance("house-$index", "House", x = (index % 10) * 30.0, y = (index / 10) * 30.0)
        }
        val rovers = (1..6).map { index -> instance("rover-$index", "Rover", x = 120.0 + index, y = 450.0 + index) }
        val m = manifest(*(houses + rovers).toTypedArray())
        val config = WorldConfig()
        val world = WorldKernel(m, config, buildTopology(m, config), 1.0)
        rovers.forEach { assertEquals(Point(it.x, it.y), world.positionOf(it.id)) }
    }

    @Test fun configuredGateLeavesAnOpeningAndIntermediateWaypointIsNotArrival() {
        val mine = Point(0.0, 0.0)
        val baseConfig = WorldConfig(creatine = CreatineEconomyConfig(enabled = true), fence = FenceConfig(enabled = true),
            geometry = GeometryConfig(mine = mine, depository = mine, medicalCenter = mine))
        val m = manifest(instance("house", "House"), instance("rover", "Rover", "PassengerRover"))
        val base = buildTopology(m, baseConfig)
        val box = checkNotNull(base.fenceBox)
        val gate = Point(box.maxX, (box.minY + box.maxY) / 2.0)
        val config = baseConfig.copy(geometry = baseConfig.geometry.copy(gates = listOf(gate)))
        val topology = buildTopology(m, config)
        assertTrue(topology.mineFixture != null)
        assertTrue(topology.fence.all { it.nearestPointTo(gate).distanceTo(gate) >= config.geometry.gateWidth / 2.0 - 1e-6 })

        val world = WorldKernel(m, config, topology, 1.0)
        val destination = Point(box.maxX + 80.0, gate.y)
        val request = VmIntent("rover", Op.MOTION_REQUEST,
            listOf(buildJsonObject { put("x", destination.x); put("y", destination.y) }, JsonPrimitive(30.0)))
        var sawArrival = false
        var reachedGate = false
        for (tick in 0L..30L) {
            val events = world.step(tick, listOf(request), listOf("rover@$tick"), setOf("house", "rover"))
            val atGate = world.positionOf("rover").distanceTo(gate) <= 1.0
            if (atGate && world.positionOf("rover").distanceTo(destination) > 1.0) {
                reachedGate = true
                assertTrue(events.none { it.type == "ArrivalConfirmed" && it.entityId == "rover" })
            }
            sawArrival = sawArrival || events.any { it.type == "ArrivalConfirmed" && it.entityId == "rover" }
        }
        assertTrue(reachedGate)
        assertTrue(sawArrival)
    }

    @Test fun groundDefenseRequiresGridPowerAndSpendsFiniteAmmoOnlyWhenItFires() {
        fun defenseWorld(unpowered: Boolean): Pair<WorldKernel, String> {
            val base = manifest(instance("house", "House"), instance("xeno", "Xenomorph", x = -100.0))
            val cfg = WorldConfig(defense = DefenseConfig(enabled = true, initialAmmoPerUnit = 1, powerPerUnit = 100.0),
                power = if (unpowered) PowerConfig(reactorPower = 0.0, solarPeak = 0.0, upsInitialCharge = 0.0) else PowerConfig())
            val initialTopology = buildTopology(base, cfg)
            val target = initialTopology.groundTurrets.first()
            val withTarget = base.copy(instances = base.instances.map {
                if (it.id == "xeno") it.copy(x = target.at.x, y = target.at.y) else it
            })
            val topology = buildTopology(withTarget, cfg)
            val world = WorldKernel(withTarget, cfg, topology, 1.0)
            world.groundDefenseUnits.keys.toList().filter { it != target.id }.forEach(world.groundDefenseUnits::remove)
            return world to target.id
        }

        val (dark, darkTurret) = defenseWorld(unpowered = true)
        val darkAccepted = setOf("house", "xeno")
        dark.step(0, emptyList(), emptyList(), darkAccepted)
        assertEquals(100.0, dark.healthOf("xeno"))
        assertEquals(1.0, dark.groundDefenseUnits.getValue(darkTurret).ammoRemaining)
        assertTrue(dark.lastPostings.none { it.kind == "defense_ammo" })

        val (lit, turretId) = defenseWorld(unpowered = false)
        lit.step(0, emptyList(), emptyList(), darkAccepted)
        val turret = lit.groundDefenseUnits.getValue(turretId)
        assertEquals(60.0, lit.healthOf("xeno"))
        assertEquals(0.0, turret.ammoRemaining)
        assertEquals(1L, turret.shotsFired)
        assertTrue(lit.lastPostings.any { it.kind == "defense_ammo" && it.amount > 0 })
        lit.step(1, emptyList(), emptyList(), darkAccepted)
        assertEquals(60.0, lit.healthOf("xeno"), "an empty turret cannot fire a second round")
        assertEquals(1L, turret.shotsFired)
    }

    @Test fun finiteRepairChargesForOneConsumedKitAndLaborOnce() {
        val config = WorldConfig(
            creatine = CreatineEconomyConfig(enabled = true),
            repair = RepairConfig(duration = mapOf("pole" to 1.0), parts = mapOf("pole" to 5000L),
                hourlyRate = 3600L, workRadius = 2.0, dispatchDelaySeconds = 0.0, materials = 1,
                initialDepotMaterials = 0, materialCost = 700L),
        )
        val initial = manifest(instance("house", "House"), instance("crew-1/rover", "Rover", "RepairCrew"))
        val initialTopology = buildTopology(initial, config)
        val pole = initialTopology.poles.first { it.id.startsWith("grid/pole-") }
        val m = initial.copy(instances = initial.instances.map {
            if (it.id == "crew-1/rover") it.copy(x = pole.at.x, y = pole.at.y) else it
        })
        val world = WorldKernel(m, config, buildTopology(m, config), 1.0)
        val intents = listOf(
            VmIntent("crew-1/rover", Op.DAMAGE_REQUEST, listOf(JsonPrimitive(pole.id), JsonPrimitive(100.0), JsonPrimitive("test"))),
            VmIntent("crew-1/rover", Op.REPAIR_REQUEST, listOf(JsonPrimitive(pole.id))),
        )
        val events = world.step(0, intents, listOf("damage", "repair"), m.instances.map { it.id }.toSet())
        assertTrue(events.any { it.type == "RepairCompleted" && it.entityId == pole.id })
        assertEquals(701L, world.lastPostings.single { it.kind == "repair" }.amount)
        assertTrue(world.lastPostings.none { it.kind == "repair_materials" })
    }

    @Test fun creatineMassMovesOnceAndCargoRolesUseNaturalNumericIdOrder() {
        val manager = CreatineManager(CreatineEconomyConfig(enabled = true, sellFraction = 0.4, pricePerUnit = 10,
            initialDepotStock = 0.0, initialMedicalStock = 0.0))
        manager.produceAtMine(100.0)
        manager.mineStock -= 100.0 // the physical cargo is loaded once before delivery
        val delivered = manager.transferToDepository(100.0)
        assertEquals(0.0, manager.mineStock)
        assertEquals(60.0, manager.storedStock)
        assertEquals(40.0, manager.totalSold)
        assertEquals(100.0, manager.storedStock + manager.totalSold + manager.medicalCenterStock)
        assertEquals(400L, delivered.revenue)

        val roster = manifest(
            instance("house", "House"),
            instance("cargo-10/rover", "Rover", "CargoRover"),
            instance("cargo-2/rover", "Rover", "CargoRover"),
        )
        val cfg = WorldConfig(creatine = CreatineEconomyConfig(enabled = true, initialDepotStock = 0.0, initialMedicalStock = 0.0))
        val firstTopology = buildTopology(roster, cfg)
        val positioned = roster.copy(instances = roster.instances.map {
            when (it.id) {
                "cargo-2/rover" -> it.copy(x = firstTopology.mine.x, y = firstTopology.mine.y)
                "cargo-10/rover" -> it.copy(x = firstTopology.depository.x, y = firstTopology.depository.y)
                else -> it
            }
        })
        val topology = buildTopology(positioned, cfg)
        val world = WorldKernel(positioned, cfg, topology, 1.0)
        world.creatineManager.mineStock = 40.0
        world.step(0, emptyList(), emptyList(), positioned.instances.map { it.id }.toSet())
        assertEquals(20.0, world.roverCreatineCargo["cargo-2/rover"])
        assertFalse("cargo-10/rover" in world.roverCreatineCargo)
        assertEquals(20.0, world.creatineManager.mineStock)
        assertEquals("depository", world.roverLogisticsTarget["cargo-2/rover"])
        assertEquals("medcenter", world.roverLogisticsTarget["cargo-10/rover"])
    }

    @Test fun monthClosureFlushesMetersAtCalendarBoundaryIndependentOfBillingInterval() {
        val house = instance("house", "House")
        val heater = instance("heater", "Heater", parent = "house")
        val m = manifest(house, heater)
        val cfg = WorldConfig(tariffs = Tariffs(electricityPerKwh = 3_600_000, billingIntervalSeconds = 5, monthSeconds = 3))
        val world = WorldKernel(m, cfg, buildTopology(m, cfg), 1.0)
        val accepted = setOf("house", "heater")
        fun request() = VmIntent("heater", Op.POWER_REQUEST, listOf(JsonPrimitive(100.0)))

        val closedReports = ArrayList<Map<String, Long>>()
        val closeEvents = ArrayList<KernelEvent>()
        repeat(6) { tick ->
            val events = world.step(tick.toLong(), listOf(request()), listOf("heater@$tick"), accepted)
            events.filter { it.type == "MonthClosed" }.forEach { closeEvents += it; closedReports += world.lastClosedMonthlyReport() }
            if (tick == 2) {
                assertEquals(300L, world.lastClosedMonthlyReport().values.sum())
                assertTrue(world.monthlyReport().isEmpty(), "the open month's totals must start empty")
            }
        }
        assertEquals(2, closeEvents.size)
        assertEquals(300L, closeEvents[0].fields.getValue("total").toString().toLong())
        assertEquals(300L, closeEvents[1].fields.getValue("total").toString().toLong())
        assertEquals(300L, closedReports[0].values.sum())
        assertEquals(300L, closedReports[1].values.sum())
        assertEquals(emptyMap(), world.monthlyReport(), "the third month starts with no inherited totals")
        fun ledgerExpenses(fromTick: Long, throughTick: Long) = world.ledger
            .filter { it.tick in fromTick..throughTick && it.kind != "creatine_sale" }
            .groupBy { it.owner }.mapValues { (_, lines) -> lines.sumOf { it.amount } }
        assertEquals(ledgerExpenses(0, 2), closedReports[0])
        assertEquals(ledgerExpenses(3, 5), closedReports[1])
    }
}
