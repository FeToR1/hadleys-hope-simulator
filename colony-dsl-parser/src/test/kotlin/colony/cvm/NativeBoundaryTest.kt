package colony.cvm

import colony.bytecode.SourceFile
import colony.bytecode.compileSources
import colony.runtime.DeliveredEvent
import colony.runtime.ReferenceVm
import colony.runtime.VmFrame
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeBoundaryTest {
    @Test fun mixedComparisonsPreserveAllIntegerBitsInValuesAndBranches() {
        val relations = listOf("==", "!=", "<", "<=", ">", ">=")
        val expressions = relations.map { "m.integer $it m.real" } + relations.map { "m.real $it m.integer" }
        val source = SourceFile("numeric.colony", """
            event Compare { integer: Int64; real: Real64; }
            behavior Numeric for House {
                ${expressions.indices.joinToString("\n") { "state value$it: Bool = false; state branch$it: Bool = false;" }}
                on Compare(m) as compare {
                    ${expressions.mapIndexed { i, e -> "value$i = $e; if $e { branch$i = true; } else { branch$i = false; }" }.joinToString("\n")}
                }
            }
        """.trimIndent())
        val program = compileToCvm(listOf(source))
        val reference = ReferenceVm("numeric", compileSources(listOf(source)), "Numeric")
        val sessions = Path.of("build/sessions").also(Files::createDirectories)
        val artifact = sessions.resolve("numeric.cvm")
        Files.write(artifact, ArtifactWriter.write(program))
        val pairs = listOf(
            9007199254740993L to 9007199254740992.0,
            -9007199254740993L to -9007199254740992.0,
            Long.MAX_VALUE to Math.scalb(1.0, 63),
            Long.MIN_VALUE to -Math.scalb(1.0, 63),
            Long.MIN_VALUE + 1 to -Math.scalb(1.0, 63),
            Long.MAX_VALUE to Math.nextDown(Math.scalb(1.0, 63)),
            Long.MIN_VALUE to Math.nextDown(-Math.scalb(1.0, 63)),
            Long.MIN_VALUE to Math.nextUp(-Math.scalb(1.0, 63)),
            1L to 1.5, -1L to -1.5, 0L to -0.0, 0L to 0.0,
            0L to Double.MIN_VALUE, 0L to -Double.MIN_VALUE,
            Long.MAX_VALUE to Double.MAX_VALUE, Long.MIN_VALUE to -Double.MAX_VALUE,
            42L to 42.0,
        )
        Files.newOutputStream(sessions.resolve("numeric.boundaries.session")).use { recording ->
            NativeVmConnection(assertNotNull(NativeVmConnection.executable()), artifact, program,
                "Numeric", "numeric", 426, JsonObject(emptyMap()), recording).use { native ->
                pairs.forEachIndexed { tick, (integer, real) ->
                    val fields = buildJsonObject { put("integer", integer); put("real", real) }
                    val eventId = program.events.single { it.name == "Compare" }.id
                    val event = buildJsonObject {
                        put("eventId", eventId); put("sender", "test"); put("sequence", tick); put("fields", fields)
                    }
                    val outcome = native.step(tick.toLong(), JsonObject(emptyMap()), listOf(event))
                    assertNull(outcome.failure)
                    val expectedReference = reference.step(VmFrame(tick.toLong(), JsonObject(emptyMap()),
                        listOf(DeliveredEvent(eventId, fields, "test", tick.toLong())))).state
                    // BigDecimal(double) represents the exact binary floating-point value, without decimal rounding.
                    val order = BigDecimal.valueOf(integer).compareTo(BigDecimal(real))
                    fun answers(c: Int) = listOf(c == 0, c != 0, c < 0, c <= 0, c > 0, c >= 0)
                    (answers(order) + answers(-order)).forEachIndexed { i, expected ->
                        for (slot in listOf("value$i", "branch$i")) {
                            val where = "$integer vs $real: $slot (${expressions[i]})"
                            assertEquals(expected, outcome.state.getValue(slot).jsonPrimitive.boolean, "native $where")
                            assertEquals(expected, expectedReference.getValue(slot).jsonPrimitive.boolean, "reference $where")
                        }
                    }
                }
            }
        }
    }
}
