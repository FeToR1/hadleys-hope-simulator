package colony.bytecode

import kotlin.test.*

/** Diagnostics of a multi-file package must name the file and the line inside that file. */
class PackageDiagnosticsTest {
    private val good = SourceFile("events.colony", "event Ping {}\n")
    private val alsoGood = SourceFile("heater.colony", "behavior H for Heater {\n    every 1s as t { power.request(0W); }\n}")

    @Test fun validPackageCompilesLikeTheConcatenation() {
        val joined = compileSource(listOf(good, alsoGood).joinToString("\n") { it.text })
        assertEquals(joined, compileSources(listOf(good, alsoGood)))
    }

    @Test fun syntaxErrorsPointIntoTheirOwnFile() {
        val broken = SourceFile("broken.colony", "\n\nbehavior B for House {\n    every 1s as t { let x = ; }\n}\n")
        val error = assertFailsWith<ColonyCompilationException> { compileSources(listOf(good, alsoGood, broken)) }
        assertTrue(error.problems.first().startsWith("broken.colony:4:"), error.message)
    }

    @Test fun semanticErrorsPointIntoTheirOwnFile() {
        val wrong = SourceFile("wrong.colony", "// comment\nbehavior W for House {\n    every 1s as t { power.request(1kW); }\n}")
        val error = assertFailsWith<ColonyCompilationException> { compileSources(listOf(good, alsoGood, wrong)) }
        assertTrue(error.problems.single().startsWith("wrong.colony:3:"), error.message)
        assertContains(error.problems.single(), "SEM_CAPABILITY")
    }

    @Test fun locationsAreMappedAcrossFilesWithAndWithoutTrailingNewlines() {
        val files = listOf(SourceFile("a", "one\ntwo"), SourceFile("b", "three\n"), SourceFile("c", "five"))
        val map = SourceLineMap(files)
        assertEquals("a:1:5", map.locate(1, 5))
        assertEquals("a:2:1", map.locate(2, 1))
        assertEquals("b:1:2", map.locate(3, 2))
        assertEquals("b:2:1", map.locate(4, 1))
        assertEquals("c:1:1", map.locate(5, 1))
    }
}
