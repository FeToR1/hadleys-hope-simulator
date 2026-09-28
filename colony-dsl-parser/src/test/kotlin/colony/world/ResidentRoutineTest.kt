package colony.world

import colony.bytecode.Op
import colony.runtime.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class ResidentRoutineTest {
    private fun prepared(startMinute: Int = 480): PreparedRun {
        val base = prepareScenario(Path.of("../examples/physics/cascade.json"))
        return base.copy(
            scenario = base.scenario.copy(world = base.scenario.world!!.copy(
                human = HumanConfig(startMinute = startMinute),
                climate = Climate(meanTemperature = 20.0, amplitude = 0.0),
            )),
            manifest = base.manifest.copy(instances = base.manifest.instances.filter { it.kind !in setOf("Rover", "Xenomorph") }),
        )
    }

    @Test fun minersActuallyReachWorkWhileTheOtherResidentsStayInTheColony() {
        ReferenceRun(prepared(), "routine").use { run ->
            val first = run.step()
            val resident = "home-1/resident"
            val start = first.entities.single { it.id == resident }.coordinates
            val next = run.step().entities.single { it.id == resident }.coordinates
            assertEquals(1.4, Math.hypot(next.x - start.x, next.y - start.y), 1e-8,
                "walking must advance each second, not just once per minute")
            var last = first
            repeat(599) { last = run.step() }
            val humans = last.entities.filter { it.type == "civilian" }
            assertEquals(2, humans.count { it.vmState.getValue("activity").jsonPrimitive.content == "Mining" })
            assertEquals(4, humans.count { it.vmState.getValue("activity").jsonPrimitive.content == "Resting" })
            assertEquals(4, last.entities.filter { it.type == "house" }.sumOf { it.metrics.getValue("occupants").jsonPrimitive.int })
            assertTrue(last.effects.none { it.source == resident && it.operation == "MOTION_REQUEST" }, "a worker stops on arrival")
        }
    }

    @Test fun minersReturnHomeAtTheEndOfTheirShift() {
        val p = prepared(830)
        // Keep the morning miner and its household so the afternoon shift cannot obscure the assertion.
        val oneHouse = p.copy(manifest = p.manifest.copy(instances = p.manifest.instances.filter { it.id.startsWith("home-1/") }))
        ReferenceRun(oneHouse, "routine").use { run ->
            var mined = false
            var last = run.step()
            repeat(1199) {
                last = run.step()
                mined = mined || last.entities.single { it.type == "civilian" }.vmState.getValue("activity").jsonPrimitive.content == "Mining"
            }
            assertTrue(mined)
            val human = last.entities.single { it.type == "civilian" }
            val home = last.entities.single { it.type == "house" }
            assertEquals("Resting", human.vmState.getValue("activity").jsonPrimitive.content)
            assertEquals(home.coordinates, human.coordinates)
            assertEquals(1, home.metrics.getValue("occupants").jsonPrimitive.int)
        }
    }

    @Test fun shiftsLeisureInjuryAndNightHaveDistinctDestinations() {
        val p = prepared()
        fun point(x: Int) = buildJsonObject { put("x", x); put("y", 0) }
        fun decide(slot: Int, minute: Int, position: Int = 0, health: Int = 100): VmResult {
            val vm = ReferenceVm("person", p.program, "Resident")
            return vm.step(VmFrame(0, buildJsonObject {
                put("routine_slot", slot); put("day_minute", minute)
                put("home", point(0)); put("workplace", point(100)); put("meeting_point", point(50))
                put("position", point(position)); put("health", health)
                put("cold", false); put("reachable_breakables", JsonArray(emptyList()))
                put("in_vehicle", false); put("available_vehicles", JsonArray(emptyList())); put("boarding_radius", 6.0)
            }))
        }
        fun assertActivity(expected: String, result: VmResult, destination: Int? = null) {
            assertEquals(expected, result.state.getValue("activity").jsonPrimitive.content)
            val motion = result.intents.filter { it.operation == Op.MOTION_REQUEST }
            if (destination == null) assertTrue(motion.isEmpty())
            else assertEquals(point(destination), motion.single().arguments.first())
        }
        assertActivity("GoingToWork", decide(0, 480), 100)
        assertActivity("Resting", decide(4, 480)) // staggered until 08:05
        assertActivity("Resting", decide(3, 480)) // afternoon miner
        assertActivity("GoingToWork", decide(3, 840), 100)
        assertActivity("Working", decide(2, 600, position = 100))
        assertActivity("GoingToMeet", decide(1, 665), 50)
        assertActivity("Socializing", decide(1, 680, position = 50))
        assertActivity("ReturningHome", decide(1, 725, position = 50), 0)
        assertActivity("GoingToMeet", decide(0, 900), 50)
        assertActivity("Injured", decide(0, 500, position = 100, health = 39), 0)
        assertActivity("Injured", decide(0, 500, health = 39))
        assertActivity("Dead", decide(0, 500, health = 0))
        for (slot in 0..7) assertActivity("Resting", decide(slot, 1380))
    }

    @Test fun assignmentsAreBalancedAndTheClockWrapsAtMidnight() {
        val p = prepareScenario(Path.of("../examples/physics/colony-300.json"))
        val config = p.scenario.world!!.copy(human = HumanConfig(startMinute = 1439))
        val world = WorldKernel(p.manifest, config, buildTopology(p.manifest, config), 60.0)
        val humans = p.manifest.instances.filter { it.kind == "Human" }
        val cohorts = humans.map { world.view(it, setOf("routine_slot")).getValue("routine_slot").jsonPrimitive.int % 4 }
        assertEquals(mapOf(0 to 75, 1 to 75, 2 to 75, 3 to 75), cohorts.groupingBy { it }.eachCount())
        val human = humans.first()
        assertEquals(1439, world.view(human, setOf("day_minute")).getValue("day_minute").jsonPrimitive.int)
        world.step(0, emptyList(), emptyList(), emptySet())
        assertEquals(0, world.view(human, setOf("day_minute")).getValue("day_minute").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { HumanConfig(startMinute = 1440).validate() }
    }
}
