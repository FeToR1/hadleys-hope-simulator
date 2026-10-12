package colony.runtime

import colony.ast.*
import colony.compiler.*
import colony.parser.ColonyParserFacade
import colony.semantics.*
import colony.world.WorldConfig
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.readText

/** .settings is another Colony entry point, lowered to the shared scenario model. */
fun prepareSettings(path: Path): PreparedRun {
    val file = path.toAbsolutePath().normalize()
    try {
        val ast = ColonyParserFacade().parseSettings(file.readText())
        val expressions = ConstantExpressions()
        ast.constants.forEach(expressions::declare)
        fun evaluate(value: SettingsValue): TypedConstant = try { expressions.evaluate(value.expression) }
            catch (failure: Exception) { error("${value.span.startLine}:${value.span.startColumn} ${value.name}: ${failure.message}") }
        fun value(constant: TypedConstant, expected: String? = null): JsonElement {
            val actual = constant.type.render()
            val compatible = expected == null || expected == actual ||
                (expected in setOf("Real64", "Money", "Probability") && constant.type.isNumericScalar()) ||
                (expected.startsWith("Ref<") && constant.type == Type.String) ||
                (expected.startsWith("Option<") && constant.value == ConstantValue.None)
            require(compatible) { "Expected $expected, got $actual" }
            return when (val v = constant.value) {
                is ConstantValue.Bool -> JsonPrimitive(v.value)
                is ConstantValue.Text -> JsonPrimitive(v.value)
                is ConstantValue.Int64 -> JsonPrimitive(v.value)
                is ConstantValue.Real64 -> JsonPrimitive(v.value)
                is ConstantValue.Decimal -> JsonPrimitive(v.value.toDouble().also { require(it.isFinite()) })
                ConstantValue.None -> JsonNull
                is ConstantValue.EnumValue -> JsonPrimitive(v.value)
            }
        }
        fun properties(nodes: List<SettingsNode>, scope: String): Map<String, SettingsNode> {
            require(nodes.map { it.name }.distinct().size == nodes.size) { "$scope: duplicate property" }
            return nodes.associateBy { it.name }
        }
        fun scalar(node: SettingsNode, expected: String? = null): JsonElement {
            if (node is SettingsList) {
                require(expected == null || (expected.startsWith("List<") && expected.endsWith(">"))) {
                    "${node.name}: expected $expected, got a list"
                }
                val inner = expected?.removePrefix("List<")?.removeSuffix(">")
                val elements = node.elements.map { expressions.evaluate(it) }
                require(inner != null || elements.map { it.type }.distinct().size <= 1) { "${node.name}: list element types must agree" }
                return JsonArray(elements.map { value(it, inner) })
            }
            require(node is SettingsValue) { "${node.name}: expected an assignment" }
            return try { value(evaluate(node), expected) }
                catch (failure: Exception) { error("${node.span.startLine}:${node.span.startColumn} ${node.name}: ${failure.message}") }
        }
        fun objectValues(nodes: List<SettingsNode>, scope: String, types: Map<String, String> = emptyMap()): JsonObject {
            val fields = properties(nodes, scope)
            if (types.isNotEmpty()) require(fields.keys.all { it in types }) { "$scope: unknown property ${fields.keys - types.keys}" }
            return JsonObject(fields.mapValues { (name, node) -> scalar(node, types[name]) })
        }
        val root = properties(ast.properties, "settings")
        require(root.keys.all { it in setOf("seed", "step", "ticks", "world") }) { "Unknown settings: ${root.keys - setOf("seed", "step", "ticks", "world")}" }
        val step = root["step"]?.let { scalar(it, "Duration").jsonPrimitive.content + "s" } ?: "1s"
        require(ast.sources.isNotEmpty() && ast.sources.distinct().size == ast.sources.size) { "Sources must be nonempty and unique" }
        val sources = ast.sources.map { name ->
            require(name.endsWith(".colony")) { "source must reference a .colony file: $name" }
            SourceFile(name, file.parent.resolve(name).normalize().readText())
        }
        val program = compileSources(sources, step)
        require(ast.templates.map { it.name }.distinct().size == ast.templates.size) { "Duplicate template" }
        val templates = ast.templates.associate { template ->
            require(template.objects.map { it.name }.distinct().size == template.objects.size) { "${template.name}: duplicate object" }
            template.name to Template(template.objects.associate { obj ->
                val behavior = program.behaviors.find { it.name == obj.behavior } ?: error("Unknown behavior ${obj.behavior}")
                val params = objectValues(obj.parameters, "${template.name}/${obj.name}.params", behavior.params.associate { it.name to it.type })
                require(params.keys == behavior.params.map { it.name }.toSet()) { "${obj.behavior}: parameter names mismatch" }
                val fields = properties(obj.properties, obj.name)
                require(fields.keys.all { it in setOf("parent", "x", "y", "view") }) { "${obj.name}: unknown object property" }
                val view = fields["view"]?.let {
                    require(it is SettingsBlock) { "view requires a block" }
                    objectValues(it.properties, "${obj.name}.view", SemanticEnvironment().kindContract(behavior.kind)!!.viewFields.mapValues { (_, type) -> type.render() })
                } ?: JsonObject(emptyMap())
                obj.name to ObjectSpec(behavior.kind, obj.behavior, params, view,
                    fields["parent"]?.let { scalar(it, "String").jsonPrimitive.content },
                    fields["x"]?.let { scalar(it, "Distance").jsonPrimitive.double } ?: 0.0,
                    fields["y"]?.let { scalar(it, "Distance").jsonPrimitive.double } ?: 0.0)
            })
        }
        val populations = ast.populations.map { population ->
            val fields = objectValues(population.properties, population.prefix, mapOf("count" to "Int64", "columns" to "Int64",
                "spacingX" to "Distance", "spacingY" to "Distance", "originX" to "Distance", "originY" to "Distance"))
            val count = fields["count"]?.jsonPrimitive?.int ?: error("${population.prefix}: count is required")
            val grid = if (fields.keys.any { it != "count" }) GridLayout(
                fields["columns"]?.jsonPrimitive?.int ?: 20,
                fields["spacingX"]?.jsonPrimitive?.double ?: 70.0, fields["spacingY"]?.jsonPrimitive?.double ?: 70.0,
                fields["originX"]?.jsonPrimitive?.double ?: 0.0, fields["originY"]?.jsonPrimitive?.double ?: 0.0) else null
            Population(population.template, count, population.prefix, grid)
        }
        fun worldObject(nodes: List<SettingsNode>, prefix: String): JsonObject = JsonObject(properties(nodes, prefix).mapValues { (name, node) ->
            val key = "$prefix.$name"
            when (node) {
                is SettingsBlock -> worldObject(node.properties, key)
                is SettingsList -> scalar(node)
                is SettingsValue -> {
                    val expected = worldSettingQuantity(key)
                    val constant = evaluate(node)
                    require(expected != null || (!constant.type.isPhysical() && constant.type !in setOf(Type.Duration, Type.Rate))) {
                        "$key: units are unavailable for this coefficient; use a scalar"
                    }
                    val result = value(constant, expected)
                    // World billing periods are integral seconds, unlike most physical settings.
                    if (key in setOf("world.tariffs.billingIntervalSeconds", "world.tariffs.monthSeconds"))
                        JsonPrimitive(result.jsonPrimitive.content.toBigDecimal().longValueExact()) else result
                }
            }
        })
        val world = root["world"]?.let {
            require(it is SettingsBlock) { "world requires a block" }
            colonyJson.decodeFromJsonElement<WorldConfig>(worldObject(it.properties, "world"))
        }
        val scenario = Scenario(catalog = file.fileName.toString(),
            seed = root["seed"]?.let { scalar(it, "Int64").jsonPrimitive.long } ?: 426,
            step = step, ticks = root["ticks"]?.let { scalar(it, "Int64").jsonPrimitive.int } ?: 120,
            populations = populations, world = world)
        require(scenario.ticks > 0) { "ticks must be positive" }
        return expandScenario(scenario, Catalog(sources = ast.sources, templates = templates), program).copy(sources = sources)
    } catch (failure: Exception) { throw IllegalArgumentException("$file: ${failure.message}", failure) }
}

