package colony.cvm

import colony.compiler.SourceFile
import colony.compiler.compileSource
import colony.compiler.compileSources

fun compileToCvm(source: String, step: String = "1s"): Program = compileSource(source, step).nativeProgram
fun compileToCvm(files: List<SourceFile>, step: String = "1s"): Program = compileSources(files, step).nativeProgram
