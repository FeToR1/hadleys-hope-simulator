package colony.cli

import colony.bytecode.*
import colony.runtime.*
import colony.cvm.ArtifactWriter
import colony.cvm.NativeVmConnection
import colony.cvm.compileToCvm
import colony.cvm.disassemble as disassembleCvm
import colony.semantics.contractDocument
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        val usage = "Usage: check SOURCE | compile SOURCE OUTPUT | prepare SCENARIO OUTPUT_DIR | run[-native] SCENARIO [OUTPUT.jsonl] | serve[-native] SCENARIO [PORT] | contract [OUTPUT.json] | conformance OUTPUT_DIR | emit SOURCE OUT.cvm | disasm ARTIFACT.cvm"
        if (args.firstOrNull() == "contract") {
            require(args.size in 1..2) { usage }
            val document = bytecodeJson.encodeToString(contractDocument())
            if (args.size == 2) Path.of(args[1]).writeText(document + "\n") else println(document)
            return
        }
        require(args.size in 2..3) { usage }
        val input = Path.of(args[1])
        fun nativeFleet(p: PreparedRun, id: String): VmFleet = ProcessFleet(p,
            NativeVmConnection.executable() ?: error("Build native/ or set HH_VM to the hh-vm executable"), id)
        when (args[0]) {
            "broker" -> { require(args.size == 2); runNativeBroker(input) }
            "check" -> { require(args.size == 2); val code = compileSources(listOf(SourceFile(input.fileName.toString(), input.readText()))); println("OK: ${code.behaviors.size} behaviors, ${code.events.size} events") }
            "compile" -> { require(args.size == 3); Path.of(args[2]).writeText(bytecodeJson.encodeToString(compileSources(listOf(SourceFile(input.fileName.toString(), input.readText()))))) }
            "emit" -> {
                require(args.size == 3) { "Usage: emit SOURCE OUTPUT.cvm" }
                val program = compileToCvm(listOf(SourceFile(input.fileName.toString(), input.readText())))
                Path.of(args[2]).writeBytes(ArtifactWriter.write(program))
                println("Wrote ${program.behaviors.size} behaviors, ${program.behaviors.sumOf { it.code.size }} bytes of code")
            }
            "disasm" -> {
                require(args.size == 2) { "Usage: disasm ARTIFACT.cvm" }
                disassembleCvm(colony.cvm.ArtifactReader.read(input.readBytes())).forEach(::println)
            }
            "conformance" -> {
                require(args.size == 2) { "Usage: conformance OUTPUT_DIR (run from the repository root)" }
                writeConformance(Path.of("").toAbsolutePath(), input)
                println("Wrote ${conformanceFileNames().size} vector files to $input")
            }
            "prepare" -> {
                require(args.size == 3)
                val prepared = prepareScenario(input); val output = Path.of(args[2]); output.createDirectories()
                output.resolve("program.cvm.json").writeText(bytecodeJson.encodeToString(prepared.program))
                output.resolve("program.cvm").writeBytes(ArtifactWriter.write(compileToCvm(prepared.sources, prepared.scenario.step)))
                output.resolve("manifest.json").writeText(bytecodeJson.encodeToString(prepared.manifest))
                println("Prepared ${prepared.manifest.instances.size} VM instances; ${prepared.program.behaviors.size} behaviors")
            }
            "run", "run-native" -> {
                val prepared = prepareScenario(input)
                val id = java.util.UUID.randomUUID().toString()
                val run = ReferenceRun(prepared, id, if (args[0] == "run-native") nativeFleet(prepared, id) else ReferenceFleet(prepared))
                val compact = kotlinx.serialization.json.Json { encodeDefaults = true }
                run.use {
                    val writer = if (args.size == 3) Path.of(args[2]).bufferedWriter() else System.out.bufferedWriter()
                    try { repeat(prepared.scenario.ticks) { writer.appendLine(compact.encodeToString(run.step())) } }
                    finally { writer.flush(); if (args.size == 3) writer.close() }
                }
            }
            "serve" -> serveReference(prepareScenario(input), args.getOrNull(2)?.toInt() ?: 8080)
            "serve-native" -> serveReference(prepareScenario(input), args.getOrNull(2)?.toInt() ?: 8080, ::nativeFleet)
            else -> error("Unknown command ${args[0]}")
        }
    } catch (failure: Exception) { System.err.println(failure.message ?: failure.javaClass.simpleName); exitProcess(1) }
}
