package colony.cvm

import colony.bytecode.SourceFile
import colony.runtime.VmFrame
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeBatchVmConnectionTest {
    @Test fun sharedProcessKeeps513ContextStatesIndependentAcrossBoundedPackets() {
        val executable = NativeVmConnection.executable() ?: return
        val program = compileToCvm(listOf(SourceFile("batch.cly", """
            behavior Meter for Human {
                param start: Int64;
                state count: Int64 = start;
                every 1s as increment { count = count + 1; }
            }
        """)))
        val artifact = Files.createTempFile("native-batch-", ".cvm")
        try {
            Files.write(artifact, ArtifactWriter.write(program))
            val contexts = (0..512).map { index ->
                NativeBatchVmConnection.ContextInit("human-$index", "Meter", 426, buildJsonObject { put("start", index) })
            }
            NativeBatchVmConnection(executable, artifact, program, contexts, "batch-isolation").use { vm ->
                assertEquals(1, vm.contextReady.values.map { it.pid }.toSet().size)
                assertEquals(513, vm.contextReady.size)
                val frames = contexts.associate { it.entityId to VmFrame(0, JsonObject(emptyMap())) }
                val first = vm.step(0, frames)
                assertEquals(1L, first.getValue("human-0").state.getValue("count").jsonPrimitive.long)
                assertEquals(513L, first.getValue("human-512").state.getValue("count").jsonPrimitive.long)
                val nextFrames = contexts.associate { it.entityId to VmFrame(1, JsonObject(emptyMap())) }
                val second = vm.step(1, nextFrames)
                assertEquals(2L, second.getValue("human-0").state.getValue("count").jsonPrimitive.long)
                assertEquals(514L, second.getValue("human-512").state.getValue("count").jsonPrimitive.long)
                assertTrue(second.values.all { it.failure == null })
            }
        } finally { Files.deleteIfExists(artifact) }
    }
}
