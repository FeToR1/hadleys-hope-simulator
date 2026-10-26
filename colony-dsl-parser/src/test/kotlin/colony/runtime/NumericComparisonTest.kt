package colony.runtime

import colony.compiler.compileSource
import kotlinx.serialization.json.*
import java.math.BigDecimal
import kotlin.test.*

class NumericComparisonTest {
    @Test fun mixedComparisonMatchesExactBinaryDecimalOracleAtIntegerBoundaries() {
        val program = compileSource("""
            behavior Compare for House {
                param integer: Int64;
                param real: Real64;
                state less: Bool = false;
                state equal: Bool = false;
                state greater: Bool = false;
                every 1s as compare {
                    less = integer < real;
                    equal = integer == real;
                    greater = real < integer;
                }
            }
        """.trimIndent())
        val integers = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -9007199254740993, -1, 0, 1,
            9007199254740991, 9007199254740993, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val reals = listOf(-9223372036854775808.0, Math.nextDown(-9223372036854775808.0), -9007199254740992.0,
            -1.5, -0.0, 0.0, Double.MIN_VALUE, 0.5, 1.0, 9007199254740992.0,
            Math.nextDown(9223372036854775808.0), 9223372036854775808.0)
        for (integer in integers) for (real in reals) {
            val expected = BigDecimal.valueOf(integer).compareTo(BigDecimal(real)).coerceIn(-1, 1)
            NativeVm("house", program, "Compare", buildJsonObject {
                put("integer", integer); put("real", real)
            }).use { vm ->
                val state = vm.step(VmFrame(0, JsonObject(emptyMap()))).state
                assertEquals(expected < 0, state.getValue("less").jsonPrimitive.boolean, "$integer vs $real")
                assertEquals(expected == 0, state.getValue("equal").jsonPrimitive.boolean, "$integer vs $real")
                assertEquals(expected > 0, state.getValue("greater").jsonPrimitive.boolean, "$integer vs $real")
            }
        }
    }
}
