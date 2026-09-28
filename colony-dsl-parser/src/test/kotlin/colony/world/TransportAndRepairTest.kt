package colony.world

import colony.bytecode.Op
import colony.runtime.Instance
import colony.runtime.ReferenceRun
import colony.runtime.RunManifest
import colony.runtime.VmIntent
import colony.runtime.prepareScenario
import colony.bytecode.bytecodeJson
import colony.runtime.jsonEquivalent
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.test.*

class TransportAndRepairTest {
    private fun motion(id: String, x: Double, speed: Double = 9.0) = VmIntent(id, Op.MOTION_REQUEST,
        listOf(buildJsonObject { put("x", x); put("y", 0.0) }, JsonPrimitive(speed)))
    private fun damage(id: String) = VmIntent("person", Op.DAMAGE_REQUEST,
        listOf(JsonPrimitive(id), JsonPrimitive(100.0), JsonPrimitive("test")))
    private fun world(manifest: RunManifest = smallManifest(), config: WorldConfig = WorldConfig()) =
        WorldKernel(manifest, config, buildTopology(manifest, config), 1.0)
    private fun WorldKernel.advance(tick: Long, manifest: RunManifest, intents: List<VmIntent> = emptyList(), requests: List<TransportRequest> = emptyList()) =
        step(tick, intents, intents.indices.map { "request-$tick-$it" }, manifest.instances.map { it.id }.toSet(), requests)
    private fun request(passenger: String = "person", x: Double = 18.0) = TransportRequest("ride-$passenger", passenger, "rover", Point(x, 0.0))
    private fun WorldKernel.field(manifest: RunManifest, id: String, field: String) =
        view(manifest.instances.single { it.id == id }, setOf(field)).getValue(field)

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
        kernel.step(1, listOf(motion), listOf("rover@1#0"), accepted)
        val dropoffEvents = kernel.step(2, listOf(motion), listOf("rover@2#0"), accepted)
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

    @Test fun ridersCannotWalkIndependentlyEvenOnTheBoardingAndArrivalTicks() {
        for (roverFirst in listOf(true, false)) {
            val m = smallManifest()
            val k = world(m)
            k.advance(0, m, listOf(motion("person", -50.0)), listOf(request()))
            assertEquals(k.positionOf("rover"), k.positionOf("person"), "boarding snaps to the vehicle")
            for (tick in 1L..2L) {
                val intents = listOf(motion("rover", 18.0), motion("person", -50.0)).let { if (roverFirst) it else it.reversed() }
                k.advance(tick, m, intents)
                assertEquals(k.positionOf("rover"), k.positionOf("person"))
            }
            assertNull(k.vehicleOf("person"))
            assertEquals(Point(18.0, 0.0), k.positionOf("person"))
        }
    }

    @Test fun boardingRejectsCapacityRouteDistanceAndNonfiniteDestinations() {
        val base = smallManifest()
        val person = base.instances.single { it.id == "person" }
        val m = base.copy(instances = base.instances + person.copy(id = "second") + person.copy(id = "far", x = 50.0))
        fun rejected(config: WorldConfig, second: TransportRequest, reason: String) {
            val k = world(m, config)
            k.advance(0, m, requests = listOf(request()))
            val events = k.advance(1, m, requests = listOf(second))
            assertEquals(reason, events.single { it.type == "ActionRejected" }.fields["reason"]?.jsonPrimitive?.content)
            assertEquals(1, k.field(m, "rover", "passenger_count").jsonPrimitive.int)
        }
        rejected(WorldConfig(transport = TransportConfig(passengerCapacity = 1)), request("second"), "vehicle_full")
        rejected(WorldConfig(), request("second", 25.0), "vehicle_already_committed")
        rejected(WorldConfig(), request("far"), "out_of_boarding_range")
        rejected(WorldConfig(), request("second", Double.NaN), "invalid_destination")
    }

