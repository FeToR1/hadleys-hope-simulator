package colony.bytecode

import colony.ir.IRBuilder
import colony.parser.ColonyParserFacade
import colony.semantics.SemanticAnalyzer
import colony.semantics.SemanticOptions

/** All sources form one package, so event IDs and cross-file references share a namespace. */
fun compileSource(source: String, step: String = "1s"): BytecodeProgram {
    val ast = ColonyParserFacade().parse(source)
    val model = SemanticAnalyzer(SemanticOptions(deltaTime = step)).analyze(ast).requireValid()
    return BytecodeCompiler().compile(IRBuilder(model).build(ast))
}
