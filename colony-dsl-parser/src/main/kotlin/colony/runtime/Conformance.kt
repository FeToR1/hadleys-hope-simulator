package colony.runtime

import colony.bytecode.*
import colony.semantics.CONTRACT_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * Cross-implementation test vectors for the stack VM (see conformance/README.md). The reference VM produces them;
 * another VM, for example the native one, must reproduce every step exactly. Programs, frames and expected results
 * are data, so nothing here has to be ported.
 */

@Serializable data class ConformanceEvent(val eventId: Int, val sender: String, val sequence: Long, val fields: JsonObject)
@Serializable data class ConformanceIntent(val operation: String, val arguments: List<JsonElement>)
@Serializable data class ConformanceOutgoing(val target: String, val eventId: Int, val sequence: Long, val fields: JsonObject)
@Serializable data class ConformanceExpectation(
    /** The step must fail and leave state, random counters and outgoing sequence exactly as they were. */
    val failure: Boolean = false,
    val intents: List<ConformanceIntent> = emptyList(),
    val events: List<ConformanceOutgoing> = emptyList(),
    val state: JsonObject,
)
@Serializable data class ConformanceStep(val tick: Long, val view: JsonObject, val events: List<ConformanceEvent>, val expect: ConformanceExpectation)
@Serializable data class ConformanceCase(
    val name: String, val behavior: String, val entityId: String, val seed: Long, val params: JsonObject,
    /** State right after the initializers ran. */
    val initialState: JsonObject, val steps: List<ConformanceStep>,
)
@Serializable data class ConformanceFile(
    val conformance: Int, val contract: Int, val name: String, val source: String, val program: BytecodeProgram, val cases: List<ConformanceCase>,
)

@Serializable data class PrngVector(
    val seed: Long, val entityId: String, val behavior: String, val rule: String, val site: String, val counter: Long,
    /** The hashed bytes, hex. */
    val input: String,
    /** First eight bytes of the SHA-256 digest, hex. */
    val digestHead: String,
    /** Top 53 bits of that as a decimal integer. */
    val bits53: String,
    /** bits53 / 2^53 as the shortest decimal, and the exact IEEE-754 bit pattern of the result, hex. */
    val unit: String, val unitBits: String,
)
@Serializable data class PrngFile(val conformance: Int, val description: String, val vectors: List<PrngVector>)

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

internal fun prngVectors(): List<PrngVector> {
    val seeds = listOf(426L, 0L, -1L, Long.MAX_VALUE)
    val keys = listOf(
        Triple("home-1/heater", "HeaterControl", "apply"),
        Triple("alien-3/xenomorph", "Xenomorph", "decide"),
        Triple("дом-7/чайник", "ЧайникУправление", "нагрев"), // non-ASCII keys pin down UTF-8 and the length prefix
    )
    val sites = listOf("strike", "", "vandalism")
    val counters = listOf(0L, 1L, 2L, 255L, 1L shl 40)
    return seeds.flatMap { seed ->
        keys.flatMap { (entity, behavior, rule) ->
            sites.flatMap { site ->
                counters.map { counter ->
                    val input = randomInput(seed, entity, behavior, rule, site, counter)
                    val head = randomDigestHead(input)
                    val unit = randomUnit(seed, entity, behavior, rule, site, counter)
                    PrngVector(seed, entity, behavior, rule, site, counter, hex(input), "%016x".format(head),
                        (head ushr 11).toString(), unit.toString(), "%016x".format(java.lang.Double.doubleToLongBits(unit)))
                }
            }
        }
    }
}

private data class CaseInput(
    val name: String, val behavior: String, val entityId: String, val frames: List<VmFrame>,
    val params: JsonObject = JsonObject(emptyMap()), val seed: Long = 426,
)

private class ProgramSpec(val name: String, val source: String, val cases: (BytecodeProgram) -> List<CaseInput>)

private fun point(x: Number, y: Number) = buildJsonObject { put("x", x); put("y", y) }
private fun target(id: String, kind: String, x: Number, y: Number, distance: Number, health: Number = 100) = buildJsonObject {
    put("id", id); put("kind", kind); put("position", point(x, y)); put("distance", distance); put("health", health)
}
private fun frame(tick: Long, view: JsonObject, vararg events: DeliveredEvent) = VmFrame(tick, view, events.toList())
private fun houseView(occupants: Int, temperature: Number = 20) = buildJsonObject { put("occupants", occupants); put("temperature", temperature) }
private fun eventId(program: BytecodeProgram, name: String) = program.events.single { it.name == name }.id

