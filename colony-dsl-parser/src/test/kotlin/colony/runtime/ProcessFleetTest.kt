package colony.runtime

import colony.compiler.SourceFile
import colony.compiler.compileSource
import colony.cvm.NativeVmConnection
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProcessFleetTest {
    private fun prepared() = prepareScenario(Path.of("../examples/integration/small.settings"))
    private fun fleet(p: PreparedRun, workers: Int = 1) = ProcessFleet(p, assertNotNull(NativeVmConnection.executable()), "test", workers = workers)
    private fun handles(f: ProcessFleet) = (f.pids.values.toSet().map { it.toLong() } + f.brokerPid)
        .map { ProcessHandle.of(it).orElseThrow() }
    private fun assertStopped(processes: List<ProcessHandle>) {
        processes.forEach { it.onExit().get(10, TimeUnit.SECONDS); assertFalse(it.isAlive, "PID ${it.pid()} leaked") }
    }

    @Test fun nativeSnapshotsMatchDefaultFleetAcrossMessagesAndWorldEvents() {
        val p = prepared()
        val f = fleet(p)
        val processes = handles(f)
        assertEquals(f.workerCount + 1, processes.size)
        assertEquals(processes.size, processes.map { it.pid() }.toSet().size)
        assertTrue(f.workerCount < p.manifest.instances.size, "native contexts should share processes")
        assertTrue(processes.all { it.isAlive })
        SimulationRun(p, "test", f).use { native ->
            SimulationRun(p, "test").use { reference ->
                repeat(60) {
                    val actual = native.step()
                    assertEquals("process", actual.runtimeMode)
                    assertTrue(actual.entities.all { entity -> entity.pid == f.pids[entity.id] })
                    val normalized = actual.copy(runtimeMode = "process", entities = actual.entities.map { entity -> entity.copy(pid = null) })
                    assertTrue(jsonEquivalent(observerJson.encodeToJsonElement(reference.step().withoutPids()), observerJson.encodeToJsonElement(normalized)), "Snapshot differs at tick $it")
                }
            }
        }
        assertStopped(processes)
    }

    @Test fun oneAndTwoSharedNativeExecutorsProduceTheSameOrderedWorld() {
        val p = prepared()
        val one = fleet(p, workers = 1)
        val two = fleet(p, workers = 2)
        val processes = handles(one) + handles(two)
        assertEquals(1, one.pids.values.toSet().size)
        assertEquals(2, two.pids.values.toSet().size)
        SimulationRun(p, "shared-barrier", one).use { first ->
            SimulationRun(p, "shared-barrier", two).use { second ->
                repeat(40) { tick ->
                    val left = first.step()
                    val right = second.step().let { snapshot -> snapshot.copy(entities = snapshot.entities.map { it.copy(pid = null) }) }
                    val normalizedLeft = left.copy(entities = left.entities.map { it.copy(pid = null) })
                    assertTrue(jsonEquivalent(observerJson.encodeToJsonElement(normalizedLeft), observerJson.encodeToJsonElement(right)), "Executor count changed tick $tick")
                }
            }
        }
        assertStopped(processes)
    }

    @Test fun binaryBrokerPreservesLargeIntegersAndEffectOrderAcross513Contexts() {
        val source = """
            behavior Meter for Human {
                param start: Int64;
                state count: Int64 = start;
                every 1s as increment {
                    count = count + 1;
                    motion.request(view.home, 1mps);
                }
            }
        """.trimIndent()
        val template = Template(mapOf("resident" to ObjectSpec("Human", "Meter",
            params = buildJsonObject { put("start", 0) },
            view = buildJsonObject { put("cold", false); put("reachable_breakables", JsonArray(emptyList())) })))
        val expanded = expandScenario(
            Scenario(ticks = 2, populations = listOf(Population("meter", 513, "meter"))),
            Catalog(sources = listOf("meter.cly"), templates = mapOf("meter" to template)), compileSource(source))
        val p = expanded.copy(sources = listOf(SourceFile("meter.cly", source)),
            manifest = expanded.manifest.copy(instances = expanded.manifest.instances.mapIndexed { index, instance ->
                instance.copy(params = buildJsonObject { put("start", 9_007_199_254_740_993L + index) })
            }))
        val expected = SimulationRun(p, "binary-chunks").use { reference -> List(2) { reference.step().withoutPids() } }
        for (workers in listOf(1, 2)) {
            val f = fleet(p, workers)
            val processes = handles(f)
            assertEquals(workers + 1, processes.size)
            SimulationRun(p, "binary-chunks", f).use { native ->
                repeat(2) { tick ->
                    val actual = native.step().let { snapshot -> snapshot.copy(runtimeMode = "process",
                        entities = snapshot.entities.map { it.copy(pid = null) }) }
                    assertTrue(jsonEquivalent(observerJson.encodeToJsonElement(expected[tick]), observerJson.encodeToJsonElement(actual)),
                        "Binary broker changed the barrier with $workers workers at tick $tick")
                    assertEquals(p.manifest.instances.map { it.id }, actual.effects.map { it.source })
                    actual.entities.forEachIndexed { index, entity ->
                        assertEquals(9_007_199_254_740_993L + index + tick + 1,
                            entity.vmState.getValue("count").jsonPrimitive.long)
                    }
                }
            }
            assertStopped(processes)
        }
    }

    @Test fun deadVmAbortsBarrierAndCleansUpTheWholeRun() = failedProcess(false)

    @Test fun failureInSecondNativeShardDoesNotCommitFirstShardIntent() {
        val source = """
            behavior Attack for Predator {
                enum Cause { Test }
                param should_fail: Bool;
                state attempts: Real64 = 0.0;
                every 1s as act {
                    if should_fail { attempts = attempts / 0.0; } else {
                        if let target = nearest(view.visible_humans) { damage.request(target.id, 100hp, Test); }
                    }
                }
            }
            behavior Idle for Human { }
            behavior Home for House { }
        """.trimIndent()
        val program = compileSource(source)
        val template = Template(linkedMapOf(
            "a_good" to ObjectSpec("Predator", "Attack", buildJsonObject { put("should_fail", false) }),
            "b_bad" to ObjectSpec("Predator", "Attack", buildJsonObject { put("should_fail", true) }),
            "z_target" to ObjectSpec("Human", "Idle"),
            "zz_home" to ObjectSpec("House", "Home"),
        ))
        val world = prepareScenario(Path.of("../examples/physics/cascade.settings")).scenario.world!!
        val scenario = Scenario(ticks = 3, populations = listOf(Population("failure", 1, "failure")), world = world)
        val prepared = expandScenario(scenario, Catalog(sources = listOf("failure.cly"), templates = mapOf("failure" to template)), program)
            .copy(sources = listOf(SourceFile("failure.cly", source)))
        val executable = assertNotNull(NativeVmConnection.executable())
        val fleet = ProcessFleet(prepared, executable, "partial-tick", workers = 2)
        val goodId = "failure-1/a_good"
        val badId = "failure-1/b_bad"
        val targetId = "failure-1/z_target"
        assertTrue(fleet.pids.getValue(goodId) != fleet.pids.getValue(badId), "the successful intent and failure must run in different shards")
        SimulationRun(prepared, "partial-tick", fleet).use { run ->
            val failure = assertFailsWith<IllegalStateException> { run.step() }
            assertTrue(failure.message.orEmpty().contains(badId), "the failing VM context must cause the barrier failure: $failure")
            assertEquals(100.0, run.kernel!!.healthOf(targetId), "no damage intent is committed when any VM fails")
            assertFailsWith<IllegalStateException> { run.step() }
        }
    }

    @Test fun roverTransportsTheWholeSquadAndNativeSnapshotsMatchDefaultFleet() {
        val base = prepareScenario(Path.of("../examples/physics/cascade.settings"))
        val world = base.scenario.world!!
        val p = base.copy(
            scenario = base.scenario.copy(world = world.copy(
                marine = world.marine.copy(responseDelaySeconds = 0.0),
                repair = world.repair.copy(dispatchDelaySeconds = 10000.0),
                sight = world.sight.copy(sightRadius = 2000.0))),
            manifest = base.manifest.copy(instances = base.manifest.instances.filter {
                it.kind in setOf("Rover", "Marine", "Predator") || it.id.startsWith("home-1/")
            }.map { instance -> when (instance.kind) {
                "Rover" -> instance.copy(behavior = "PassengerRover", x = 0.0, y = 0.0)
                "Marine" -> instance.copy(x = instance.id.last().digitToInt() * 5.0, y = 0.0,
                    params = JsonObject(instance.params + ("success_probability" to JsonPrimitive(1.0))))
                "Predator" -> instance.copy(x = 200.0, y = 0.0)
                else -> instance
            } }),
        )
        val boarded = mutableSetOf<String>()
        val alighted = mutableSetOf<String>()
        var assault = false
        SimulationRun(p, "transport", fleet(p)).use { native ->
            SimulationRun(p, "transport").use { reference ->
                repeat(240) { tick ->
                    val actual = native.step().let { s -> s.copy(runtimeMode = "process", entities = s.entities.map { it.copy(pid = null) }) }
                    assertTrue(jsonEquivalent(observerJson.encodeToJsonElement(reference.step().withoutPids()), observerJson.encodeToJsonElement(actual)),
                        "Transport snapshot differs at tick $tick")
                    actual.events.forEach { event ->
                        if (event.entityId.startsWith("marines-")) {
                            when (event.fields["action"]?.jsonPrimitive?.content) {
                                "transport.board" -> boarded += event.entityId
                                "transport.alight" -> alighted += event.entityId
                            }
                        }
                        if (event.type == "DamageApplied" && event.fields["reason"]?.jsonPrimitive?.content == "MarineAssault") assault = true
                    }
                }
            }
        }
        assertEquals(5, boarded.size, "the complete squad boards, including late arrivals")
        assertEquals(boarded, alighted, "all marines reach the destination")
        assertTrue(assault, "the delivered squad gets one assault attempt")
    }

    @Test fun residentRoutineMovesIdenticallyThroughTheBroker() {
        val base = prepareScenario(Path.of("../examples/physics/cascade.settings"))
        val p = base.copy(manifest = base.manifest.copy(instances = base.manifest.instances.filter {
            it.id.startsWith("home-1/") || it.id.startsWith("home-2/")
        }))
        SimulationRun(p, "routine", fleet(p)).use { native ->
            SimulationRun(p, "routine").use { reference ->
                repeat(180) { tick ->
                    val actual = native.step().let { s -> s.copy(runtimeMode = "process", entities = s.entities.map { it.copy(pid = null) }) }
                    assertTrue(jsonEquivalent(observerJson.encodeToJsonElement(reference.step().withoutPids()), observerJson.encodeToJsonElement(actual)),
                        "Resident snapshot differs at tick $tick")
                }
            }
        }
    }
    @Test fun deadBrokerCleansUpItsChildren() = failedProcess(true)
    private fun failedProcess(killBroker: Boolean) {
        val p = prepared(); val f = fleet(p); val processes = handles(f)
        SimulationRun(p, "test", f).use { run ->
            run.step()
            val victim = if (killBroker) processes.last() else processes.first()
            victim.destroyForcibly(); victim.onExit().get(10, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { run.step() }
            assertFailsWith<IllegalStateException> { run.step() }
        }
        assertStopped(processes)
    }

    @Test fun resetReplacesProcessesAndCompletionReapsThem() {
        val p = prepared().let { it.copy(scenario = it.scenario.copy(ticks = 2, changes = emptyList())) }
        val fleets = mutableListOf<ProcessFleet>()
        RunController(p, fleetFactory = { prepared, _ -> fleet(prepared).also { fleets += it } }).use { controller ->
            val old = handles(fleets.single())
            controller.stepOnce()
            controller.reset()
            assertStopped(old)
            val current = handles(fleets.last())
            assertTrue(current.all { it.isAlive })
            assertEquals("process", controller.health().getValue("runtimeMode").jsonPrimitive.content)
            controller.stepOnce()
            assertEquals(RunController.Status.COMPLETED, controller.currentStatus)
            assertStopped(current)
        }
    }
}
