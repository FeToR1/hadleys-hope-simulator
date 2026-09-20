package colony.semantics

import colony.ast.SourceSpan

/** Entity-kind contract supplied by runtime/simulation owners (Role #7). */
data class KindContract(
    val kind: String,
    val viewFields: Map<String, Type>,
    val capabilities: Set<Capability> = emptySet(),
)

enum class Capability {
    POWER_REQUEST,
    DAMAGE_REQUEST,
    MOTION_REQUEST,
    REPAIR_REQUEST,
}

data class IntrinsicContract(
    val name: String,
    val resultType: (List<Type>) -> Type,
    val argumentCheck: (List<Type>) -> String?,
    val requiredCapability: Capability? = null,
    val effect: EffectClass,
)

enum class EffectClass {
    PURE,
    RANDOM,
    STATE,
    EXTERNAL,
}

class SemanticEnvironment(
    val kindContracts: Map<String, KindContract> = defaultKindContracts(),
) {
    fun kindContract(kind: String): KindContract? = kindContracts[kind]
}

/** Small default contract. Simulation/runtime can replace or extend it without touching the parser. */
fun defaultKindContracts(): Map<String, KindContract> = listOf(
    KindContract(
        kind = "House",
        viewFields = mapOf(
            "temperature" to Type.Physical(PhysicalKind.TEMPERATURE),
            "occupants" to Type.Int64,
        ),
    ),
    KindContract(
        kind = "Heater",
        viewFields = mapOf(
            "home_occupants" to Type.Int64,
            "broken" to Type.Bool,
            "power_connected" to Type.Bool,
        ),
        capabilities = setOf(Capability.POWER_REQUEST),
    ),
    KindContract(
        kind = "Kettle",
        viewFields = mapOf(
            "water_temperature" to Type.Physical(PhysicalKind.TEMPERATURE),
            "broken" to Type.Bool,
            "power_connected" to Type.Bool,
        ),
        capabilities = setOf(Capability.POWER_REQUEST),
    ),
    KindContract(
        kind = "Human",
        viewFields = mapOf(
            "cold" to Type.Bool,
            "position" to Type.Kind("Position"),
        ),
        capabilities = setOf(Capability.DAMAGE_REQUEST, Capability.MOTION_REQUEST),
    ),
    KindContract(
        kind = "Xenomorph",
        viewFields = mapOf(
            "position" to Type.Kind("Position"),
            "visible_breakables" to Type.List(Type.Ref("Breakable")),
            "patrol_waypoint" to Type.Kind("Position"),
        ),
        capabilities = setOf(Capability.DAMAGE_REQUEST, Capability.MOTION_REQUEST),
    ),
).associateBy(KindContract::kind)

object Intrinsics {
    private val contracts: Map<String, IntrinsicContract> = mapOf(
        "chance" to IntrinsicContract(
            name = "chance",
            resultType = { Type.Bool },
            argumentCheck = { args ->
                when {
                    args.size != 2 -> "chance expects (Probability, String)"
                    !args[0].sameNominal(Type.Probability) && !args[0].sameNominal(Type.Real64) -> "chance probability must be Probability or a constant Real64 literal in [0,1]"
                    args[1] != Type.String -> "chance site must be String"
                    else -> null
                }
            },
            effect = EffectClass.RANDOM,
        ),
        "hazard" to IntrinsicContract(
            name = "hazard",
            resultType = { Type.Bool },
            argumentCheck = { args ->
                when {
                    args.size != 2 -> "hazard expects (Rate, String)"
                    args.getOrNull(0) != Type.Rate -> "hazard rate must be Rate"
                    args.getOrNull(1) != Type.String -> "hazard site must be String"
                    else -> null
                }
            },
            effect = EffectClass.RANDOM,
        ),
        "some" to IntrinsicContract(
            name = "some",
            resultType = { args -> if (args.size == 1) Type.Option(args[0]) else Type.Unknown },
            argumentCheck = { args -> if (args.size == 1) null else "some expects one argument" },
            effect = EffectClass.PURE,
        ),
        "clamp" to IntrinsicContract(
            name = "clamp",
            resultType = { args -> if (args.size == 3 && args[0].sameNominal(args[1]) && args[0].sameNominal(args[2])) args[0] else Type.Unknown },
            argumentCheck = { args -> if (args.size == 3 && args.distinctBy { it.render() }.size == 1) null else "clamp expects three values of the same scalar type" },
            effect = EffectClass.PURE,
        ),
        "nearest" to IntrinsicContract(
            name = "nearest",
            resultType = { args ->
                val list = args.singleOrNull() as? Type.List
                if (list != null) Type.Option(list.element) else Type.Unknown
            },
            argumentCheck = { args ->
                if (args.singleOrNull() is Type.List) null else "nearest expects List<T>"
            },
            effect = EffectClass.PURE,
        ),
        "power.request" to IntrinsicContract(
            name = "power.request",
            resultType = { Type.Void },
            argumentCheck = { args ->
                if (args.size == 1 && args[0] == Type.Physical(PhysicalKind.POWER)) null
                else "power.request expects (Power)"
            },
            requiredCapability = Capability.POWER_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
        "damage.request" to IntrinsicContract(
            name = "damage.request",
            resultType = { Type.Void },
            argumentCheck = { args ->
                when {
                    args.size != 3 -> "damage.request expects (Ref<T> or String, Health, EnumValue)"
                    args[0] !is Type.Ref && args[0] != Type.String -> "damage target must be Ref<T> or ref id String"
                    args[1] != Type.Physical(PhysicalKind.HEALTH) -> "damage amount must be Health"
                    args[2] !is Type.EnumValue && args[2] != Type.String -> "damage reason must be an enum value or String"
                    else -> null
                }
            },
            requiredCapability = Capability.DAMAGE_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
        "motion.request" to IntrinsicContract(
            name = "motion.request",
            resultType = { Type.Void },
            argumentCheck = { args ->
                if (args.size == 2 && args[0] == Type.Kind("Position") && args[1] == Type.Physical(PhysicalKind.SPEED)) null
                else "motion.request expects (Position, Speed)"
            },
            requiredCapability = Capability.MOTION_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
        "repair.request" to IntrinsicContract(
            name = "repair.request",
            resultType = { Type.Void },
            argumentCheck = { args -> if (args.size == 1 && (args[0] is Type.Ref || args[0] == Type.String)) null else "repair.request expects (Ref<T> or String)" },
            requiredCapability = Capability.REPAIR_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
    )

    fun resolve(path: String): IntrinsicContract? = contracts[path]
}
