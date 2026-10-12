package colony.parser

import colony.ast.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.DefaultErrorStrategy

class ColonyParserFacade {
    fun parse(source: String): Program = parseEntry(source, false) as Program
    fun parseSettings(source: String): SettingsDocument = parseEntry(source, true) as SettingsDocument

    private fun parseEntry(source: String, settings: Boolean): AstNode {
        val lexer = ColonyLexer(CharStreams.fromString(source))
        val lexerErrors = CollectingErrorListener()
        lexer.removeErrorListeners()
        lexer.addErrorListener(lexerErrors)

        val tokens = CommonTokenStream(lexer)
        val parser = ColonyParser(tokens)
        val parserErrors = CollectingErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(parserErrors)
        parser.errorHandler = DefaultErrorStrategy()

        val tree = if (settings) parser.settingsProgram() else parser.program()
        val diagnostics = lexerErrors.diagnostics + parserErrors.diagnostics
        if (diagnostics.isNotEmpty()) {
            throw ColonySyntaxException(diagnostics.sortedWith(compareBy({ it.line }, { it.column })))
        }
        return AstBuilder().visit(tree)
    }
}
