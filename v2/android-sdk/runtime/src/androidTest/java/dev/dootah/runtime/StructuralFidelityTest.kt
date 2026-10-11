package dev.dootah.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dootah.fixture.Header
import dev.dootah.portable.PortableProgram
import dev.dootah.runtime.sandbox.RestrictedSandbox
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Device evidence for the unchanged ordinary-source body; no screenshot goldens from another renderer. */
class StructuralFidelityTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(InternalComposeApi::class)
    @Test fun missingOverrideExecutesNativeBodyThroughUnchangedHookAbi() {
        rule.setContent {
            MaterialTheme {
                if (!ComposeEntry.tryRenderV2("dth1:" + "a".repeat(64), "String", arrayOf("Native fallback"), currentComposer, 0)) {
                    Header("Native fallback")
                }
            }
        }
        rule.onNodeWithText("Native fallback").assertExists()
    }

    @Test fun originalAndLoweredHeaderHaveIdenticalPixelsInCallerThemeAndLayoutDirection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = instrumentation.context.assets.open("header.json").bufferedReader().use { it.readText() }
        val program = PortableProgram(raw, "structural-fixture")
        val id = program.functionIds().single()
        val sandbox = RestrictedSandbox(instrumentation.targetContext)
        val output = try { sandbox.evaluate(program.source(id, listOf("String"), listOf("Theme"))) }
            finally { sandbox.close() }
        val result = program.renderResult(id, output)
        val remote = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val rtl = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val colors = if (dark.value) darkColorScheme(secondary = Color(0xffeb9fec))
                    else lightColorScheme(secondary = Color(0xff573285))
                MaterialTheme(colorScheme = colors, typography = Typography(
                    bodyMedium = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
                )) {
                    Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface).testTag("capture")) {
                        if (remote.value) renderTree(result.root, result.slots) else Header("Theme")
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (rightToLeft in listOf(false, true)) {
            rule.runOnIdle { dark.value = night; rtl.value = rightToLeft; remote.value = false }
            rule.onNodeWithText("Theme").assertExists()
            val native = rule.onNodeWithTag("capture").captureToImage().asAndroidBitmap()
            rule.runOnIdle { remote.value = true }
            rule.onNodeWithText("Theme").assertExists()
            val portable = rule.onNodeWithTag("capture").captureToImage().asAndroidBitmap()
            assertEquals(native.width, portable.width)
            assertEquals(native.height, portable.height)
            assertTrue("Native pixels differ: dark=$night rtl=$rightToLeft", native.sameAs(portable))
        }
    }
}
