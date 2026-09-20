package colony.semantics

import colony.ast.SourceSpan

sealed interface Symbol {
    val name: String
    val type: Type
    val span: SourceSpan

    data class Param(
        override val name: String,
        override val type: Type,
        override val span: SourceSpan,
    ) : Symbol

    data class State(
        override val name: String,
        override val type: Type,
        val slot: Int,
        override val span: SourceSpan,
    ) : Symbol

    data class Local(
        override val name: String,
        override val type: Type,
        override val span: SourceSpan,
    ) : Symbol

    data class Message(
        override val name: String,
        override val type: Type.Event,
        override val span: SourceSpan,
    ) : Symbol

    data class EnumValueSymbol(
        override val name: String,
        override val type: Type.EnumValue,
        override val span: SourceSpan,
    ) : Symbol

    data class Implicit(
        override val name: String,
        override val type: Type,
        override val span: SourceSpan = SourceSpan.synthetic(),
    ) : Symbol
}

class Scope private constructor(
    private val parent: Scope?,
) {
    private val declarations = linkedMapOf<String, Symbol>()

    internal fun declare(symbol: Symbol, sink: DiagnosticSink) {
        if (declarations.containsKey(symbol.name)) {
            val previous = declarations.getValue(symbol.name)
            sink.error(
                code = "SEM_DUPLICATE",
                span = symbol.span,
                ru = "Повторное объявление '${symbol.name}'; ранее объявлено в ${previous.span.startLine}:${previous.span.startColumn}",
                en = "Duplicate declaration '${symbol.name}'; already declared at ${previous.span.startLine}:${previous.span.startColumn}",
            )
            return
        }
        declarations[symbol.name] = symbol
    }

    fun lookupLocal(name: String): Symbol? = declarations[name]

    fun lookup(name: String): Symbol? = declarations[name] ?: parent?.lookup(name)

    fun snapshot(): Map<String, Symbol> = declarations.toMap()

    fun child(): Scope = Scope(this)

    companion object {
        fun root(): Scope = Scope(null)
    }
}

class SymbolTable {
    val global: Scope = Scope.root()
    private val behaviorScopes = linkedMapOf<String, Scope>()

    fun createBehaviorScope(name: String): Scope = global.child().also { behaviorScopes[name] = it }
    fun behaviorScope(name: String): Scope? = behaviorScopes[name]
}
