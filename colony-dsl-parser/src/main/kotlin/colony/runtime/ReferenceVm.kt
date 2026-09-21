package colony.runtime

import colony.bytecode.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class DeliveredEvent(val eventId: Int, val fields: JsonObject, val sender: String = "scenario", val sequence: Long = 0)
data class OutgoingEvent(val target: String, val eventId: Int, val fields: JsonObject, val sender: String, val sequence: Long)
data class VmIntent(val source: String, val operation: Op, val arguments: List<JsonElement>)
data class VmFrame(val tick: Long, val view: JsonObject, val events: List<DeliveredEvent> = emptyList())
data class VmResult(val intents: List<VmIntent>, val events: List<OutgoingEvent>, val state: JsonObject)

/** Temporary, in-process reference for the future native VM. It executes the actual stack artifact. */
class ReferenceVm(
    val entityId: String,
    private val program: BytecodeProgram,
    behaviorName: String,
    parameters: JsonObject = JsonObject(emptyMap()),
    private val seed: Long = 426,
    private val instructionBudget: Int = 100_000,
) {
    val behavior: BehaviorCode = program.behaviors.single { it.name == behaviorName }
    private val params = behavior.params.map { slot -> parameters[slot.name] ?: error("Missing parameter ${slot.name} for $entityId") }
    private val state = MutableList<JsonElement?>(behavior.state.size) { null }
    private val counters = linkedMapOf<Pair<String, String>, Long>()
    private var sequence = 0L
    private var remaining = instructionBudget
    val randomDrawCount: Long get() = counters.values.sum()

    init {
        BytecodeVerifier.verify(program)
        require(parameters.keys == behavior.params.map { it.name }.toSet()) { "Parameter names do not match ${behavior.name}" }
        val initialized = mutableListOf<VmIntent>()
        val events = mutableListOf<OutgoingEvent>()
        execute(behavior.initialize, 0, "__init__", VmFrame(0, JsonObject(emptyMap())), JsonObject(emptyMap()), initialized, events)
        require(initialized.isEmpty() && events.isEmpty()) { "Initialization must not produce effects" }
        require(state.none { it == null }) { "Uninitialized state in ${behavior.name}" }
    }

    fun stateSnapshot(): JsonObject = JsonObject(behavior.state.mapIndexed { index, slot -> slot.name to (state[index] ?: error("Uninitialized state")) }.toMap())

    fun step(frame: VmFrame): VmResult {
        require(frame.tick >= 0) { "Negative tick" }
        val previousState = state.toList()
        val previousCounters = counters.toMap()
        val previousSequence = sequence
        val intents = mutableListOf<VmIntent>()
        val outgoing = mutableListOf<OutgoingEvent>()
        remaining = instructionBudget
        try {
            for (event in frame.events.sortedWith(compareBy({ it.sender }, { it.sequence }))) {
                for (handler in behavior.handlers.filter { it.eventId == event.eventId }) {
                    execute(handler.entry, handler.localCount, handler.name, frame, event.fields, intents, outgoing)
                }
            }
            for (handler in behavior.handlers) {
                if (handler.periodTicks != null && frame.tick % handler.periodTicks == 0L) {
                    execute(handler.entry, handler.localCount, handler.name, frame, JsonObject(emptyMap()), intents, outgoing)
                }
            }
            return VmResult(intents.toList(), outgoing.toList(), stateSnapshot())
        } catch (failure: Exception) {
            previousState.forEachIndexed { index, value -> state[index] = value }
            counters.clear(); counters.putAll(previousCounters); sequence = previousSequence
            throw failure
        }
    }

    private fun execute(entry: Int, localCount: Int, rule: String, frame: VmFrame, message: JsonObject,
                        intents: MutableList<VmIntent>, outgoing: MutableList<OutgoingEvent>) {
        val stack = mutableListOf<JsonElement>()
        val temps = arrayOfNulls<JsonElement>(behavior.temporaryCount)
        val locals = arrayOfNulls<JsonElement>(localCount)
        fun pop(): JsonElement = stack.removeLastOrNull() ?: error("Operand stack underflow")
        fun arguments(count: Int): List<JsonElement> = List(count) { pop() }.reversed()
        fun record(names: List<String>): JsonObject = JsonObject(names.zip(arguments(names.size)).toMap())
        var pc = entry
        try {
            while (true) {
                check(--remaining >= 0) { "Instruction budget exceeded" }
                check(stack.size <= 4096) { "Operand stack limit exceeded" }
                check(intents.size + outgoing.size <= 1024) { "Output limit exceeded" }
                val instruction = behavior.code[pc]
                var next = pc + 1
                when (instruction.op) {
                    Op.CONST -> stack += instruction.value
                    Op.LOAD_TEMP -> stack += temps[instruction.arg] ?: error("Uninitialized temporary")
                    Op.STORE_TEMP -> temps[instruction.arg] = pop()
                    Op.LOAD_PARAM -> stack += params[instruction.arg]
                    Op.LOAD_STATE -> stack += state[instruction.arg] ?: error("Uninitialized state")
                    Op.STORE_STATE -> state[instruction.arg] = pop()
                    Op.LOAD_LOCAL -> stack += locals[instruction.arg] ?: error("Uninitialized local")
                    Op.STORE_LOCAL -> locals[instruction.arg] = pop()
                    Op.LOAD_MESSAGE -> stack += message
                    Op.LOAD_VIEW -> stack += frame.view[instruction.text] ?: error("Missing observation ${instruction.text}")
                    Op.LOAD_OBSERVATIONS -> stack += frame.view
                    Op.LOAD_TIME -> stack += finite(frame.tick * program.stepSeconds.toDouble())
                    Op.LOAD_SELF -> stack += JsonPrimitive(entityId)
                    Op.GET_FIELD -> stack += pop().jsonObject[instruction.text] ?: error("Missing field ${instruction.text}")
                    Op.CONVERT -> stack += convert(pop(), instruction.type)
                    Op.UNARY -> {
                        val operand = pop()
                        stack += when (instruction.text) {
                            "NOT" -> JsonPrimitive(!operand.jsonPrimitive.boolean)
                            "PLUS" -> operand
                            "MINUS" -> if (instruction.type == "Int64" || instruction.type == "Money") JsonPrimitive(Math.negateExact(operand.jsonPrimitive.long)) else finite(-number(operand))
                            else -> error("Unknown unary operator")
                        }
                    }
                    Op.BINARY -> { val right = pop(); val left = pop(); stack += binary(instruction.text, instruction.type, left, right) }
                    Op.MAKE_RECORD -> stack += record(instruction.names)
                    Op.SOME -> stack += buildJsonObject { put("some", pop()) }
                    Op.IS_SOME -> stack += JsonPrimitive(pop() != JsonNull)
                    Op.UNWRAP -> stack += pop().jsonObject["some"] ?: error("Unwrap of none")
                    Op.INDEX -> { val index = pop().jsonPrimitive.int; stack += pop().jsonArray[index] }
                    Op.CLAMP -> { val args = arguments(3); val lo = number(args[1]); val hi = number(args[2]); require(lo <= hi); stack += if (number(args[0]) < lo) args[1] else if (number(args[0]) > hi) args[2] else args[0] }
                    Op.NEAREST -> {
                        val candidates = pop().jsonArray
                        require(candidates.size <= 4096) { "Observation list limit exceeded" }
                        val nearest = candidates.minWithOrNull(compareBy<JsonElement>({ number(it.jsonObject.getValue("distance")) }, { it.jsonObject.getValue("id").jsonPrimitive.content }))
                        stack += if (nearest == null) JsonNull else buildJsonObject { put("some", nearest) }
                    }
                    Op.CHANCE, Op.HAZARD -> {
                        val site = pop().jsonPrimitive.content
                        val parameter = number(pop())
                        val probability = if (instruction.op == Op.HAZARD) {
                            require(parameter >= 0); -Math.expm1(-parameter * program.stepSeconds.toDouble())
                        } else parameter
                        require(probability in 0.0..1.0) { "Probability outside [0,1]" }
                        stack += JsonPrimitive(random(rule, site) < probability)
                    }
                    Op.POWER_REQUEST, Op.DAMAGE_REQUEST, Op.MOTION_REQUEST, Op.REPAIR_REQUEST -> {
                        intents += VmIntent(entityId, instruction.op, arguments(instruction.stackEffect().first))
                    }
                    Op.SEND -> {
                        val fields = record(instruction.names)
                        outgoing += OutgoingEvent(pop().jsonPrimitive.content, instruction.arg, fields, entityId, sequence++)
                    }
                    Op.JUMP -> next = instruction.arg
                    Op.JUMP_IF_FALSE -> if (!pop().jsonPrimitive.boolean) next = instruction.arg
                    Op.RETURN -> { check(stack.isEmpty()); return }
                }
                pc = next
            }
        } catch (failure: Exception) {
            val instruction = behavior.code.getOrNull(pc)
            throw IllegalStateException("VM $entityId, tick ${frame.tick}, rule $rule, pc $pc, ${instruction?.line}:${instruction?.column}: ${failure.message}", failure)
        }
    }

    /** The next number of the stream of one (rule, site) of this entity; every draw advances only its own counter. */
    private fun random(rule: String, site: String): Double {
        val key = rule to site
        val counter = counters.getOrDefault(key, 0L)
        counters[key] = counter + 1
        return randomUnit(seed, entityId, behavior.name, rule, site, counter)
    }
}

