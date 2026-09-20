package colony.semantics

import colony.ast.TypeRef
import java.math.BigDecimal

/** Physical quantity families. Temperature is absolute; TemperatureDelta is semantic-only. */
enum class PhysicalKind {
    TEMPERATURE,
    TEMPERATURE_DELTA,
    POWER,
    ENERGY,
    VOLUME,
    DISTANCE,
    SPEED,
    HEALTH,
}

/** Source-level unit suffixes understood by the semantic layer. */
enum class UnitCategory { PHYSICAL, DURATION, RATE }

enum class PhysicalUnit(
    val suffix: kotlin.String,
    val category: UnitCategory,
    val kind: PhysicalKind?,
    val toCanonical: BigDecimal,
) {
    DEG_C("degC", UnitCategory.PHYSICAL, PhysicalKind.TEMPERATURE, BigDecimal.ONE),
    W("W", UnitCategory.PHYSICAL, PhysicalKind.POWER, BigDecimal.ONE),
    KW("kW", UnitCategory.PHYSICAL, PhysicalKind.POWER, BigDecimal("1000")),
    J("J", UnitCategory.PHYSICAL, PhysicalKind.ENERGY, BigDecimal.ONE),
    KJ("kJ", UnitCategory.PHYSICAL, PhysicalKind.ENERGY, BigDecimal("1000")),
    L("L", UnitCategory.PHYSICAL, PhysicalKind.VOLUME, BigDecimal.ONE),
    ML("mL", UnitCategory.PHYSICAL, PhysicalKind.VOLUME, BigDecimal("0.001")),
    M3("m3", UnitCategory.PHYSICAL, PhysicalKind.VOLUME, BigDecimal("1000")),
    M("m", UnitCategory.PHYSICAL, PhysicalKind.DISTANCE, BigDecimal.ONE),
    KM("km", UnitCategory.PHYSICAL, PhysicalKind.DISTANCE, BigDecimal("1000")),
    MPS("mps", UnitCategory.PHYSICAL, PhysicalKind.SPEED, BigDecimal.ONE),
    KMH("kmh", UnitCategory.PHYSICAL, PhysicalKind.SPEED, BigDecimal("0.2777777777777777777777777778")),
    HP("hp", UnitCategory.PHYSICAL, PhysicalKind.HEALTH, BigDecimal.ONE),
    MS("ms", UnitCategory.DURATION, null, BigDecimal("0.001")),
    S("s", UnitCategory.DURATION, null, BigDecimal.ONE),
    MIN("min", UnitCategory.DURATION, null, BigDecimal("60")),
    H("h", UnitCategory.DURATION, null, BigDecimal("3600")),
    PER_S("per_s", UnitCategory.RATE, null, BigDecimal.ONE),
}

/** Semantic categories that are not physical quantity dimensions. */
enum class SemanticKind {
    BOOL,
    INT64,
    REAL64,
    DURATION,
    PROBABILITY,
    RATE,
    MONEY,
    STRING,
    VOID,
    UNKNOWN,
}

sealed interface Type {
    data object Bool : Type
    data object Int64 : Type
    data object Real64 : Type
    data object Duration : Type
    data object Probability : Type
    data object Rate : Type
    data object Money : Type
    data object String : Type
    data object Void : Type
    data object Unknown : Type

    data class Physical(val kind: PhysicalKind) : Type
    data class Ref(val kind: kotlin.String) : Type
    data class Option(val inner: Type) : Type
    data class List(val element: Type) : Type
    data class Enum(val behavior: kotlin.String, val name: kotlin.String) : Type
    data class EnumValue(val enumType: Enum, val value: kotlin.String) : Type
    data class Event(val name: kotlin.String, val fields: Map<kotlin.String, Type>) : Type
    data class Kind(val name: kotlin.String) : Type
    data class View(val kind: kotlin.String) : Type
    data object IntrinsicNamespace : Type
}

data class TypedConstant(
    val type: Type,
    val value: ConstantValue,
)

sealed interface ConstantValue {
    data class Bool(val value: kotlin.Boolean) : ConstantValue
    data class Int64(val value: kotlin.Long) : ConstantValue
    data class Real64(val value: kotlin.Double) : ConstantValue
    data class Text(val value: kotlin.String) : ConstantValue
    data object None : ConstantValue
    data class Decimal(val value: BigDecimal) : ConstantValue
    data class EnumValue(val enumType: Type.Enum, val value: kotlin.String) : ConstantValue
}

fun Type.render(): String = when (this) {
    Type.Bool -> "Bool"
    Type.Int64 -> "Int64"
    Type.Real64 -> "Real64"
    Type.Duration -> "Duration"
    Type.Probability -> "Probability"
    Type.Rate -> "Rate"
    Type.Money -> "Money"
    Type.String -> "String"
    Type.Void -> "Void"
    Type.Unknown -> "<unknown>"
    is Type.Physical -> when (kind) {
        PhysicalKind.TEMPERATURE -> "Temperature"
        PhysicalKind.TEMPERATURE_DELTA -> "TemperatureDelta"
        PhysicalKind.POWER -> "Power"
        PhysicalKind.ENERGY -> "Energy"
        PhysicalKind.VOLUME -> "Volume"
        PhysicalKind.DISTANCE -> "Distance"
        PhysicalKind.SPEED -> "Speed"
        PhysicalKind.HEALTH -> "Health"
    }
    is Type.Ref -> "Ref<${kind}>"
    is Type.Option -> "Option<${inner.render()}>"
    is Type.List -> "List<${element.render()}>"
    is Type.Enum -> "${behavior}.${name}"
    is Type.EnumValue -> "${enumType.render()}.${value}"
    is Type.Event -> name
    is Type.Kind -> name
    is Type.View -> "View<${kind}>"
    Type.IntrinsicNamespace -> "<intrinsic>"
}

