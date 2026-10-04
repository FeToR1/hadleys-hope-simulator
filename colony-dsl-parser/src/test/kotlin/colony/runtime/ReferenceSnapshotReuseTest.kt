package colony.runtime

import java.nio.file.Path
import colony.world.EcosystemConfig
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceSnapshotReuseTest {
    @Test fun unchangedInfrastructureFixturesReuseTheirImmutableSnapshots() {
        val prepared = prepareScenario(Path.of("../examples/physics/cascade.json"))
        assertTrue(prepared.scenario.world?.creatine?.enabled != true, "fixture-only mine metrics stay absent in the legacy scenario")
        ReferenceRun(prepared, "snapshot-reuse").use { run ->
            val firstSnapshot = run.step()
            val first = firstSnapshot.entities.filter { it.id == "grid/reactor" }
            assertTrue(first.isNotEmpty())
            val mine = firstSnapshot.entities.single { it.id == "site/mine" }
            assertEquals("nominal", mine.status)
            assertTrue("health" !in mine.metrics, "disabled creatine must not create a phantom mine fixture metric")
            val second = run.step().entities.associateBy { it.id }
            first.forEach { fixture -> assertSame(fixture, second.getValue(fixture.id), fixture.id) }
        }
    }

    @Test fun ecologyZonesAreObservableAndReuseUnchangedSnapshots() {
        val base = prepareScenario(Path.of("../examples/physics/cascade.json"))
        val world = base.scenario.world!!
        val prepared = base.copy(scenario = base.scenario.copy(world = world.copy(ecosystem = EcosystemConfig(enabled = true))))
        ReferenceRun(prepared, "zone-snapshots").use { run ->
            val first = run.step().entities.filter { it.type == "ecology_zone" }
            assertTrue(first.isNotEmpty())
            val second = run.step().entities.associateBy { it.id }
            first.forEach { zone -> assertSame(zone, second.getValue(zone.id), zone.id) }
            assertEquals((first.first().metrics.getValue("max_x").jsonPrimitive.double - first.first().metrics.getValue("min_x").jsonPrimitive.double) / 2.0,
                first.first().coordinates.x - first.first().metrics.getValue("min_x").jsonPrimitive.double)
        }
    }
}