/** The bytes hashed for one draw: seed, four length-prefixed UTF-8 keys, counter; big-endian, as documented. */
internal fun randomInput(seed: Long, entityId: String, behavior: String, rule: String, site: String, counter: Long): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeLong(seed)
        for (value in listOf(entityId, behavior, rule, site)) {
            val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded)
        }
        output.writeLong(counter)
    }
    return bytes.toByteArray()
}

/** The first eight bytes of the SHA-256 digest as a big-endian long. */
internal fun randomDigestHead(input: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(input)).long

/** SHA-256 counter stream: the top 53 bits of the digest head divided by 2^53, a number in [0, 1). */
internal fun randomUnit(seed: Long, entityId: String, behavior: String, rule: String, site: String, counter: Long): Double =
    (randomDigestHead(randomInput(seed, entityId, behavior, rule, site, counter)) ushr 11).toDouble() / 9007199254740992.0

internal fun number(value: JsonElement): Double {
    val primitive = value.jsonPrimitive
    require(!primitive.isString) { "Expected a number" }
    return primitive.double.also { require(it.isFinite()) { "Non-finite number" } }
}
private fun finite(value: Double): JsonPrimitive { require(value.isFinite()) { "Non-finite result" }; return JsonPrimitive(value) }
private fun convert(value: JsonElement, type: String): JsonElement = when {
    type == "Real64" || type == "Probability" -> finite(number(value))
    type.startsWith("Option<") && value != JsonNull -> buildJsonObject { put("some", convert(value.jsonObject.getValue("some"), type.removePrefix("Option<").dropLast(1))) }
    else -> value
}

