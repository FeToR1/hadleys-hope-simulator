package colony.runtime

import colony.bytecode.Op
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class WorldCausalityRegressionTest {
    private class ScriptedFleet(private val script: (Long) -> List<VmIntent>) : VmFleet {
        override val mode = "test"
        override val pids = emptyMap<String, Int>()
        override fun step(frames: Map<String, VmFrame>): Map<String, VmResult> {
            val intents = script(frames.values.first().tick).groupBy { it.source }
            return frames.mapValues { (id, _) -> VmResult(intents[id].orEmpty(), emptyList(), JsonObject(emptyMap())) }
        }
    }

    private fun run(script: (Long, String, String) -> List<VmIntent>): ReferenceRun {
        val base = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val config = base.scenario.world!!
        val prepared = base.copy(scenario = base.scenario.copy(ticks = 8, world = config.copy(
            repair = config.repair.copy(duration = mapOf("pole" to 1.0, "pipe" to 1.0, "device" to 1.0),
                dispatchDelaySeconds = 0.0, workRadius = 10_000.0))))
        val attacker = prepared.manifest.instances.first { it.kind == "Xenomorph" }.id
        val crew = prepared.manifest.instances.first { it.kind == "Rover" }.id
        return ReferenceRun(prepared, "test", ScriptedFleet { tick -> script(tick, attacker, crew) })
    }

    private fun damage(source: String, target: String) = VmIntent(source, Op.DAMAGE_REQUEST,
        listOf(JsonPrimitive(target), JsonPrimitive(1000.0), JsonPrimitive("test")))
    private fun repair(source: String, target: String) = VmIntent(source, Op.REPAIR_REQUEST, listOf(JsonPrimitive(target)))
    private fun TickSnapshot.event(type: String, entity: String) = events.single { it.type == type && it.entityId == entity }

    @Test fun busBreakAndRepairCauseBothNetworkTransitionsEvenOnFastSteps() {
        run { tick, attacker, crew -> when (tick) {
            0L -> listOf(damage(attacker, "grid/bus"))
            1L -> listOf(repair(crew, "grid/bus"))
            else -> emptyList()
        } }.use { run ->
            val lost = run.step(captureSnapshot = false)
            val broken = lost.event("ObjectBroken", "grid/bus")
            assertTrue(lost.events.any { it.type == "PowerLost" })
            assertTrue(lost.events.any { it.type == "WaterLost" })
            lost.events.filter { it.type in setOf("PowerLost", "WaterLost") }.forEach { assertEquals(broken.id, it.causationId) }
            val restored = run.step()
            val repaired = restored.event("RepairCompleted", "grid/bus")
            assertEquals(broken.id, repaired.causationId)
            assertTrue(restored.events.any { it.type == "PowerRestored" })
            assertTrue(restored.events.any { it.type == "WaterRestored" })
            restored.events.filter { it.type in setOf("PowerRestored", "WaterRestored") }.forEach { assertEquals(repaired.id, it.causationId) }
        }
    }

    @Test fun repairedPipeCannotCauseALaterPumpOutage() {
        val house = "home-1/house"
        val pipe = "water/pipe-home-1-house"
        run { tick, attacker, crew -> when (tick) {
            0L -> listOf(damage(attacker, pipe))
            1L -> listOf(repair(crew, pipe))
            2L -> listOf(damage(attacker, "water/pump"))
            3L -> listOf(repair(crew, "water/pump"))
            else -> emptyList()
        } }.use { run ->
            val broken = run.step().event("ObjectBroken", pipe)
            val restored = run.step()
            val repaired = restored.event("RepairCompleted", pipe)
            assertEquals(broken.id, repaired.causationId)
            assertEquals(repaired.id, restored.event("WaterRestored", house).causationId)
            val lost = run.step()
            val pumpBreak = lost.event("ObjectBroken", "water/pump")
            assertEquals(pumpBreak.id, lost.event("WaterLost", house).causationId)
            lost.events.filter { it.type == "WaterLost" }.forEach { assertEquals(pumpBreak.id, it.causationId) }
            assertTrue(lost.events.none { it.type == "PowerLost" })
            val wet = run.step()
            val pumpRepair = wet.event("RepairCompleted", "water/pump")
            assertEquals(pumpBreak.id, pumpRepair.causationId)
            assertEquals(pumpRepair.id, wet.event("WaterRestored", house).causationId)
            wet.events.filter { it.type == "WaterRestored" }.forEach { assertEquals(pumpRepair.id, it.causationId) }
        }
    }
}