private val PROGRAMS: List<ProgramSpec> = listOf(
    ProgramSpec("heater", "colony-dsl-parser/examples/heater.colony") { program ->
        val demand = eventId(program, "HeatingDemand")
        val readings = listOf(1 to 16, 1 to 17, 1 to 19, 1 to 22, 0 to 22, 0 to 15, 1 to 15, 1 to 17.9, 1 to 18, 1 to 20.5, 1 to 21, 1 to 20)
        val heaterView = { occupants: Int, broken: Boolean, connected: Boolean ->
            buildJsonObject { put("home_occupants", occupants); put("broken", broken); put("power_connected", connected) }
        }
        fun ask(enabled: Boolean) = buildJsonObject { put("enabled", enabled) }
        listOf(
            CaseInput("house-hysteresis", "HouseControl", "home-1/house",
                readings.mapIndexed { tick, (occupants, temperature) -> frame(tick.toLong(), houseView(occupants, temperature)) },
                params = buildJsonObject { put("heater", "home-1/heater") }),
            CaseInput("heater-follows-demand", "HeaterControl", "home-1/heater", listOf(
                frame(0, heaterView(1, false, true)),
                frame(1, heaterView(1, false, true), DeliveredEvent(demand, ask(true), "home-1/house", 0)),
                frame(2, heaterView(1, false, true)),
                frame(3, heaterView(1, true, true)),
                frame(4, heaterView(1, false, false)),
                frame(5, heaterView(0, false, true)),
                frame(6, heaterView(1, false, true), DeliveredEvent(demand, ask(false), "home-1/house", 1)),
                frame(7, heaterView(1, false, true), DeliveredEvent(demand, ask(true), "home-1/house", 2), DeliveredEvent(demand, ask(false), "home-0/house", 9)),
            )),
        )
    },
    ProgramSpec("resident", "colony-dsl-parser/examples/resident.colony") { program ->
        val lost = eventId(program, "PowerLost")
        val breakable = target("home-1/heater", "Heater", 10, 10, 4)
        val frames = (0L..125L).map { tick ->
            val cold = tick < 60 || tick >= 120
            val view = buildJsonObject { put("cold", cold); put("reachable_breakables", buildJsonArray { add(breakable) }) }
            // Five outages in a row push the stress over the threshold before the first reconsideration at tick 60.
            if (tick in 1L..5L) frame(tick, view, DeliveredEvent(lost, JsonObject(emptyMap()), "world", tick)) else frame(tick, view)
        }
        // Seed 1 vandalizes at step 60, seed 6 at step 120 and seed 2 never: both outcomes of the draw are covered.
        listOf(1L, 2L, 6L).map { seed -> CaseInput("resident-seed-$seed", "Resident", "home-1/resident", frames, seed = seed) }
    },
    ProgramSpec("xenomorph", "colony-dsl-parser/examples/xenomorph.colony") { _ ->
        val waypoint = point(400, 300)
        val frames = (0L until 30L).map { tick ->
            val list = when {
                tick < 10 -> listOf(target("home-1/heater", "Heater", 10, 10, 10))
                tick < 20 -> listOf(target("home-1/heater", "Heater", 10, 10, 1.5), target("home-2/heater", "Heater", 80, 10, 70))
                else -> emptyList()
            }
            frame(tick, buildJsonObject {
                put("position", point(0, 0)); put("patrol_waypoint", waypoint); put("visible_infrastructure", buildJsonArray { list.forEach { add(it) } })
            })
        }
        listOf(426L, 7L).map { seed -> CaseInput("hunt-and-patrol-seed-$seed", "Xenomorph", "alien-1/xenomorph", frames, seed = seed) }
    },
    ProgramSpec("kettle", "examples/integration/devices.colony") { _ ->
        val kettleView = { water: Number, broken: Boolean, connected: Boolean ->
            buildJsonObject { put("water_temperature", water); put("broken", broken); put("power_connected", connected) }
        }
        listOf(CaseInput("heats-until-boiling", "KettleControl", "home-1/kettle", listOf(
            frame(0, kettleView(20, false, true)), frame(1, kettleView(90, false, true)), frame(2, kettleView(94.9, false, true)),
            frame(3, kettleView(95, false, true)), frame(4, kettleView(96, false, true)), frame(5, kettleView(20, true, true)),
            frame(6, kettleView(20, false, false)),
        )))
    },
)

