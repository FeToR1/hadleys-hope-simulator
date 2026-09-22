package colony.cvm

import colony.bytecode.ColonyCompilationException
import colony.bytecode.SourceFile
import colony.bytecode.SourceLineMap
import colony.ir.IRBuilder
import colony.parser.ColonyParserFacade
import colony.parser.ColonySyntaxException
import colony.semantics.SemanticAnalyzer
import colony.semantics.SemanticException
import colony.semantics.SemanticOptions

/** Source text to a CVM v2 program: the same front end as the v1 artifact, a different back end. */
fun compileToCvm(source: String, step: String = "1s"): Program {
    val ast = ColonyParserFacade().parse(source)
    val model = SemanticAnalyzer(SemanticOptions(deltaTime = step)).analyze(ast).requireValid()
    return CvmCompiler().compile(IRBuilder(model).build(ast))
}

/** Compiles several files as one package and reports diagnostics as file:line:column. */
fun compileToCvm(files: List<SourceFile>, step: String = "1s"): Program {
    require(files.isNotEmpty()) { "At least one source file is required" }
    val map = SourceLineMap(files)
    try {
        return compileToCvm(files.joinToString("\n") { it.text }, step)
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
