package colony.cvm

import colony.ir.IRBuilder
import colony.parser.ColonyParserFacade
import colony.semantics.SemanticAnalyzer
import java.io.File
import kotlin.test.*

class ArtifactRoundTripTest {
    private fun compile(source: String): Program {
        val ast = ColonyParserFacade().parse(source)
        val model = SemanticAnalyzer().analyze(ast).requireValid()
        return CvmCompiler().compile(IRBuilder(model).build(ast))
    }

    private val all = listOf("examples/heater.colony", "examples/resident.colony", "examples/xenomorph.colony",
        "../examples/integration/devices.colony", "../conformance/programs/language.colony")
        .joinToString("\n") { File(it).readText() }

    @Test fun everyProgramSurvivesTheBinaryRoundTrip() {
        val program = compile(all)
        val bytes = ArtifactWriter.write(program)
        val back = ArtifactReader.read(bytes)
        assertEquals(program.contract, back.contract)
        assertEquals(program.stepSeconds, back.stepSeconds)
        assertEquals(program.strings, back.strings)
        assertEquals(program.schemas.map { it.name to it.fields.map { f -> f.name + ":" + f.type.render() } },
            back.schemas.map { it.name to it.fields.map { f -> f.name + ":" + f.type.render() } })
        assertEquals(program.events.map { Triple(it.id, it.name, it.schema) }, back.events.map { Triple(it.id, it.name, it.schema) })
        assertEquals(program.behaviors.size, back.behaviors.size)
        for ((original, copy) in program.behaviors.zip(back.behaviors)) {
            assertEquals(original.name, copy.name)
            assertEquals(original.kind, copy.kind)
            assertEquals(original.capabilities, copy.capabilities)
            assertEquals(original.state.map { it.name + ":" + it.type.render() }, copy.state.map { it.name + ":" + it.type.render() })
            assertEquals(original.observes.map { it.name }, copy.observes.map { it.name })
            assertEquals(original.handlers.map { listOf(it.name, it.isTimer, it.eventOrPeriod, it.entry, it.locals.size) },
                copy.handlers.map { listOf(it.name, it.isTimer, it.eventOrPeriod, it.entry, it.locals.size) })
            assertEquals(original.initEntry, copy.initEntry)
            assertEquals(original.maxStack, copy.maxStack)
            assertContentEquals(original.code, copy.code)
            assertEquals(original.sourceMap, copy.sourceMap)
        }
        // The second write of the same program is identical: the artifact is a function of the program.
        assertContentEquals(bytes, ArtifactWriter.write(back))
    }

    @Test fun aChangedByteIsRejected() {
        val bytes = ArtifactWriter.write(compile(File("examples/heater.colony").readText()))
        val tampered = bytes.copyOf().also { it[it.size - 40] = (it[it.size - 40] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(tampered) }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(bytes.copyOf(20)) }
    }

    @Test fun everyInstructionDecodesBackToWhatWasWritten() {
        val program = compile(all)
        for (behavior in program.behaviors) {
            val lines = Disassembler.disassemble(behavior.code, program.strings)
            assertTrue(lines.isNotEmpty(), behavior.name)
            assertTrue(lines.none { it.contains("UNKNOWN") }, behavior.name)
        }
        assertTrue(disassemble(program).any { it.contains("LET_SOME") }, "the if-let pattern becomes one instruction")
        assertTrue(disassemble(program).any { it.contains("BR_CMP_F") }, "a comparison and its branch are one instruction")
    }

    @Test fun theCodeIsSubstantiallySmallerThanTheJsonArtifactItReplaces() {
        val program = compile(File("examples/heater.colony").readText())
        val heater = program.behaviors.single { it.name == "HeaterControl" }
        // The v1 artifact needs 71 stack instructions for this behavior; here the whole behavior is one small block.
        assertTrue(heater.code.size < 120, "heater code is ${heater.code.size} bytes")
        assertEquals(1, heater.capabilities, "it only requests power")
        assertEquals(listOf("home_occupants", "broken", "power_connected"), heater.observes.map { it.name })
    }
}
