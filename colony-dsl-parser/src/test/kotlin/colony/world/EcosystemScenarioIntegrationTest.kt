package colony.world

import colony.runtime.ReferenceRun
import colony.runtime.prepareScenario
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EcosystemScenarioIntegrationTest {
    @Test fun `small ecology scenario cleans street manure and unloads at its depot`() {
        val prepared = prepareScenario(Path.of("../examples/physics/ecosystem-small.json"))
        assertFalse(prepared.manifest.instances.any { it.kind == "Xenomorph" })
        val predator = assertNotNull(prepared.manifest.instances.firstOrNull { it.kind == "Predator" })
        val observedEvents = ArrayList<colony.runtime.WorldEvent>()
        var mutation: String? = null
        var snapshotType: String? = null
        ReferenceRun(prepared, "ecosystem-cleanup-integration").use { run ->
            repeat(prepared.scenario.ticks) {
                val frame = run.step()
                if (it == 0) {
                    val snapshot = frame.entities.single { it.id == predator.id }
                    snapshotType = snapshot.type
                    mutation = snapshot.metrics["mutation"]?.jsonPrimitive?.content
                }
                observedEvents += frame.events
            }
        }
        assertTrue(mutation in setOf("armored", "swift", "venomous", "pack"), "Ground predators should receive an ecosystem mutation metric")
        assertTrue(snapshotType == "predator", "Ground predators should have a distinct snapshot type")
        assertTrue(observedEvents.any { it.type == "ManureCleaned" }, "Cleanup rovers should clear accessible street zones")
        assertTrue(observedEvents.any { it.type == "ManureUnloaded" }, "Cleanup rovers should deliver collected manure to the depot")
        assertTrue(observedEvents.any { it.type == "EntityDied" && it.entityId.startsWith("predator-") },
            "A killed ground predator should be recorded as an entity death")
        assertFalse(observedEvents.any { it.type == "ObjectBroken" && it.entityId.startsWith("predator-") })
    }

    @Test fun `ground predator emits its distinct attack cause`() {
        val base = prepareScenario(Path.of("../examples/physics/ecosystem-small.json"))
        val kept = base.manifest.instances.filter { it.id.startsWith("home-1/") || it.id.startsWith("predator-1/") }
        val world = checkNotNull(base.scenario.world).copy(
            fence = FenceConfig(enabled = false),
            sea = SeaConfig(enabled = false),
            defense = DefenseConfig(enabled = false),
            ecosystem = checkNotNull(base.scenario.world).ecosystem.copy(
                forest = ForestConfig(enabled = false),
                predator = PredatorConfig(enabled = true, attackPeriodSeconds = 1.0, attackDurationSeconds = 1.0),
                cleanup = CleanupConfig(enabled = false),
                plankton = PlanktonConfig(enabled = false),
                humanFactors = HumanFactorsConfig(enabled = false),
            ),
        )
        val partial = base.copy(
            scenario = base.scenario.copy(ticks = 60, world = world),
            manifest = base.manifest.copy(instances = kept),
        )
        val topology = buildTopology(partial.manifest, world)
        val nearestPole = topology.poles.minBy { it.at.distanceTo(Point(kept.first { it.kind == "House" }.x, kept.first { it.kind == "House" }.y)) }
        val positioned = partial.copy(manifest = partial.manifest.copy(instances = kept.map {
            if (it.kind == "Predator") it.copy(x = nearestPole.at.x, y = nearestPole.at.y) else it
        }))

        val events = ArrayList<colony.runtime.WorldEvent>()
        ReferenceRun(positioned, "predator-attack-cause").use { run ->
            repeat(positioned.scenario.ticks) { events += run.step().events }
        }
        assertTrue(events.any { it.type == "DamageApplied" && it.fields["reason"]?.jsonPrimitive?.content == "PredatorAttack" })
    }
}
