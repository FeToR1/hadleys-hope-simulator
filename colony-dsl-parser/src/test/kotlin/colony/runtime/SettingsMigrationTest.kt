package colony.runtime

import colony.compiler.colonyJson
import colony.cvm.ArtifactWriter
import colony.world.WorldConfig
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Frozen fingerprints of the old inputs: compare values, not JSON number spelling. */
class SettingsMigrationTest {
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null)
            JsonPrimitive(element.content.toBigDecimal().stripTrailingZeros()) else element
    }

    private fun hash(prepared: PreparedRun): String {
        val model = buildJsonObject {
            put("manifest", colonyJson.encodeToJsonElement(RunManifest.serializer(), prepared.manifest))
            put("world", prepared.scenario.world?.let { colonyJson.encodeToJsonElement(WorldConfig.serializer(), it) } ?: JsonNull)
            put("ticks", prepared.scenario.ticks)
            put("changes", JsonArray(prepared.scenario.changes.map { colonyJson.encodeToJsonElement(ObservationChange.serializer(), it) }))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonical(model).toString().toByteArray(Charsets.UTF_8))
        // Contract bumps describe new settings syntax; the executable instructions must stay the same.
        val p = prepared.program.nativeProgram
        digest.update(ArtifactWriter.write(colony.cvm.Program(0, p.stepSeconds, p.strings, p.schemas, p.events, p.behaviors)))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun allMigratedSettingsPreserveTheOldRuns() {
        val fingerprints = mapOf(
            "integration/small" to "12111fd5c937176649b3e024e1a37459d9db85bf8014bbdcc0e42e4d84dca18b",
            "integration/colony-300" to "145c0b833569d1d049a839bd4c9bb13af9acb3368e769f1fd49a8fa0e7ede51a",
            "physics/cascade" to "fe559dd4b5c4a2942c7bd5039892dd808f5954697a1dfd2ac0420bbed9c4b0cd",
            "physics/ecosystem-small" to "08fb732b0f46aabd7909807fa68f20ca8e202046bd2fc7b5b5c02d7886d98764",
            "physics/full" to "7d5a315a19bd9bbdfb9af3b9326a0a7ef649c3ced45fb8162e8a74d1bae30cf2",
            "physics/full-5000" to "f0edb16746a3dd2fd54a2ef5554dbff71222bc46f7db3d71f0046c89f85d0400",
            "physics/colony" to "3e1b48b6017a4864c371240238bb805ecc56834049a21cc43ab1735641846376")
        for ((name, expected) in fingerprints) {
            assertEquals(expected, hash(prepareScenario(Path.of("../examples/$name.settings"))), name)
        }
    }
}
