package colony.runtime

import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import kotlin.test.*

class NumericComparisonTest {
    @Test fun mixedComparisonMatchesExactBinaryDecimalOracleAtIntegerBoundaries() {
        val integers = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -9007199254740993, -1, 0, 1,
            9007199254740991, 9007199254740993, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val reals = listOf(-9223372036854775808.0, Math.nextDown(-9223372036854775808.0), -9007199254740992.0,
            -1.5, -0.0, 0.0, Double.MIN_VALUE, 0.5, 1.0, 9007199254740992.0,
            Math.nextDown(9223372036854775808.0), 9223372036854775808.0)
        for (integer in integers) for (real in reals) {
            val expected = BigDecimal.valueOf(integer).compareTo(BigDecimal(real)).coerceIn(-1, 1)
            assertEquals(expected, compareNumbers(JsonPrimitive(integer), JsonPrimitive(real)), "$integer vs $real")
            assertEquals(-expected, compareNumbers(JsonPrimitive(real), JsonPrimitive(integer)), "$real vs $integer")
        }
        assertEquals(0, compareNumbers(JsonPrimitive(-0.0), JsonPrimitive(0.0)))
        assertNull(compareNumbers(JsonPrimitive("1"), JsonPrimitive(1)))
        assertNull(compareNumbers(JsonPrimitive(true), JsonPrimitive(1)))
    }
}
