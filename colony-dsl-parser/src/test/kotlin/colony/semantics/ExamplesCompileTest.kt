package colony.semantics

import colony.bytecode.SourceFile
import colony.bytecode.compileSources
import colony.cvm.compileToCvm
import java.io.File
import kotlin.test.*

/**
 * Every example that is not a draft has to compile through both back ends. A program that only shows intended
 * syntax belongs in examples/drafts, which this test leaves alone.
 */
class ExamplesCompileTest {
    private fun isDraft(file: File) = file.path.replace(File.separatorChar, '/').contains("/drafts/")

    private fun examples(): List<File> =
        (File("examples").walkTopDown() + File("../examples").walkTopDown())
            .filter { it.isFile && it.extension == "colony" && !isDraft(it) }
            .sortedBy { it.path }
            .toList()

    @Test fun thereAreExamplesToCheck() {
        val found = examples().map { it.name }
        assertTrue(found.size >= 5, "found only $found")
        assertContains(found, "heater.colony")
        assertContains(found, "settlement.colony")
    }

    @Test fun everyExampleCompilesToBothArtifacts() {
        for (file in examples()) {
            val source = listOf(SourceFile(file.name, file.readText()))
            val v1 = compileSources(source)
            val v2 = compileToCvm(source)
            assertTrue(v1.behaviors.isNotEmpty(), "${file.name} has no behavior")
            assertEquals(v1.behaviors.map { it.name }, v2.behaviors.map { it.name }, file.name)
            assertEquals(v1.events.map { it.id to it.name }, v2.events.map { it.id to it.name }, file.name)
        }
    }

    @Test fun theDraftsAreStillThereAndStillDoNotCompile() {
        val drafts = File("examples/drafts").listFiles().orEmpty().filter { it.extension == "colony" }
        assertTrue(drafts.isNotEmpty(), "the drafts folder explains what is not supported yet")
        for (draft in drafts) {
            // If one of these starts compiling, its kind has joined the contract and it should move up a folder.
            assertFails(draft.name) { compileSources(listOf(SourceFile(draft.name, draft.readText()))) }
        }
    }
}
