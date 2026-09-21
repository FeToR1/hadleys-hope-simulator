package colony.semantics

import colony.ast.SourceSpan

/**
 * Version of the kind, observation and kernel-event contract below. Every change of a schema is a new
 * version (docs/simulation/trigger-conditions.md, section 6), and compiled artifacts record the version
 * they were built against so that a kernel or VM can refuse a mismatch.
 */
const val CONTRACT_VERSION = 2

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
    /** Human-readable signature for the generated contract document. */
    val signature: String = name,
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

    /** Record types a program can name and read fields of. */
    val recordNames: List<String> = listOf("Position", "Target", "Device")

    fun recordFields(name: String): Map<String, Type>? = when (name) {
        "Position" -> mapOf("x" to Type.Physical(PhysicalKind.DISTANCE), "y" to Type.Physical(PhysicalKind.DISTANCE))
        // An observed object: sorted by (distance, id) in observation lists; health is its current H.
        "Target" -> mapOf(
            "id" to Type.String, "kind" to Type.String, "position" to Type.Kind("Position"),
            "distance" to Type.Physical(PhysicalKind.DISTANCE), "health" to Type.Physical(PhysicalKind.HEALTH),
        )
        // One of the appliances of a house, as the house sees it.
        "Device" -> mapOf("id" to Type.String, "kind" to Type.String, "broken" to Type.Bool)
        else -> null
    }
}

/**
 * Kinds and observations of the MVP as specified in docs/simulation/trigger-conditions.md, section 6.
 * Simulation/runtime can replace or extend it without touching the parser. Kinds that the specification
 * lists but that need decisions about their commands (crews, reactor, UPS, pump, comms) are not here yet.
 */
fun defaultKindContracts(): Map<String, KindContract> = listOf(
    KindContract(
        kind = "House",
        viewFields = mapOf(
            "temperature" to Type.Physical(PhysicalKind.TEMPERATURE),
            "occupants" to Type.Int64,
            "power_connected" to Type.Bool,
            "water_available" to Type.Bool,
            "devices" to Type.List(Type.Kind("Device")),
        ),
    ),
    KindContract(
        kind = "Heater",
        viewFields = mapOf(
            "home_occupants" to Type.Int64,
            "broken" to Type.Bool,
            "power_connected" to Type.Bool,
            "power_granted" to Type.Physical(PhysicalKind.POWER),
        ),
        capabilities = setOf(Capability.POWER_REQUEST),
    ),
    KindContract(
        kind = "Kettle",
        viewFields = mapOf(
            "home_occupants" to Type.Int64,
            "water_temperature" to Type.Physical(PhysicalKind.TEMPERATURE),
            "broken" to Type.Bool,
            "power_connected" to Type.Bool,
            "power_granted" to Type.Physical(PhysicalKind.POWER),
        ),
        capabilities = setOf(Capability.POWER_REQUEST),
    ),
    KindContract(
        kind = "Human",
        viewFields = mapOf(
            "cold" to Type.Bool,
            "position" to Type.Kind("Position"),
            "health" to Type.Physical(PhysicalKind.HEALTH),
            "reachable_breakables" to Type.List(Type.Kind("Target")),
        ),
        capabilities = setOf(Capability.DAMAGE_REQUEST, Capability.MOTION_REQUEST),
    ),
    KindContract(
        kind = "Xenomorph",
        viewFields = mapOf(
            "position" to Type.Kind("Position"),
            "visible_infrastructure" to Type.List(Type.Kind("Target")),
            "visible_humans" to Type.List(Type.Kind("Target")),
            "patrol_waypoint" to Type.Kind("Position"),
        ),
        capabilities = setOf(Capability.DAMAGE_REQUEST, Capability.MOTION_REQUEST),
    ),
).associateBy(KindContract::kind)

/**
 * Events the world kernel sends to programs (docs/simulation/trigger-conditions.md, section 3), with the payload
 * a program receives. A program subscribes by declaring an event of the same name; the compiler then requires
 * exactly this schema, so that a program cannot be linked against a kernel that means something else.
 * Events for journals only (EntityDied, MonthClosed) are not delivered to programs and are not listed.
 */
