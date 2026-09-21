package colony.semantics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The contract as a document for everyone who is not the compiler: the world kernel, the VMs, the UI and
 * scenario authors. It is generated from the same definitions the compiler checks programs against, so it cannot
 * disagree with them (docs/generated/contract.json is compared with it by a test).
 */
fun contractDocument(environment: SemanticEnvironment = SemanticEnvironment()): JsonObject = buildJsonObject {
    put("contractVersion", CONTRACT_VERSION)
    putJsonObject("units") {
        for (unit in PhysicalUnit.entries) putJsonObject(unit.suffix) {
            put("quantity", unit.kind?.let { Type.Physical(it).render() } ?: if (unit.category == UnitCategory.RATE) "Rate" else "Duration")
            put("toCanonical", unit.toCanonical.stripTrailingZeros().toPlainString())
        }
    }
    putJsonObject("records") {
        for (name in environment.recordNames) putJsonObject(name) {
            environment.recordFields(name)!!.forEach { (field, type) -> put(field, type.render()) }
        }
    }
    putJsonObject("kinds") {
        for ((kind, contract) in environment.kindContracts.toSortedMap()) putJsonObject(kind) {
            putJsonObject("view") { contract.viewFields.forEach { (field, type) -> put(field, type.render()) } }
            putJsonArray("capabilities") { contract.capabilities.map { it.name }.sorted().forEach { add(it) } }
        }
    }
    putJsonObject("intrinsics") {
        for ((name, intrinsic) in Intrinsics.all().toSortedMap()) putJsonObject(name) {
            put("signature", intrinsic.signature)
            put("effect", intrinsic.effect.name)
            intrinsic.requiredCapability?.let { put("capability", it.name) }
        }
    }
    putJsonObject("kernelEvents") {
        for ((name, fields) in KernelEvents.catalog) putJsonObject(name) { fields.forEach { (field, type) -> put(field, type.render()) } }
    }
}