private val LANGUAGE = ProgramSpec("language", "conformance/programs/language.colony") { program ->
    val report = eventId(program, "Report")
    val value = eventId(program, "Value")
    fun some(number: Double) = buildJsonObject { put("v", buildJsonObject { put("some", number) }) }
    val none = buildJsonObject { put("v", JsonNull) }
    fun valueOf(n: Int) = buildJsonObject { put("n", n) }
    val infrastructure = { list: List<JsonObject>, x: Int ->
        buildJsonObject { put("position", point(x, 0)); put("patrol_waypoint", point(0, 0)); put("visible_infrastructure", buildJsonArray { list.forEach { add(it) } }) }
    }
    listOf(
        CaseInput("numbers", "Numbers", "home-1/house", (0L..5L).map { frame(it, houseView(it.toInt())) }),
        CaseInput("short-circuit", "ShortCircuit", "home-1/house", (0L until 40L).map { frame(it, houseView(if (it % 3 == 0L) 0 else 1)) }),
        CaseInput("branches", "Branches", "alien-1/xenomorph", listOf(
            frame(0, infrastructure(listOf(target("a", "Heater", 0, 0, 5)), 0)),
            frame(1, infrastructure(listOf(target("b", "Heater", 0, 0, 5)), 0)),
            frame(2, infrastructure(listOf(target("c", "Heater", 0, 0, 5)), 0)),
            frame(3, infrastructure(listOf(target("a", "Heater", 0, 0, 1)), 0)),
            frame(4, infrastructure(emptyList(), 9)),
            frame(5, infrastructure(emptyList(), 1)),
            frame(6, infrastructure(listOf(target("z", "Heater", 0, 0, 2)), 0)),
            frame(7, infrastructure(listOf(target("b", "Heater", 0, 0, 3), target("c", "Heater", 0, 0, 1)), 0)),
        )),
        CaseInput("sender", "Sender", "home-1/house", (0L..2L).map { frame(it, houseView(4 + it.toInt())) },
            params = buildJsonObject { put("peer", "home-2/house") }),
        CaseInput("receiver", "Receiver", "home-2/house", listOf(
            frame(0, houseView(1)),
            frame(1, houseView(1), DeliveredEvent(report, some(1.5), "home-1/house", 0), DeliveredEvent(report, none, "home-1/house", 1), DeliveredEvent(value, valueOf(4), "home-1/house", 2)),
            // Delivered out of order and from two senders: the VM must sort by (sender, sequence) itself.
            frame(2, houseView(1), DeliveredEvent(value, valueOf(5), "home-1/house", 5), DeliveredEvent(report, none, "home-1/house", 4),
                DeliveredEvent(report, some(1.5), "home-1/house", 3), DeliveredEvent(value, valueOf(99), "home-0/house", 0)),
            frame(3, houseView(1)),
        )),
        CaseInput("timers", "Timers", "home-1/house", (0L..125L).map { frame(it, houseView(1)) }),
        CaseInput("durations", "Durations", "home-1/resident", (0L..35L).map { frame(it, JsonObject(emptyMap())) }),
        CaseInput("rollback", "Rollback", "home-1/house", listOf(1, 1, 0, 1, 0, 2).mapIndexed { tick, occupants -> frame(tick.toLong(), houseView(occupants)) }, seed = 11),
    )
}

private val ALL_PROGRAMS = PROGRAMS + LANGUAGE

private fun expectationOf(vm: ReferenceVm, frame: VmFrame): ConformanceExpectation = try {
    val result = vm.step(frame)
    ConformanceExpectation(false, result.intents.map { ConformanceIntent(it.operation.name, it.arguments) },
        result.events.map { ConformanceOutgoing(it.target, it.eventId, it.sequence, it.fields) }, result.state)
} catch (_: Exception) {
    ConformanceExpectation(failure = true, state = vm.stateSnapshot()) // a failed step leaves everything as it was
}

