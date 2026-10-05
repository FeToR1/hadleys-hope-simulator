package colony.world

import colony.bytecode.Op
import colony.runtime.VmIntent
import colony.runtime.prepareScenario
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class DisasterTest {
    private val prepared = prepareScenario(Path.of("../examples/physics/ecosystem-small.json"))
    private fun world(config: WorldConfig = prepared.scenario.world!!) =
        WorldKernel(prepared.manifest, config, buildTopology(prepared.manifest, config), 1.0)

    @Test fun explosionWaitsForAStepDamagesOnlyItsRadiusAndCreatesRepairJobs() {
        val world = world(prepared.scenario.world!!.let { it.copy(sea = it.sea.copy(enabled = false)) })
        val reactor = "grid/reactor"
        val center = world.positionOf(reactor)
        val radius = 80.0
        val before = world.fixtureState().associateBy { it.id }
        world.queueDisaster(Disaster.ReactorExplosion(radius, 200.0))
        assertFalse(world.isBroken(reactor), "queuing must not modify the paused world")
        assertFailsWith<IllegalArgumentException> { world.queueDisaster(Disaster.ReactorExplosion(0.0, 0.0)) }
        val facts = world.step(0, emptyList(), emptyList(), emptySet())
        assertTrue(world.isBroken(reactor))
        assertTrue(world.activeJobs.any { it.target == reactor } || world.taskQueue.allJobs.any { it.target == reactor })
        val damage = facts.filter { it.type == "DamageApplied" && it.fields["reason"]?.jsonPrimitive?.content == "ReactorExplosion" }
        assertTrue(damage.any { it.entityId == reactor })
        assertTrue(damage.size > 1)
        damage.forEach {
            val nearest = world.topology.byId[it.entityId]?.nearestPointTo(center) ?: world.positionOf(it.entityId)
            assertTrue(nearest.distanceTo(center) <= radius, "inside explosion: ${it.entityId}")
        }
        before.values.filter { it.at.distanceTo(center) > radius && it.from == null }.forEach { fixture ->
            assertFalse(damage.any { it.entityId == fixture.id }, "outside explosion: ${fixture.id}")
        }
        assertEquals(0, world.disasterOptions().getValue("pending").jsonPrimitive.int)
    }

    @Test fun manualCrocodilesFlyEvenWhenAutomaticThreatsAreDisabledAndReservePoolSpace() {
        val world = world(prepared.scenario.world!!.let { it.copy(defense = it.defense.copy(enabled = false)) })
        repeat(4) { world.queueDisaster(Disaster.Crocodiles(32)) }
        assertEquals(0, world.disasterOptions().getValue("crocodilesAvailable").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { world.queueDisaster(Disaster.Crocodiles(1)) }
        val facts = world.step(0, emptyList(), emptyList(), emptySet())
        assertEquals(128, facts.count { it.type == "CrocodileSpawned" })
        assertEquals(128, world.crocodilePool.activeCrocodiles().size)
        val croc = world.crocodilePool.activeCrocodiles().first()
        val start = croc.position
        world.step(1, emptyList(), emptyList(), emptySet())
        assertNotEquals(start, croc.position)
        assertEquals(128, world.totalCrocodilesSpawned)
    }

    @Test fun incursionUsesLivingMonstersIgnoresOldMovementAndOverridesRestForItsDuration() {
        val config = prepared.scenario.world!!.let { it.copy(ecosystem = it.ecosystem.copy(
            predator = it.ecosystem.predator.copy(enabled = true, attackDurationSeconds = 0.0))) }
        val world = world(config)
        val monster = prepared.manifest.instances.filter { it.kind in setOf("Xenomorph", "Predator") }.minBy { it.id }
        val previous = world.positionOf(monster.id)
        val movement = VmIntent(monster.id, Op.MOTION_REQUEST, listOf(
            buildJsonObject { put("x", previous.x); put("y", previous.y) }, JsonPrimitive(1000.0)))
        assertFalse(world.view(monster, setOf("attack_active")).getValue("attack_active").jsonPrimitive.boolean)
        world.queueDisaster(Disaster.Monsters(1, 10.0))
        val available = world.disasterOptions().getValue("monstersAvailable").jsonPrimitive.int
        assertFailsWith<IllegalArgumentException> { world.queueDisaster(Disaster.Monsters(available + 1, 10.0)) }
        val facts = world.step(0, listOf(movement), listOf("old-movement"), setOf(monster.id))
        val admitted = facts.single { it.type == "MonsterAdmitted" }
        val point = admitted.fields.getValue("position").jsonObject
        assertEquals(Point(point.getValue("x").jsonPrimitive.double, point.getValue("y").jsonPrimitive.double), world.positionOf(monster.id))
        assertNotEquals(previous, world.positionOf(monster.id))
        assertTrue(world.topology.fenceBox?.contains(world.positionOf(monster.id)) != false)
        assertTrue(world.view(monster, setOf("attack_active")).getValue("attack_active").jsonPrimitive.boolean)
        repeat(9) { world.step((it + 1).toLong(), emptyList(), emptyList(), emptySet()) }
        assertFalse(world.view(monster, setOf("attack_active")).getValue("attack_active").jsonPrimitive.boolean)
    }

    @Test fun invalidDisasterParametersAreRejected() {
        assertFailsWith<IllegalArgumentException> { Disaster.ReactorExplosion(Double.NaN, 100.0) }
        assertFailsWith<IllegalArgumentException> { Disaster.ReactorExplosion(301.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { Disaster.ReactorExplosion(60.0, -1.0) }
        assertFailsWith<IllegalArgumentException> { Disaster.Crocodiles(0) }
        assertFailsWith<IllegalArgumentException> { Disaster.Crocodiles(33) }
        assertFailsWith<IllegalArgumentException> { Disaster.Monsters(1, Double.POSITIVE_INFINITY) }
    }
}
