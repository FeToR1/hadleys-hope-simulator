package colony.semantics

import colony.ast.Program
import colony.ir.IRBuilder
import colony.ir.IRProgram

/** Stable role #4 entry point; parser ownership remains in role #3. */
class ColonySemanticFrontend(
    private val options: SemanticOptions = SemanticOptions(),
) {
    fun analyze(program: Program): SemanticResult = SemanticAnalyzer(options).analyze(program)

    fun compile(program: Program): IRProgram {
        val result = analyze(program)
        val model = result.requireValid()
        return IRBuilder(model, options.environment).build(program)
    }
}
