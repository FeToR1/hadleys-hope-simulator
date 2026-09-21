package colony.parser

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.ParseCancellationException

/** 1-based line/column diagnostic suitable for IDE/CLI output. */
data class SyntaxDiagnostic(
    val line: Int,
    val column: Int,
    val message: String,
    val offendingText: String? = null,
)

class ColonySyntaxException(
    val diagnostics: List<SyntaxDiagnostic>,
) : RuntimeException(
    diagnostics.joinToString("\n") {
        val token = it.offendingText?.let { text -> " near '$text'" } ?: ""
        "${it.line}:${it.column}: ${it.message}$token"
    },
)

class CollectingErrorListener : BaseErrorListener() {
    val diagnostics: MutableList<SyntaxDiagnostic> = mutableListOf()

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?,
    ) {
        val tokenText = (offendingSymbol as? Token)?.text
        diagnostics += SyntaxDiagnostic(
            line = line,
            column = charPositionInLine + 1,
            message = msg,
            offendingText = tokenText,
        )
    }
}

/**
 * Optional fast-fail listener for command-line tools where the first error is enough.
 */
class FirstErrorThrowingListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?,
    ) {
        throw ParseCancellationException(
            "${line}:${charPositionInLine + 1}: $msg"
        )
    }
}
