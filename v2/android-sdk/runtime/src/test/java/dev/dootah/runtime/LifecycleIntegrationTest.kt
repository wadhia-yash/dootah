package dev.dootah.runtime

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/** Phase 8: frame health must not require DootahActivity, AppCompat or an AppCompat theme. */
class LifecycleIntegrationTest {
    private fun node(name: String) = ClassNode().also { n ->
        javaClass.getResourceAsStream("/dev/dootah/runtime/$name.class")!!.use { ClassReader(it).accept(n, 0) }
    }
    private fun calls(owner: String, method: String) =
        node(owner).methods.single { it.name == method }.instructions.toArray().filterIsInstance<MethodInsnNode>()

    @Test fun applicationRegistersFrameObserverForEveryActivity() {
        assertTrue(calls("DootahApplication", "onCreate").any { it.name == "registerActivityLifecycleCallbacks" })
        assertTrue(node("NativeFrameObserver").interfaces.contains("android/app/Application\$ActivityLifecycleCallbacks"))
    }

    @Test fun observerNeverInstallsDecorEarlyAndActivityBaseHoldsNoFrameLogic() {
        val track = calls("NativeFrameObserver", "track")
        assertTrue(track.any { it.name == "peekDecorView" })
        assertFalse(track.any { it.name == "getDecorView" })
        val base = node("DootahActivity").methods.map { it.name }
        assertFalse(base.any { it in listOf("onCreate", "onAttachedToWindow", "onDetachedFromWindow") })
    }
}
