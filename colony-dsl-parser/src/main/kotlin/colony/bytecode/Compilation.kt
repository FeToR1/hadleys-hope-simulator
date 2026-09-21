package colony.bytecode

import colony.ir.IRBuilder
import colony.parser.ColonyParserFacade
import colony.parser.ColonySyntaxException
import colony.semantics.SemanticAnalyzer
import colony.semantics.SemanticException
import colony.semantics.SemanticOptions

/** All sources form one package, so event IDs and cross-file references share a namespace. */
fun compileSource(source: String, step: String = "1s"): BytecodeProgram {
    val ast = ColonyParserFacade().parse(source)
    val model = SemanticAnalyzer(SemanticOptions(deltaTime = step)).analyze(ast).requireValid()
    return BytecodeCompiler().compile(IRBuilder(model).build(ast))
}

/** A named piece of source text of one compilation package. */
data class SourceFile(val name: String, val text: String)

/** Compilation failure whose diagnostics already point to the original file and line. */
class ColonyCompilationException(val problems: List<String>) : RuntimeException(problems.joinToString("\n"))

/**
 * Compiles several files as one package and reports every diagnostic as `file:line:column`.
 * The files are concatenated (one newline between them), so the result is identical to [compileSource];
 * only the error locations are mapped back from the combined text to the file they came from.
 */
fun compileSources(files: List<SourceFile>, step: String = "1s"): BytecodeProgram {
    require(files.isNotEmpty()) { "At least one source file is required" }
    val map = SourceLineMap(files)
    try {
        return compileSource(files.joinToString("\n") { it.text }, step)
    } catch (failure: ColonySyntaxException) {
        throw ColonyCompilationException(failure.diagnostics.map {
            val token = it.offendingText?.let { text -> " near '$text'" } ?: ""
            "${map.locate(it.line, it.column)}: ${it.message}$token"
        })
    } catch (failure: SemanticException) {
        throw ColonyCompilationException(failure.diagnostics.map {
            "${map.locate(it.span.startLine, it.span.startColumn)}: [${it.code}] ${it.messageRu} / ${it.messageEn}"
        })
    }
}

/** Maps a 1-based line of the concatenated package back to `file:line:column`. */
internal class SourceLineMap(private val files: List<SourceFile>) {
    private val firstLine: IntArray = IntArray(files.size).also { starts ->
        var next = 1
        files.forEachIndexed { index, file ->
            starts[index] = next
            next += file.text.count { it == '\n' } + 1
        }
    }

    fun locate(line: Int, column: Int): String {
        val index = firstLine.indexOfLast { it <= line }.coerceAtLeast(0)
        return "${files[index].name}:${line - firstLine[index] + 1}:$column"
    }
}
