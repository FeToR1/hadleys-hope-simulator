package colony.semantics

import colony.parser.ColonyParserFacade
import kotlin.test.*

/**
 * The contract must say what docs/simulation/trigger-conditions.md says. If the specification changes,
 * change it here in the same commit, and bump CONTRACT_VERSION.
 */
class ContractAlignmentTest {
    private val specification = mapOf(
        "House" to setOf("occupants", "temperature", "power_connected", "water_available", "devices"),
        "Heater" to setOf("home_occupants", "power_connected", "broken", "power_granted"),
        "Kettle" to setOf("home_occupants", "power_connected", "broken", "power_granted", "water_temperature"),
        "Human" to setOf("position", "health", "cold", "reachable_breakables", "home", "workplace"),
        "Xenomorph" to setOf("position", "visible_infrastructure", "visible_humans", "patrol_waypoint"),
        // The crew of a rover: the spec adds materials and speed on top of the jobs it can see.
        "Rover" to setOf("position", "speed_eff", "active_jobs", "materials_remaining", "depot"),
    )

    @Test fun observationsMatchTheSpecificationPerKind() {
        val contracts = SemanticEnvironment().kindContracts
        assertEquals(specification.keys, contracts.keys)
        for ((kind, fields) in specification) assertEquals(fields, contracts.getValue(kind).viewFields.keys, kind)
    }

    private fun codes(source: String) =
        SemanticAnalyzer().analyze(ColonyParserFacade().parse(source)).diagnostics.map { it.code }

    @Test fun kernelEventsMustKeepTheirPayload() {
        assertEquals(emptyList(), codes("event PowerLost {}"))
        assertEquals(emptyList(), codes("event DamageApplied { target: String; amount: Health; reason: String; }"))
        assertEquals(listOf("SEM_KERNEL_EVENT_SCHEMA"), codes("event PowerLost { volts: Int64; }"))
        assertEquals(listOf("SEM_KERNEL_EVENT_SCHEMA"), codes("event DamageApplied { target: String; amount: Real64; reason: String; }"))
        assertEquals(emptyList(), codes("event HeatingDemand { enabled: Bool; }"), "events that are not the kernel's stay free-form")
    }

    @Test fun programsCanReadTheNewObservationsAndRecordFields() {
        val source = """
            behavior H for House {
                state broken: Bool = false;
                every 1s as w { broken = view.devices[0].broken || !view.power_connected || !view.water_available; }
            }
            behavior K for Kettle { state p: Power = 0W; every 1s as w { p = view.power_granted; } }
            behavior R for Human { state hp: Health = 0hp; every 1s as w { hp = view.health; } }
            behavior X for Xenomorph {
                state kind: String = "";
                state hp: Health = 0hp;
                every 1s as w { if let t = nearest(view.visible_humans) { kind = t.kind; hp = t.health; } }
            }
        """
        assertEquals(emptyList(), codes(source))
    }
}
