package colony.runtime

import colony.bytecode.*
import colony.semantics.SemanticEnvironment
import colony.semantics.render
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.readText

@Serializable data class Catalog(val version: Int = 1, val sources: List<String>, val templates: Map<String, Template>)
@Serializable data class Template(val objects: Map<String, ObjectSpec>)
@Serializable data class ObjectSpec(
    val kind: String, val behavior: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val view: JsonObject = JsonObject(emptyMap()),
    val parent: String? = null,
    val x: Double = 0.0, val y: Double = 0.0,
)
@Serializable data class Population(val template: String, val count: Int, val prefix: String)
@Serializable data class ObservationChange(val tick: Long, val target: String, val view: JsonObject)
@Serializable data class Scenario(
    val version: Int = 1, val catalog: String, val seed: Long = 426, val step: String = "1s",
    val ticks: Int = 120, val populations: List<Population>,
    val changes: List<ObservationChange> = emptyList(),
)
@Serializable data class Instance(
    val id: String, val kind: String, val behavior: String, val params: JsonObject,
    val view: JsonObject, val parent: String?, val x: Double, val y: Double,
)
@Serializable data class RunManifest(val version: Int = 1, val seed: Long, val stepSeconds: String, val instances: List<Instance>)
data class PreparedRun(val scenario: Scenario, val program: BytecodeProgram, val manifest: RunManifest)

fun prepareScenario(path: Path): PreparedRun {
    val absolute = path.toAbsolutePath().normalize()
    val scenario = bytecodeJson.decodeFromString<Scenario>(absolute.readText())
    val catalogPath = absolute.parent.resolve(scenario.catalog).normalize()
    val catalog = bytecodeJson.decodeFromString<Catalog>(catalogPath.readText())
    require(scenario.version == 1 && catalog.version == 1) { "Unsupported scenario/catalog version" }
    require(scenario.ticks in 1..1_000_000) { "ticks must be in 1..1000000" }
    require(catalog.sources.isNotEmpty() && catalog.sources.distinct().size == catalog.sources.size) { "Sources must be nonempty and unique" }
    // Concatenation is one compilation package; a separating newline also terminates // comments.
    val source = catalog.sources.joinToString("\n") { catalogPath.parent.resolve(it).readText() }
    val program = compileSource(source, scenario.step)
    return expandScenario(scenario, catalog, program)
}

