package colony.cli

import colony.bytecode.*
import colony.runtime.*
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.size in 2..3) { "Usage: check SOURCE | compile SOURCE OUTPUT | prepare SCENARIO OUTPUT_DIR | run SCENARIO [OUTPUT.jsonl] | serve SCENARIO [PORT]" }
        val input = Path.of(args[1])
        when (args[0]) {
            "check" -> { require(args.size == 2); val code = compileSources(listOf(SourceFile(input.fileName.toString(), input.readText()))); println("OK: ${code.behaviors.size} behaviors, ${code.events.size} events") }
            "compile" -> { require(args.size == 3); Path.of(args[2]).writeText(bytecodeJson.encodeToString(compileSources(listOf(SourceFile(input.fileName.toString(), input.readText()))))) }
            "prepare" -> {
                require(args.size == 3)
                val prepared = prepareScenario(input); val output = Path.of(args[2]); output.createDirectories()
                output.resolve("program.cvm.json").writeText(bytecodeJson.encodeToString(prepared.program))
                output.resolve("manifest.json").writeText(bytecodeJson.encodeToString(prepared.manifest))
                println("Prepared ${prepared.manifest.instances.size} VM instances; ${prepared.program.behaviors.size} behaviors")
            }
            "run" -> {
                val prepared = prepareScenario(input); val run = ReferenceRun(prepared)
                val compact = kotlinx.serialization.json.Json { encodeDefaults = true }
                val writer = if (args.size == 3) Path.of(args[2]).bufferedWriter() else System.out.bufferedWriter()
                try { repeat(prepared.scenario.ticks) { writer.appendLine(compact.encodeToString(run.step())) } }
                finally { writer.flush(); if (args.size == 3) writer.close() }
            }
            "serve" -> serveReference(prepareScenario(input), args.getOrNull(2)?.toInt() ?: 8080)
            else -> error("Unknown command ${args[0]}")
        }
    } catch (failure: Exception) { System.err.println(failure.message ?: failure.javaClass.simpleName); exitProcess(1) }
}
