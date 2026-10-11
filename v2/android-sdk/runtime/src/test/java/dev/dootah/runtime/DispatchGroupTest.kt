package dev.dootah.runtime

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

class DispatchGroupTest {
    /**
     * Without an update for this call site the hook reads only snapshot state and returns, so the native body
     * keeps its group position. With an update, `portableEntry` owns the call site's override groups (state and
     * the last result), so a pending re-evaluation keeps the override instead of switching back to native.
     */
    @Test fun missingUpdatesDoNotCreateComposeGroups() {
        val node = ClassNode()
        javaClass.getResourceAsStream("/dev/dootah/runtime/ComposeDispatchKt.class")!!.use {
            ClassReader(it).accept(node, 0)
        }
        for (name in listOf("dispatchValue")) {
            val method = node.methods.single { it.name == name }
            val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertFalse("$name must retain the native body's slot/group position on a miss",
                calls.any { it.name.matches(Regex("(start|end).*Group")) })
            assertFalse(calls.any { it.name.startsWith("produceState") })
        }
    }
}
