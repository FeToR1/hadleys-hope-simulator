package colony.semantics

import colony.bytecode.bytecodeJson
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.*

class ContractDocumentTest {
    @Test fun theCommittedContractDocumentIsCurrent() {
        val expected = bytecodeJson.encodeToString(contractDocument()) + "\n"
        val actual = File("../docs/generated/contract.json").readText().replace("\r\n", "\n")
        assertEquals(expected, actual, "docs/generated/contract.json is stale; regenerate it: colony-dsl-parser contract docs/generated/contract.json")
    }

    @Test fun everyIntrinsicAndKindIsDocumented() {
        val document = contractDocument().toString()
        for (name in Intrinsics.all().keys) assertContains(document, "\"$name\"")
        for (kind in SemanticEnvironment().kindContracts.keys) assertContains(document, "\"$kind\"")
        for (event in KernelEvents.catalog.keys) assertContains(document, "\"$event\"")
    }
}
