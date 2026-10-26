package colony.runtime

import colony.ast.SettingsDocument
import colony.parser.ColonyParserFacade
import java.nio.file.Path
import kotlin.io.path.readText

/** Includes are declaration libraries; every source path belongs to its declaring file. */
internal fun loadSettingsPackage(path: Path): SettingsDocument {
    val active = linkedSetOf<Path>()
    val loaded = mutableSetOf<Path>()
    val libraries = mutableListOf<SettingsDocument>()
    fun load(input: Path, library: Boolean): SettingsDocument? {
        require(input.fileName.toString().endsWith(".settings")) { "Expected a .settings file: $input" }
        val file = input.toRealPath()
        require(file !in active) { "Settings include cycle: ${(active.toList() + listOf(file)).joinToString(" -> ")}" }
        if (!loaded.add(file)) return null
        active.add(file)
        val ast = try { ColonyParserFacade().parseSettings(file.readText()) }
            catch (failure: Exception) { throw IllegalArgumentException("$file: ${failure.message}", failure) }
        require(ast.includes.distinct().size == ast.includes.size) { "$file: duplicate include" }
        if (library) require(ast.populations.isEmpty() && ast.properties.isEmpty() && ast.changes.isEmpty()) {
            "$file: included settings may declare only sources, constants, templates and includes"
        }
        ast.includes.forEach { load(file.parent.resolve(it), true) }
        active.remove(file)
        val resolved = ast.copy(sources = ast.sources.map { file.parent.resolve(it).normalize().toString() })
        if (library) libraries += resolved
        return resolved
    }
    val root = load(path, false)!!
    val documents = libraries + root
    return root.copy(sources = documents.flatMap { it.sources }, constants = documents.flatMap { it.constants },
        templates = documents.flatMap { it.templates }, includes = emptyList())
}