/** Quantity fields are explicit; compound coefficients remain scalar until Colony has their dimensions. */
internal fun worldSettingQuantity(path: String): String? {
    val name = path.substringAfterLast('.')
    if (name.endsWith("Seconds") || path.startsWith("world.repair.duration.")) return "Duration"
    if (path.startsWith("world.geometry.") && name in setOf("x", "y", "coastX", "gateWidth")) return "Distance"
    if (path.startsWith("world.ecosystem.cleanup.unloadAt.") && name in setOf("x", "y")) return "Distance"
    return when (path.removePrefix("world.")) {
        "climate.meanTemperature", "climate.amplitude", "house.initialTemperature", "house.coldThreshold", "human.harmThreshold" -> "Temperature"
        "power.reactorPower", "power.solarPeak", "power.upsMaxPower", "water.pumpPower", "defense.powerPerUnit" -> "Power"
        "power.upsCapacity", "power.upsInitialCharge" -> "Energy"
        "house.kettleVolume" -> "Volume"
        "water.freezeThresholdIndoor" -> "Duration"
        "power.poleHealth", "fence.health", "creatine.lowHealthThreshold" -> "Health"
        "human.vandalRadius", "marine.assaultRadius", "transport.boardRadius", "transport.walkRadius", "repair.workRadius",
        "sight.sightRadius", "sight.attackRadius", "sight.predatorRadius", "fence.margin", "fence.segmentLength", "fence.roamingDistance",
        "sea.coastalZoneWidth", "sea.fogMaxPenetration", "sea.cloudWidth", "sea.cloudHeight" -> "Distance"
        "ecosystem.zoneSize", "ecosystem.cleanup.unloadRadius", "ecosystem.plankton.coastalWidth", "ecosystem.humanFactors.vandalRadius" -> "Distance"
        "ecosystem.humanFactors.vandalDamage" -> "Health"
        "ecosystem.plankton.burnPowerPerSecond" -> "Power"
        "repair.roverSpeed", "sea.fogAdvanceSpeed" -> "Speed"
        else -> null
    }
}