fun Type.isNumericScalar(): Boolean = this == Type.Int64 || this == Type.Real64
fun Type.isPhysical(): Boolean = this is Type.Physical

fun Type.sameNominal(other: Type): Boolean = when {
    this is Type.Physical && other is Type.Physical -> kind == other.kind
    this is Type.Ref && other is Type.Ref -> kind == other.kind
    this is Type.Option && other is Type.Option -> inner.sameNominal(other.inner)
    this is Type.List && other is Type.List -> element.sameNominal(other.element)
    this is Type.Enum && other is Type.Enum -> behavior == other.behavior && name == other.name
    this == other -> true
    else -> false
}

fun Type.isAssignableFrom(actual: Type): Boolean = when {
    this == Type.Unknown || actual == Type.Unknown -> true
    this is Type.Option && actual is Type.Option -> this.inner.isAssignableFrom(actual.inner)
    this is Type.List && actual is Type.List -> this.element.isAssignableFrom(actual.element)
    this.sameNominal(actual) -> true
    this == Type.Real64 && actual == Type.Int64 -> true
    this == Type.Money && actual == Type.Int64 -> true
    this == Type.Probability && actual == Type.Real64 -> false // only contextual constant conversion is allowed in the checker
    else -> false
}

fun resolveTypeRef(typeRef: TypeRef, behaviorName: String, enums: Map<String, Type.Enum>): Type {
    return when (typeRef.name) {
        "Bool" -> Type.Bool
        "Int64" -> Type.Int64
        "Real64" -> Type.Real64
        "Duration" -> Type.Duration
        "Probability" -> Type.Probability
        "Rate" -> Type.Rate
        "Money" -> Type.Money
        "String" -> Type.String
        "Temperature" -> Type.Physical(PhysicalKind.TEMPERATURE)
        "Power" -> Type.Physical(PhysicalKind.POWER)
        "Energy" -> Type.Physical(PhysicalKind.ENERGY)
        "Volume" -> Type.Physical(PhysicalKind.VOLUME)
        "Distance" -> Type.Physical(PhysicalKind.DISTANCE)
        "Speed" -> Type.Physical(PhysicalKind.SPEED)
        "Health" -> Type.Physical(PhysicalKind.HEALTH)
        "Ref" -> {
            require(typeRef.arguments.size == 1) { "Ref<T> expects one type argument" }
            val arg = typeRef.arguments.single()
            Type.Ref(resolveKindName(arg, behaviorName, enums))
        }
        "Option" -> {
            require(typeRef.arguments.size == 1) { "Option<T> expects one type argument" }
            Type.Option(resolveTypeRef(typeRef.arguments.single(), behaviorName, enums))
        }
        "List" -> {
            require(typeRef.arguments.size == 1) { "List<T> expects one type argument" }
            Type.List(resolveTypeRef(typeRef.arguments.single(), behaviorName, enums))
        }
        else -> enums[typeRef.name] ?: Type.Kind(typeRef.name)
    }
}

private fun resolveKindName(
    typeRef: TypeRef,
    @Suppress("UNUSED_PARAMETER") behaviorName: kotlin.String,
    enums: Map<String, Type.Enum>,
): kotlin.String = when {
    typeRef.arguments.isNotEmpty() -> error("Expected a nominal kind in Ref<T>")
    enums.containsKey(typeRef.name) -> error("Enum '${typeRef.name}' cannot be used as Ref<T>")
    typeRef.name.isBlank() -> error("Empty kind name")
    else -> typeRef.name
}

fun parseUnitType(unit: kotlin.String): Type? = when (PhysicalUnit.entries.firstOrNull { it.suffix == unit }) {
    null -> null
    PhysicalUnit.DEG_C -> Type.Physical(PhysicalKind.TEMPERATURE)
    PhysicalUnit.W, PhysicalUnit.KW -> Type.Physical(PhysicalKind.POWER)
    PhysicalUnit.J, PhysicalUnit.KJ -> Type.Physical(PhysicalKind.ENERGY)
    PhysicalUnit.L, PhysicalUnit.ML, PhysicalUnit.M3 -> Type.Physical(PhysicalKind.VOLUME)
    PhysicalUnit.M, PhysicalUnit.KM -> Type.Physical(PhysicalKind.DISTANCE)
    PhysicalUnit.MPS, PhysicalUnit.KMH -> Type.Physical(PhysicalKind.SPEED)
    PhysicalUnit.HP -> Type.Physical(PhysicalKind.HEALTH)
    PhysicalUnit.MS, PhysicalUnit.S, PhysicalUnit.MIN, PhysicalUnit.H -> Type.Duration
    PhysicalUnit.PER_S -> Type.Rate
}

fun canonicalMultiplier(unit: kotlin.String): BigDecimal? = PhysicalUnit.entries.firstOrNull { it.suffix == unit }?.toCanonical
