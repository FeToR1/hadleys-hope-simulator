package colony.compiler

import colony.cvm.CvmCompiler
import colony.cvm.Program
import colony.ir.IRProgram
import colony.semantics.render
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class Parameter(val name: String, val type: String)
data class EventDefinition(val id: Int, val name: String, val fields: List<Parameter>)
data class HandlerDefinition(val name: String, val eventId: Int? = null, val periodTicks: Long? = null)
data class BehaviorDefinition(val name: String, val kind: String, val params: List<Parameter>,
    val observes: List<String>, val handlers: List<HandlerDefinition>)
/** Binding metadata plus the sole executable program, CVM2. No JSON instruction stream. */
data class CompiledProgram(val nativeProgram: Program, val behaviors: List<BehaviorDefinition>, val events: List<EventDefinition>,
    val contract: Int = nativeProgram.contract, val stepSeconds: String = nativeProgram.stepSeconds)
@Serializable enum class Op { POWER_REQUEST, DAMAGE_REQUEST, MOTION_REQUEST, REPAIR_REQUEST }
val colonyJson = Json { prettyPrint = true; encodeDefaults = false }

internal fun compilePackage(ir: IRProgram): CompiledProgram {
    val native = CvmCompiler().compile(ir)
    return CompiledProgram(native, ir.behaviors.map { behavior ->
        val code = native.behaviors.single { it.name == behavior.name }
        BehaviorDefinition(behavior.name, behavior.targetKind, behavior.params.map { Parameter(it.name, it.type.render()) },
            code.observes.map { it.name }, code.handlers.map { h -> HandlerDefinition(h.name,
                if (h.isTimer) null else h.eventOrPeriod.toInt(), if (h.isTimer) h.eventOrPeriod else null) })
    }, ir.events.map { e -> EventDefinition(e.id, e.name, e.fields.map { Parameter(it.name, it.type.render()) }) })
}
