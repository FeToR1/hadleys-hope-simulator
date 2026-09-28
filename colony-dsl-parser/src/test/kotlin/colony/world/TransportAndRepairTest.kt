package colony.world

import colony.bytecode.Op
import colony.runtime.Instance
import colony.runtime.ReferenceRun
import colony.runtime.RunManifest
import colony.runtime.VmIntent
import colony.runtime.prepareScenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.test.*

class TransportAndRepairTest {
    private fun smallManifest(): RunManifest = RunManifest(
        seed = 1,
        stepSeconds = "1",
        instances = listOf(
            Instance("house", "House", "Dummy", JsonObject(emptyMap()), JsonObject(emptyMap()), null, 0.0, 0.0),
            Instance("rover", "Rover", "Dummy", JsonObject(emptyMap()), JsonObject(emptyMap()), null, 0.0, 0.0),
            Instance("person", "Human", "Dummy", JsonObject(emptyMap()), JsonObject(emptyMap()), "house", 2.0, 0.0),
        ),
    )

    @Test fun aRepairJobIsNotVisibleUntilItsDispatchDelayHasElapsed() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val run = ReferenceRun(prepared, "repair-delay-test")
        val kernel = assertNotNull(run.kernel)
        var brokenTick: Long? = null
        var firstVisibleTick: Long? = null
        run.use { active ->
            repeat(300) {
                val snapshot = active.step()
                if (brokenTick == null) {
                    brokenTick = snapshot.events.firstOrNull { it.type == "ObjectBroken" }?.tick
                }
                if (firstVisibleTick == null && kernel.activeJobs.isNotEmpty()) {
                    firstVisibleTick = snapshot.tickId
                }
            }
        }
        val broken = assertNotNull(brokenTick, "the cascade must create a broken object")
        val visible = assertNotNull(firstVisibleTick, "the repair job should eventually become dispatchable")
        val expectedDelay = ceil(prepared.scenario.world!!.repair.dispatchDelaySeconds / prepared.program.stepSeconds.toDouble()).toLong()
        assertTrue(visible - broken >= expectedDelay, "job became visible too early: broken=$broken visible=$visible delay=$expectedDelay")
    }

    @Test fun aPassengerCanBoardAndBeDroppedOffWithoutASeparateVmOpcode() {
        val manifest = smallManifest()
        val config = WorldConfig()
        val topology = buildTopology(manifest, config)
        val kernel = WorldKernel(manifest, config, topology, 1.0)
        val request = TransportRequest("person@0:event0", "person", "rover", Point(18.0, 0.0))
        val accepted = manifest.instances.map { it.id }.toSet()

        val boardingEvents = kernel.step(0, emptyList(), emptyList(), accepted, listOf(request))
        assertTrue(boardingEvents.any { it.type == "ActionSucceeded" && it.fields["action"]?.jsonPrimitive?.content == "transport.board" })

        val motion = VmIntent(
            source = "rover",
            operation = Op.MOTION_REQUEST,
            arguments = listOf(
                buildJsonObject { put("x", 18.0); put("y", 0.0) },
                JsonPrimitive(18.0),
            ),
        )
        val dropoffEvents = kernel.step(1, listOf(motion), listOf("rover@1#0"), accepted)
        assertEquals(Point(18.0, 0.0), kernel.positionOf("person"))
        assertTrue(dropoffEvents.any { it.type == "ActionSucceeded" && it.fields["action"]?.jsonPrimitive?.content == "transport.alight" })
    }
    @Test fun marineScenarioContainsACompleteFivePersonSquad() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val marines = prepared.manifest.instances.filter { it.kind == "Marine" }
        assertEquals(5, marines.size)
        assertEquals(1, marines.count { it.params["leader"]?.jsonPrimitive?.booleanOrNull == true })
        assertEquals(setOf("marines-1"), marines.mapNotNull { it.params["squad"]?.jsonPrimitive?.contentOrNull }.toSet())
        assertTrue(marines.all { it.params["success_probability"]?.jsonPrimitive?.doubleOrNull == 0.05 })
    }

}