/** Runs every case of one program on the reference VM and records what it does. */
private fun buildConformance(root: Path, spec: ProgramSpec): ConformanceFile {
    val program = compileSources(listOf(SourceFile(spec.source, root.resolve(spec.source).readText())))
    val cases = spec.cases(program).map { input ->
        val vm = ReferenceVm(input.entityId, program, input.behavior, input.params, input.seed)
        val initial = vm.stateSnapshot()
        val steps = input.frames.map { frame ->
            ConformanceStep(frame.tick, frame.view, frame.events.map { ConformanceEvent(it.eventId, it.sender, it.sequence, it.fields) }, expectationOf(vm, frame))
        }
        ConformanceCase(input.name, input.behavior, input.entityId, input.seed, input.params, initial, steps)
    }
    return ConformanceFile(1, CONTRACT_VERSION, spec.name, spec.source, program, cases)
}

private const val PRNG_DESCRIPTION =
    "One draw hashes: seed as 8 bytes big-endian, then entityId, behavior, rule and site as UTF-8 strings each preceded by " +
    "its byte length as 4 bytes big-endian, then the counter as 8 bytes big-endian. The counter of a (rule, site) pair of one " +
    "entity starts at 0 and advances by one per draw. The result is the first 8 bytes of SHA-256 as an unsigned big-endian " +
    "integer shifted right by 11 (53 bits), divided by 2^53. chance(p) is true when the result is below p."

/** Files produced: one per program plus prng.json. Sources are read relative to the repository root. */
fun writeConformance(root: Path, output: Path) {
    output.createDirectories()
    for (spec in ALL_PROGRAMS) output.resolve("${spec.name}.json").writeText(bytecodeJson.encodeToString(buildConformance(root, spec)) + "\n")
    output.resolve("prng.json").writeText(bytecodeJson.encodeToString(PrngFile(1, PRNG_DESCRIPTION, prngVectors())) + "\n")
}

/** Names of the vector files writeConformance produces. */
fun conformanceFileNames(): List<String> = ALL_PROGRAMS.map { "${it.name}.json" } + "prng.json"

/** Equality of JSON values as a VM sees them: numbers by value (2 and 2.0 agree), objects without regard to key order. */
internal fun jsonEquivalent(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> jsonEquivalent(value, b.getValue(key)) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquivalent(a[it], b[it]) }
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString || b.isString -> a.isString && b.isString && a.content == b.content
        a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
        else -> a.content.toBigDecimalOrNull().let { x -> x != null && b.content.toBigDecimalOrNull()?.let { y -> x.compareTo(y) == 0 } == true }
    }
    else -> false
}

/** Replays a vector file on the reference VM; one message per mismatch, none when the VM conforms. */
fun verifyConformance(file: ConformanceFile): List<String> {
    val problems = mutableListOf<String>()
    for (case in file.cases) {
        val vm = ReferenceVm(case.entityId, file.program, case.behavior, case.params, case.seed)
        if (!jsonEquivalent(vm.stateSnapshot(), case.initialState)) problems += "${file.name}/${case.name}: state after initialization differs"
        for (step in case.steps) {
            val frame = VmFrame(step.tick, step.view, step.events.map { DeliveredEvent(it.eventId, it.fields, it.sender, it.sequence) })
            val actual = bytecodeJson.encodeToJsonElement(ConformanceExpectation.serializer(), expectationOf(vm, frame))
            val expected = bytecodeJson.encodeToJsonElement(ConformanceExpectation.serializer(), step.expect)
            if (!jsonEquivalent(actual, expected)) problems += "${file.name}/${case.name} tick ${step.tick}: expected $expected but got $actual"
        }
    }
    return problems
}

/** Replays the random number vectors; one message per mismatch. */
fun verifyPrng(file: PrngFile): List<String> = file.vectors.mapNotNull { v ->
    val input = randomInput(v.seed, v.entityId, v.behavior, v.rule, v.site, v.counter)
    val head = randomDigestHead(input)
    val unit = randomUnit(v.seed, v.entityId, v.behavior, v.rule, v.site, v.counter)
    if (hex(input) == v.input && "%016x".format(head) == v.digestHead && (head ushr 11).toString() == v.bits53 &&
        unit.toString() == v.unit && "%016x".format(java.lang.Double.doubleToLongBits(unit)) == v.unitBits) null
    else "prng seed=${v.seed} entity=${v.entityId} site='${v.site}' counter=${v.counter}: differs"
}