private fun binary(operation: String, type: String, left: JsonElement, right: JsonElement): JsonElement {
    fun decimal(value: JsonElement) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
    val a = decimal(left); val b = decimal(right)
    if (operation == "EQ" || operation == "NEQ") {
        val equal = if (a != null && b != null) a.compareTo(b) == 0 else left == right
        return JsonPrimitive(if (operation == "EQ") equal else !equal)
    }
    if (operation in setOf("LT", "LE", "GT", "GE")) {
        val comparison = requireNotNull(a).compareTo(requireNotNull(b))
        return JsonPrimitive(when (operation) { "LT" -> comparison < 0; "LE" -> comparison <= 0; "GT" -> comparison > 0; else -> comparison >= 0 })
    }
    if (type == "Int64" || type == "Money") {
        val l = left.jsonPrimitive.long; val r = right.jsonPrimitive.long
        return JsonPrimitive(when (operation) { "ADD" -> Math.addExact(l, r); "SUB" -> Math.subtractExact(l, r); "MUL" -> Math.multiplyExact(l, r); "MOD" -> l % r; else -> error("Invalid integer operation") })
    }
    val l = number(left); val r = number(right)
    return finite(when (operation) { "ADD" -> l + r; "SUB" -> l - r; "MUL" -> l * r; "DIV" -> { require(r != 0.0) { "Division by zero" }; l / r }; else -> error("Invalid arithmetic operation $operation") })
}