    @Test fun brokenRoversUnloadAndDeadPassengersReleaseTheRoute() {
        val m = smallManifest()
        val broken = world(m)
        broken.advance(0, m, requests = listOf(request()))
        val events = broken.advance(1, m, listOf(damage("rover"), motion("rover", 18.0)))
        assertNull(broken.vehicleOf("person"))
        assertEquals(Point(0.0, 0.0), broken.positionOf("person"))
        assertTrue(events.any { it.fields["reason"]?.jsonPrimitive?.content == "vehicle_broken" })

        val extra = m.instances.single { it.id == "person" }.copy(id = "second")
        val extended = m.copy(instances = m.instances + extra)
        val dead = world(extended)
        dead.advance(0, extended, requests = listOf(request()))
        dead.advance(1, extended, listOf(damage("person")), listOf(request("second", 30.0)))
        assertEquals("rover", dead.vehicleOf("second"))
        assertEquals(30.0, dead.field(extended, "rover", "transport_target").jsonObject.getValue("x").jsonPrimitive.double)
    }

    private fun squadManifest(): RunManifest {
        val base = smallManifest()
        val marines = (1..5).map { index -> Instance("marine-$index", "Marine", "Dummy",
            buildJsonObject { put("squad", "alpha"); put("leader", index == 1) }, JsonObject(emptyMap()), null, 0.0, 0.0) }
        val alien = Instance("alien", "Xenomorph", "Dummy", JsonObject(emptyMap()), JsonObject(emptyMap()), null, 36.0, 0.0)
        return base.copy(instances = base.instances + marines + alien)
    }

