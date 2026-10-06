package dev.dootah.runtime

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/** E2E/Phase 8: real code-signing rejections never produced verification_failed. */
class VerificationSignalTest {
    // Shapes written by expo-updates' UpdatesLogger/PersistentFileLog (entry, then raw cause lines).
    private val manifest = """{"timestamp":1759400000123,"message":"Code signing verification failed for manifest","code":"UpdateCodeSigningError","level":"error","stacktrace":["java.io.IOException: Incorrect signature"]}"""
    private val directive = """{"level":"error","code":"UpdateCodeSigningError","message":"Code signing verification failed for directive","timestamp":1759400000999}"""
    private val network = """{"timestamp":1759400005000,"message":"Failed to download remote update","code":"Unknown","level":"error"}"""
    private val cause = """Incorrect signature "code":"UpdateCodeSigningError" "timestamp":1759400009999"""
    private val forged = """{"timestamp":1759400007000,"message":"server said \"code\":\"UpdateCodeSigningError\"","code":"None","level":"info"}"""

    @Test fun classifiesOnlyStructuredCodeSigningRejectionsAfterTheCutoff() {
        assertEquals(1759400000999L, VerificationSignal.newestRejection(listOf(manifest, directive, network), 0))
        assertEquals(1759400000123L, VerificationSignal.newestRejection(listOf(manifest), 1759400000000L))
        // Already reported, network-only failures, raw cause lines and escaped message text never count.
        assertNull(VerificationSignal.newestRejection(listOf(manifest, directive), 1759400000999L))
        assertNull(VerificationSignal.newestRejection(listOf(network, cause, forged, "", "{"), 0))
    }

    @Test fun telemetryClassifiesFromUpdaterLogNotEventText() {
        val node = ClassNode().also { n ->
            javaClass.getResourceAsStream("/dev/dootah/runtime/CloudTelemetry.class")!!.use { ClassReader(it).accept(n, 0) }
        }
        val calls = node.methods.single { it.name == "reportVerificationFailure" }.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertTrue(calls.any { it.owner == "expo/modules/updates/logging/UpdatesLogReader" && it.name == "getLogEntries" })
        assertTrue(calls.any { it.owner == "dev/dootah/runtime/VerificationSignal" && it.name == "newestRejection" })
        val all = node.methods.flatMap { m -> m.instructions.toArray().toList() }
        assertFalse(all.any { it is org.objectweb.asm.tree.LdcInsnNode && it.cst == "signature" })
    }
}
