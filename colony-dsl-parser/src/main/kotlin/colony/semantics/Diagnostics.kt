package colony.semantics

import colony.ast.SourceSpan

enum class DiagnosticSeverity { ERROR, WARNING }

data class SemanticDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val span: SourceSpan,
    val messageRu: String,
    val messageEn: String,
) {
    override fun toString(): String =
        "${span.startLine}:${span.startColumn}: [$code] $messageRu / $messageEn"
}

class SemanticException(
    val diagnostics: List<SemanticDiagnostic>,
) : RuntimeException(diagnostics.joinToString("\n"))

class DiagnosticSink {
    private val _items = mutableListOf<SemanticDiagnostic>()
    val items: List<SemanticDiagnostic> get() = _items

    fun error(code: String, span: SourceSpan, ru: String, en: String) {
        _items += SemanticDiagnostic(DiagnosticSeverity.ERROR, code, span, ru, en)
    }

    fun isEmpty(): Boolean = _items.isEmpty()
}