    @Test fun aSquadBoardsAcrossTicksAndSharesAMovingTarget() {
        val m = squadManifest()
        val k = world(m)
        k.advance(0, m, requests = listOf(request("marine-1", 36.0)))
        assertTrue(k.field(m, "marine-2", "available_vehicles").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "rover" })
        k.advance(1, m, listOf(motion("rover", -30.0), motion("alien", 45.0)))
        assertEquals(Point(0.0, 0.0), k.positionOf("rover"), "wait for the entire squad")
        for (member in 2..5) k.advance(member.toLong(), m, requests = listOf(request("marine-$member", 45.0)))
        assertTrue(k.field(m, "rover", "transport_ready").jsonPrimitive.boolean)
        assertEquals(5, k.field(m, "rover", "passenger_count").jsonPrimitive.int)
        for (tick in 6L..10L) k.advance(tick, m, listOf(motion("rover", 45.0)))
        assertEquals(Point(45.0, 0.0), k.positionOf("marine-5"))
        assertTrue(k.field(m, "marine-1", "squad_ready").jsonPrimitive.boolean)
        assertNull(k.vehicleOf("marine-1"))
    }

    @Test fun aDeadMarineIsNeverRepairedAndAnUndersizedSquadReleasesItsRover() {
        val m = squadManifest()
        val k = world(m)
        k.advance(0, m, requests = listOf(request("marine-1", 36.0)))
        val events = k.advance(1, m, listOf(damage("marine-2"), damage("marine-3")))
        assertEquals(2, events.count { it.type == "EntityDied" })
        assertNull(k.vehicleOf("marine-1"))
        k.advance(200, m)
        assertTrue(k.activeJobs.isEmpty())
    }

    @Test fun fullConfigurationSpellsOutEveryWorldParameterAndRunsAllKinds() {
        val path = Path.of("../examples/physics/full.json")
        val p = prepareScenario(path)
        val raw = bytecodeJson.parseToJsonElement(java.nio.file.Files.readString(path)).jsonObject.getValue("world")
        val complete = Json { encodeDefaults = true }.encodeToJsonElement(p.scenario.world!!)
        assertTrue(jsonEquivalent(complete, raw), "all default-valued fields must be explicit")
        assertEquals(setOf("House", "Heater", "Kettle", "Human", "Rover", "Marine", "Xenomorph"), p.manifest.instances.map { it.kind }.toSet())
        assertEquals(1228, p.manifest.instances.size)
        assertEquals(2, p.manifest.instances.count { it.behavior == "PassengerRover" })
        ReferenceRun(p, "full-smoke").use { run ->
            val snapshot = run.step()
            assertEquals(6, snapshot.entities.count { it.type == "rover" })
            assertEquals(10, snapshot.entities.count { it.type == "marine" })
        }
    }

    private fun residents(count: Int): RunManifest {
        val base = smallManifest()
        val person = base.instances.single { it.id == "person" }
        return base.copy(instances = base.instances.filter { it.id != "person" } +
            (1..count).map { person.copy(id = "walker-$it", x = 2.0 + 10.0 * (it - 1)) })
    }
    private fun walk(id: String, to: Point) = VmIntent(id, Op.MOTION_REQUEST,
        listOf(buildJsonObject { put("x", to.x); put("y", to.y) }, JsonPrimitive(1.4)))
    private fun seesRover(k: WorldKernel, m: RunManifest, id: String) =
        k.field(m, id, "available_vehicles").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "rover" }

    @Test fun seatsGoToTheNearestWalkersAndTheRoverWaitsForThem() {
        val m = residents(7)
        val k = world(m)
        val rover = k.positionOf("rover")
        assertTrue((1..7).all { seesRover(k, m, "walker-$it") }, "before anyone walks, every seat is free")
        k.advance(0, m, (1..7).map { walk("walker-$it", rover) })
        assertEquals((1..5).map { true } + listOf(false, false), (1..7).map { seesRover(k, m, "walker-$it") },
            "five seats go to the five nearest; the rest see no rover and walk instead")
        assertEquals(5, k.field(m, "rover", "boarding_pending").jsonPrimitive.int)

        val work = k.field(m, "walker-1", "workplace").jsonObject
        val destination = Point(work.getValue("x").jsonPrimitive.double, work.getValue("y").jsonPrimitive.double)
        k.advance(1, m, (2..5).map { walk("walker-$it", rover) }, listOf(TransportRequest("ride", "walker-1", "rover", destination)))
        assertEquals("rover", k.vehicleOf("walker-1"))
        assertFalse(k.field(m, "rover", "transport_ready").jsonPrimitive.boolean, "four more are on their way")
        assertTrue(seesRover(k, m, "walker-2"), "a rover going to their workplace still takes them")
        k.advance(2, m)
        assertTrue(k.field(m, "rover", "transport_ready").jsonPrimitive.boolean, "nobody is walking to it any more")
    }

    @Test fun aRoverDoesNotWaitForeverForResidentsWhoAreStillWalking() {
        val m = residents(2)
        val k = world(m, WorldConfig(transport = TransportConfig(boardingWaitSeconds = 3.0)))
        val rover = k.positionOf("rover")
        val work = k.field(m, "walker-1", "workplace").jsonObject
        val destination = Point(work.getValue("x").jsonPrimitive.double, work.getValue("y").jsonPrimitive.double)
        k.advance(0, m, listOf(walk("walker-2", rover)), listOf(TransportRequest("ride", "walker-1", "rover", destination)))
        for (tick in 1L..2L) {
            assertFalse(k.field(m, "rover", "transport_ready").jsonPrimitive.boolean)
            k.advance(tick, m, listOf(VmIntent("walker-2", Op.MOTION_REQUEST, listOf(buildJsonObject { put("x", rover.x); put("y", rover.y) }, JsonPrimitive(0.0)))))
        }
        assertTrue(k.field(m, "rover", "transport_ready").jsonPrimitive.boolean)
    }

    @Test fun aClosedFenceStopsAXenomorphUntilItBreaksTheSegmentInItsWay() {
        val base = smallManifest()
        val m = base.copy(instances = base.instances + Instance("alien", "Xenomorph", "Dummy", JsonObject(emptyMap()), JsonObject(emptyMap()), null, 0.0, 0.0))
        val k = world(m, WorldConfig(fence = FenceConfig(enabled = true, margin = 10.0, segmentLength = 20.0)))
        val box = assertNotNull(k.topology.fenceBox)
        assertFalse(box.contains(k.positionOf("alien")), "xenomorphs appear outside the fence")
        assertTrue(k.topology.fence.all { k.healthOf(it.id) == 400.0 })

        val house = VmIntent("alien", Op.MOTION_REQUEST, listOf(buildJsonObject { put("x", 0.0); put("y", 0.0) }, JsonPrimitive(1000.0)))
        k.advance(0, m, listOf(house))
        assertFalse(box.contains(k.positionOf("alien")), "the fence holds")
        val segment = k.field(m, "alien", "visible_infrastructure").jsonArray.first().jsonObject
        assertEquals("Fence", segment.getValue("kind").jsonPrimitive.content)
        assertTrue(segment.getValue("distance").jsonPrimitive.double <= 1.0, "it stands right at the segment it ran into")

        val id = segment.getValue("id").jsonPrimitive.content
        val events = k.advance(1, m, listOf(VmIntent("alien", Op.DAMAGE_REQUEST, listOf(JsonPrimitive(id), JsonPrimitive(1000.0), JsonPrimitive("test")))))
        assertTrue(events.any { it.type == "ObjectBroken" && it.entityId == id })
        k.advance(2 + ceil(WorldConfig().repair.dispatchDelaySeconds).toLong(), m, listOf(house))
        assertEquals(Point(0.0, 0.0), k.positionOf("alien"), "a broken segment lets it through")
        assertEquals("fence", k.activeJobs.single().kind)
    }

    @Test fun aSquadWhoseTargetDiesOnTheWayTakesTheNextOne() {
        val base = squadManifest()
        val m = base.copy(instances = base.instances + base.instances.single { it.id == "alien" }.copy(id = "alien-2", x = 60.0))
        val k = world(m)
        k.advance(0, m, requests = listOf(request("marine-1", 36.0)))
        k.advance(1, m, listOf(VmIntent("marine-1", Op.DAMAGE_REQUEST, listOf(JsonPrimitive("alien"), JsonPrimitive(100.0), JsonPrimitive("test")))))
        assertEquals("rover", k.vehicleOf("marine-1"), "the sortie goes on")
        assertEquals("alien-2", k.field(m, "marine-1", "visible_xenomorphs").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    private fun point(value: JsonElement) = value.jsonObject.let { Point(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double) }

    @Test fun aSquadThatGathersAtItsTargetDrivesItOff() {
        val m = squadManifest()
        val k = world(m)
        k.advance(0, m, requests = (1..5).map { request("marine-$it", 36.0) })
        for (tick in 1L..4L) k.advance(tick, m, listOf(motion("rover", 36.0)))
        assertTrue(k.field(m, "marine-1", "squad_ready").jsonPrimitive.boolean)
        assertFalse(k.field(m, "alien", "routed").jsonPrimitive.boolean)
        k.advance(5, m)
        assertTrue(k.field(m, "alien", "routed").jsonPrimitive.boolean, "gathering routs it, whatever the shot does")
        assertTrue(k.field(m, "marine-1", "visible_xenomorphs").jsonArray.isEmpty(), "a routed xenomorph is no longer a target")
        repeat(WorldConfig().marine.routSeconds.toInt()) { k.advance(6L + it, m, (1..5).map { index -> motion("marine-$index", 0.0, 1.5) }) }
        assertFalse(k.field(m, "alien", "routed").jsonPrimitive.boolean, "once the squad has gone home, it recovers after its time")
    }

    @Test fun behindAFenceSquadsHuntIntrudersAndDriveThemOutThroughABreach() {
        val base = squadManifest()
        val m = base.copy(instances = base.instances.map { if (it.kind == "Marine") it.copy(x = -30.0, y = -30.0) else it })
        val k = world(m, WorldConfig(fence = FenceConfig(enabled = true, margin = 10.0, segmentLength = 20.0)))
        val box = assertNotNull(k.topology.fenceBox)
        assertTrue(k.field(m, "marine-1", "visible_xenomorphs").jsonArray.isEmpty(), "what roams outside is not a target")

        val inside = VmIntent("alien", Op.MOTION_REQUEST, listOf(buildJsonObject { put("x", -30.0); put("y", -30.0) }, JsonPrimitive(1000.0)))
        k.advance(0, m, listOf(inside))
        val segment = k.field(m, "alien", "visible_infrastructure").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        k.advance(1, m, listOf(VmIntent("alien", Op.DAMAGE_REQUEST, listOf(JsonPrimitive(segment), JsonPrimitive(1000.0), JsonPrimitive("test")))))
        k.advance(2, m, listOf(inside))
        assertTrue(box.contains(k.positionOf("alien")))
        assertEquals("alien", k.field(m, "marine-1", "visible_xenomorphs").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)

        k.advance(3, m)
        assertTrue(k.field(m, "alien", "routed").jsonPrimitive.boolean)
        var tick = 4L
        while (box.contains(k.positionOf("alien")) && tick < 60) {
            val exit = point(k.field(m, "alien", "patrol_waypoint"))
            k.advance(tick++, m, listOf(VmIntent("alien", Op.MOTION_REQUEST, listOf(buildJsonObject { put("x", exit.x); put("y", exit.y) }, JsonPrimitive(100.0)))))
        }
        assertFalse(box.contains(k.positionOf("alien")), "it leaves through the breach it came in by")
        assertTrue(k.field(m, "alien", "routed").jsonPrimitive.boolean, "and keeps away for a while")
    }
}
