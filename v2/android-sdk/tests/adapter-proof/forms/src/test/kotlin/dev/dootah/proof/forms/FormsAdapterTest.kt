package dev.dootah.proof.forms

import androidx.compose.runtime.*
import dev.dootah.runtime.InstalledAdapters
import dev.dootah.runtime.NativeAdapterHost
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

private class NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private fun compose(content: @Composable () -> Unit) {
    val composition = Composition(NoNodes(), Recomposer(EmptyCoroutineContext))
    composition.setContent(content)
    composition.dispose()
}

class FormsAdapterTest {
    private val registry = InstalledAdapters.registry
    private fun id(callee: String, signature: String) =
        registry.signatures.entries.single { it.key.startsWith("dev.dootah.proof.forms.$callee#") && it.value == signature }.key

    @BeforeTest fun clear() { Calls.clear() }

    @Test fun scalarSignaturesAreTypedFromHookInputs() {
        assertEquals(setOf("String,Int,Boolean", "String", "Int"), registry.signatures.values.toSet())
        assertEquals(3, registry.signatures.size)
    }

    @Test fun positionalNamedAndTypedArgumentsInvokeTheNativeComponent() {
        val selected = id("Chip", "String,Int,Boolean")
        compose {
            CompositionLocalProvider(LocalTone provides "accent") {
                NativeAdapterHost.render(registry, selected, listOf("Unread", 3, true))
                NativeAdapterHost.render(registry, selected, listOf("Read", 0, false))
            }
        }
        assertEquals(listOf("Chip(Unread,3,true,accent)", "Chip(Read,0,false,accent)"), Calls)
    }

    @Test fun expressionBodyHookAndFrozenQualifiedConstant() {
        val meter = id("Meter", "Int")
        compose { NativeAdapterHost.render(registry, meter, listOf(7)) }
        assertEquals(listOf("Meter(7/10)"), Calls)
    }

    @Test fun templateArgumentKeepsOtherDefaults() {
        compose { NativeAdapterHost.render(registry, id("Chip", "String"), listOf("All (4)")) }
        assertEquals(listOf("Chip(All (4),0,false,neutral)"), Calls)
    }

    @Test fun refusesBoxedTypeConfusion() {
        val selected = id("Chip", "String,Int,Boolean")
        compose {
            assertFalse(NativeAdapterHost.render(registry, selected, listOf("x", "3", true)))
            assertFalse(NativeAdapterHost.render(registry, selected, listOf("x", 3L, true)))
            assertFalse(NativeAdapterHost.render(registry, selected, listOf("x", 3, null)))
        }
        assertTrue(Calls.isEmpty())
    }
}
