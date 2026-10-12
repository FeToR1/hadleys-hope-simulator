package colony.semantics

import colony.compiler.SourceFile
import colony.compiler.compileSources
import java.io.File
import kotlin.test.*

/**
 * Every example that is not a draft has to compile to CVM2. A program that only shows intended
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

    @Test fun everyExampleCompilesToCvm2() {
        for (file in examples()) {
            val source = listOf(SourceFile(file.name, file.readText()))
            val compiled = compileSources(source)
            assertTrue(compiled.behaviors.isNotEmpty(), "${file.name} has no behavior")
            val bytes = colony.cvm.ArtifactWriter.write(compiled.nativeProgram)
            assertContentEquals(bytes, colony.cvm.ArtifactWriter.write(colony.cvm.ArtifactReader.read(bytes)), file.name)
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