fun expandScenario(scenario: Scenario, catalog: Catalog, program: BytecodeProgram): PreparedRun {
    val instances = mutableListOf<Instance>()
    val identifier = Regex("[A-Za-z][A-Za-z0-9_-]*")
    require(scenario.populations.map { it.prefix }.distinct().size == scenario.populations.size) { "Duplicate population prefix" }
    var groupIndex = 0
    for (population in scenario.populations) {
        require(population.count in 0..10_000 && identifier.matches(population.prefix)) { "Invalid population count/prefix" }
        val template = catalog.templates[population.template] ?: error("Unknown template ${population.template}")
        require(template.objects.isNotEmpty()) { "Empty template ${population.template}" }
        require(instances.size.toLong() + population.count.toLong() * template.objects.size <= 10_000) { "Reference limit: 10000 instances" }
        repeat(population.count) { index ->
            val group = "${population.prefix}-${index + 1}"
            fun resolve(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.mapValues { resolve(it.value) })
                is JsonArray -> JsonArray(value.map(::resolve))
                is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.replace("\$instance", group)) else value
            }
            for ((role, spec) in template.objects.toSortedMap()) {
                require(identifier.matches(role)) { "Invalid object role $role" }
                val behavior = program.behaviors.find { it.name == spec.behavior } ?: error("Unknown behavior ${spec.behavior}")
                require(behavior.kind == spec.kind) { "${spec.behavior} requires ${behavior.kind}, got ${spec.kind}" }
                require(spec.x.isFinite() && spec.y.isFinite()) { "Nonfinite coordinates" }
                instances += Instance("$group/$role", spec.kind, spec.behavior, resolve(spec.params).jsonObject,
                    resolve(spec.view).jsonObject, spec.parent?.replace("\$instance", group),
                    (groupIndex % 20) * 70.0 + spec.x, (groupIndex / 20) * 70.0 + spec.y)
            }
            groupIndex++
        }
    }
    require(instances.isNotEmpty()) { "Scenario contains no objects" }
    val byId = instances.associateBy { it.id }
    require(byId.size == instances.size) { "Duplicate entity ID" }
    for (instance in instances) {
        val behavior = program.behaviors.single { it.name == instance.behavior }
        require(instance.params.keys == behavior.params.map { it.name }.toSet()) { "${instance.id}: parameter names mismatch" }
        behavior.params.forEach { validateValue(instance.params.getValue(it.name), it.type, byId, "${instance.id}.${it.name}") }
        val fields = SemanticEnvironment().kindContract(instance.kind)!!.viewFields
        require(instance.view.keys == fields.keys) { "${instance.id}: expected observations ${fields.keys}, got ${instance.view.keys}" }
        fields.forEach { (name, type) -> validateValue(instance.view.getValue(name), type.render(), byId, "${instance.id}.view.$name") }
        instance.parent?.let { require(it != instance.id && byId[it]?.kind == "House") { "${instance.id}: parent must reference another House" } }
    }
    for (change in scenario.changes) {
        require(change.tick in 0 until scenario.ticks.toLong()) { "Observation change outside run" }
        val instance = byId[change.target] ?: error("Unknown change target ${change.target}")
        val fields = SemanticEnvironment().kindContract(instance.kind)!!.viewFields
        change.view.forEach { (name, value) ->
            validateValue(value, fields[name]?.render() ?: error("Unknown observation $name"), byId, "${change.target}.$name")
        }
    }
    return PreparedRun(scenario, program, RunManifest(seed = scenario.seed, stepSeconds = program.stepSeconds, instances = instances.sortedBy { it.id }))
}

/** Config values use canonical units (W, seconds, metres, litres, degC), never unit strings. */
internal fun validateValue(value: JsonElement, type: String, instances: Map<String, Instance>, location: String) {
    fun fail(): Nothing = error("$location: invalid $type value $value")
    val primitive = value as? JsonPrimitive
    when {
        type == "Bool" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) fail()
        type == "String" -> if (primitive?.isString != true) fail()
        type == "Int64" || type == "Money" -> if (primitive == null || primitive.isString || primitive.longOrNull == null) fail()
        type.startsWith("Ref<") -> if (primitive?.isString != true || instances[primitive.content]?.kind != type.removePrefix("Ref<").dropLast(1)) fail()
        type.startsWith("List<") -> {
            if (value !is JsonArray || value.size > 4096) fail()
            value.forEach { validateValue(it, type.removePrefix("List<").dropLast(1), instances, location) }
        }
        type.startsWith("Option<") -> if (value != JsonNull) {
            if (value !is JsonObject || value.keys != setOf("some")) fail()
            validateValue(value.getValue("some"), type.removePrefix("Option<").dropLast(1), instances, location)
        }
        type == "Position" || type == "Target" -> {
            val fields = SemanticEnvironment().recordFields(type)!!
            if (value !is JsonObject || value.keys != fields.keys) fail()
            fields.forEach { (name, fieldType) -> validateValue(value.getValue(name), fieldType.render(), instances, location) }
            if (type == "Target" && value.getValue("id").jsonPrimitive.content !in instances) fail()
        }
        type in setOf("Real64", "Probability", "Rate", "Duration", "Temperature", "TemperatureDelta", "Power", "Energy", "Volume", "Distance", "Speed", "Health") -> {
            val number = primitive?.takeUnless { it.isString }?.doubleOrNull ?: fail()
            if (!number.isFinite()) fail()
            if (type == "Probability" && number !in 0.0..1.0) fail()
            if (type in setOf("Rate", "Duration", "Power", "Energy", "Volume", "Distance", "Speed", "Health") && number < 0) fail()
        }
        else -> error("$location: unsupported JSON parameter type $type; enum parameters need a versioned enum schema")
    }
}
