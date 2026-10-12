package colony.ast

data class SettingsDocument(val sources: List<String>, val constants: List<LetStmt>,
    val templates: List<SettingsTemplate>, val populations: List<SettingsPopulation>,
    val properties: List<SettingsNode>, override val span: SourceSpan,
    val includes: List<String> = emptyList(), val changes: List<SettingsChange> = emptyList()) : AstNode

data class SettingsChange(val tick: Expr, val target: String, val properties: List<SettingsNode>,
    override val span: SourceSpan) : AstNode
data class SettingsTemplate(val name: String, val objects: List<SettingsObject>,
    override val span: SourceSpan) : AstNode
data class SettingsObject(val name: String, val behavior: String, val parameters: List<SettingsNode>,
    val properties: List<SettingsNode>, override val span: SourceSpan) : AstNode
data class SettingsPopulation(val template: String, val prefix: String, val properties: List<SettingsNode>,
    override val span: SourceSpan) : AstNode
sealed interface SettingsNode : AstNode { val name: String }
data class SettingsValue(override val name: String, val expression: Expr,
    override val span: SourceSpan) : SettingsNode
data class SettingsList(override val name: String, val elements: List<Expr>,
    override val span: SourceSpan) : SettingsNode
data class SettingsBlock(override val name: String, val properties: List<SettingsNode>,
    override val span: SourceSpan) : SettingsNode