object KernelEvents {
    val catalog: Map<String, Map<String, Type>> = linkedMapOf(
        "ActionSucceeded" to mapOf("action" to Type.String),
        "ActionRejected" to mapOf("action" to Type.String, "reason" to Type.String),
        "DamageApplied" to mapOf("target" to Type.String, "amount" to Type.Physical(PhysicalKind.HEALTH), "reason" to Type.String),
        "ObjectBroken" to mapOf("object" to Type.String, "reason" to Type.String),
        "PowerLost" to emptyMap(),
        "PowerRestored" to emptyMap(),
        "WaterLost" to emptyMap(),
        "WaterRestored" to emptyMap(),
        "RepairCompleted" to mapOf("object" to Type.String),
        "RepairRejected" to mapOf("reason" to Type.String),
        "ArrivalConfirmed" to mapOf("point" to Type.Kind("Position")),
    )
}

object Intrinsics {
    private val contracts: Map<String, IntrinsicContract> = mapOf(
        "chance" to IntrinsicContract(
            name = "chance",
            signature = "chance(Probability | constant Real64, String site) -> Bool",
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
            signature = "hazard(Rate, String site) -> Bool",
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
            signature = "some(T) -> Option<T>",
            resultType = { args -> if (args.size == 1) Type.Option(args[0]) else Type.Unknown },
            argumentCheck = { args -> if (args.size == 1) null else "some expects one argument" },
            effect = EffectClass.PURE,
        ),
        "clamp" to IntrinsicContract(
            name = "clamp",
            signature = "clamp(T, T, T) -> T",
            resultType = { args -> if (args.size == 3 && args[0].sameNominal(args[1]) && args[0].sameNominal(args[2])) args[0] else Type.Unknown },
            argumentCheck = { args -> if (args.size == 3 && args.distinctBy { it.render() }.size == 1 &&
                (args[0].isNumericScalar() || args[0].isPhysical() || args[0] in setOf(Type.Duration, Type.Rate, Type.Probability, Type.Money))) null
                else "clamp expects three values of the same numeric type" },
            effect = EffectClass.PURE,
        ),
        "nearest" to IntrinsicContract(
            name = "nearest",
            signature = "nearest(List<Target>) -> Option<Target>",
            resultType = { args ->
                val list = args.singleOrNull() as? Type.List
                if (list != null) Type.Option(list.element) else Type.Unknown
            },
            argumentCheck = { args ->
                if (args.singleOrNull() == Type.List(Type.Kind("Target"))) null else "nearest expects List<Target> with id, position and distance"
            },
            effect = EffectClass.PURE,
        ),
        "power.request" to IntrinsicContract(
            name = "power.request",
            signature = "power.request(Power)",
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
            signature = "damage.request(Ref<T> or String target, Health amount, enum value or String reason)",
            resultType = { Type.Void },
            argumentCheck = { args ->
                when {
                    args.size != 3 -> "damage.request expects (Ref<T> or String, Health, EnumValue)"
                    args[0] !is Type.Ref && args[0] != Type.String -> "damage target must be Ref<T> or ref id String"
                    args[1] != Type.Physical(PhysicalKind.HEALTH) -> "damage amount must be Health"
                    args[2] !is Type.Enum && args[2] !is Type.EnumValue && args[2] != Type.String -> "damage reason must be an enum value or String"
                    else -> null
                }
            },
            requiredCapability = Capability.DAMAGE_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
        "motion.request" to IntrinsicContract(
            name = "motion.request",
            signature = "motion.request(Position, Speed)",
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
            signature = "repair.request(Ref<T> or String target)",
            resultType = { Type.Void },
            argumentCheck = { args -> if (args.size == 1 && (args[0] is Type.Ref || args[0] == Type.String)) null else "repair.request expects (Ref<T> or String)" },
            requiredCapability = Capability.REPAIR_REQUEST,
            effect = EffectClass.EXTERNAL,
        ),
    )

    fun resolve(path: String): IntrinsicContract? = contracts[path]

    fun all(): Map<String, IntrinsicContract> = contracts
}
