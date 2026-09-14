package com.dootah.ui

import androidx.compose.ui.unit.dp

import dev.dootah.contract.PropValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an installed build will and will not accept from a bundle.
 *
 * The rule under test is the one the previous design could not express: a bundle
 * may arrange the components this app has however it likes, and is turned away
 * only when it needs something the app genuinely has not got.
 *
 * The old design named a component by its position among its identical
 * siblings. Deleting the middle of three icon buttons renumbered the rest, so
 * the bundle's "second" was the app's "third" -- a real component under a name
 * that existed, which meant nothing was reported and the screen quietly drew the
 * wrong icon. Guarding against that cost every layout edit: adding, removing or
 * reordering a component fell back to the native screen.
 */
@OptIn(DootahGeneratedApi::class)
class DootahAdaptersTest {

    private val iconButton = "androidx.compose.material3.IconButton(content|modifier|onClick)"
    private val icon = "androidx.compose.material3.Icon(contentDescription|painter|tint)"

    private val registered = mapOf(
        iconButton to "content,modifier,onClick",
        icon to "contentDescription,painter,tint",
    )

    private fun bindings(
        adapters: Map<String, String> = registered,
        capabilities: List<String> = emptyList(),
        handles: List<String> = emptyList(),
        resources: List<String> = emptyList(),
        anchors: List<String> = emptyList(),
        builders: List<String> = emptyList(),
    ) = DootahNativeBindings(
        adapters = dootahAdapters(
            *adapters.map { (id, parameters) -> dootahAdapter(id, parameters) {} }.toTypedArray()
        ),
        capabilities = dootahCapabilities(
            *capabilities.map { id -> dootahCapability(id) {} }.toTypedArray()
        ),
        handles = dootahHandles(*handles.map { name -> dootahHandle(name, Any()) }.toTypedArray()),
        resources = dootahResources(*resources.map { key -> dootahResource(key, 1) }.toTypedArray()),
        anchors = dootahAnchors(*anchors.map { name -> dootahAnchor(name, 0.dp) }.toTypedArray()),
        builders = dootahBuilders(*builders.map { id -> dootahBuilder(id) {} }.toTypedArray()),
    )

    private fun button(onClick: String) = BundleUiNode.Component(
        adapterId = iconButton,
        props = mapOf("onClick" to PropValue.CallbackValue(onClick, arity = 0)),
    )

    /**
     * The edit that used to break a shipped screen.
     *
     * Three identical buttons, and the bundle publishes two of them. There is
     * nothing to renumber, because nothing was ever numbered.
     */
    @Test
    fun `dropping one of several identical components is an ordinary update`() {

        val bindings = bindings(capabilities = listOf("a()", "b()", "c()"))

        val screen = BundleUiNode.Fragment(listOf(button("a()"), button("c()")))

        assertEquals(emptyList<String>(), bindings.shortfall(screen.requirements()))
    }

    @Test
    fun `reordering components is an ordinary update`() {

        val bindings = bindings(capabilities = listOf("a()", "b()"))

        val screen = BundleUiNode.Fragment(listOf(button("b()"), button("a()")))

        assertEquals(emptyList<String>(), bindings.shortfall(screen.requirements()))
    }

    /** One adapter, placed as many times as the bundle likes. */
    @Test
    fun `repeating a component the app registered once is an ordinary update`() {

        val bindings = bindings(capabilities = listOf("a()"))

        val screen = BundleUiNode.Fragment(
            listOf(button("a()"), button("a()"), button("a()"), button("a()"))
        )

        assertEquals(emptyList<String>(), bindings.shortfall(screen.requirements()))
    }

    @Test
    fun `changing what a component is given is an ordinary update`() {

        val bindings = bindings(
            capabilities = listOf("a()"),
            resources = listOf("drawable:brush", "drawable:eraser"),
        )

        val screen = BundleUiNode.Component(
            adapterId = iconButton,
            props = mapOf("onClick" to PropValue.CallbackValue("a()", arity = 0)),
            children = mapOf(
                "content" to listOf(
                    BundleUiNode.Component(
                        adapterId = icon,
                        // A resource this build has, but not the one the source
                        // the APK was built from used here.
                        props = mapOf(
                            "painter" to PropValue.PainterResourceValue("drawable:eraser"),
                        ),
                    )
                )
            ),
        )

        assertEquals(emptyList<String>(), bindings.shortfall(screen.requirements()))
    }

    @Test
    fun `a component this build has not got is refused by name`() {

        val screen = BundleUiNode.Component(adapterId = "app.Sparkline(points)")

        assertEquals(
            listOf("component app.Sparkline(points)"),
            bindings().shortfall(screen.requirements()),
        )
    }

    @Test
    fun `an action this build has not got is refused by name`() {

        assertEquals(
            listOf("action vm.explode()"),
            bindings().shortfall(button("vm.explode()").requirements()),
        )
    }

    @Test
    fun `a value and a resource this build has not got are refused by name`() {

        val screen = BundleUiNode.Component(
            adapterId = icon,
            props = mapOf(
                "painter" to PropValue.PainterResourceValue("drawable:missing"),
                "tint" to PropValue.HandleValue("otherScreensViewModel"),
            ),
        )

        assertEquals(
            listOf("value otherScreensViewModel", "resource drawable:missing"),
            bindings().shortfall(screen.requirements()),
        )
    }

    /**
     * The edit that used to change nothing and say nothing.
     *
     * An adapter passes through only the arguments the APK's own source gave the
     * composable somewhere. Adding an argument to a call and publishing that as
     * a bundle used to render exactly as before: the app had no slot to put the
     * value in, so it drew with the composable's default and logged nothing.
     */
    @Test
    fun `an argument this build never supplied is refused by name`() {

        val bindings = bindings(
            adapters = mapOf(
                iconButton to "content,modifier,onClick",
                icon to "contentDescription,painter",
            ),
            resources = listOf("drawable:share", "string:share"),
        )

        val screen = BundleUiNode.Component(
            adapterId = icon,
            props = mapOf(
                "painter" to PropValue.PainterResourceValue("drawable:share"),
                "contentDescription" to PropValue.StringResourceValue("string:share"),
                "tint" to PropValue.ThemeColorValue("primary"),
            ),
        )

        assertEquals(
            listOf("argument tint on component $icon"),
            bindings.shortfall(screen.requirements()),
        )
    }

    /** An argument the APK does supply may be given anything the bundle likes. */
    @Test
    fun `an argument this build supplies somewhere may be varied`() {

        val bindings = bindings(resources = listOf("drawable:share", "string:share"))

        val screen = BundleUiNode.Component(
            adapterId = icon,
            props = mapOf(
                "painter" to PropValue.PainterResourceValue("drawable:share"),
                "contentDescription" to PropValue.StringResourceValue("string:share"),
                "tint" to PropValue.ThemeColorValue("primary"),
            ),
        )

        assertEquals(emptyList<String>(), bindings.shortfall(screen.requirements()))
    }

    /** Requirements are collected through everything that can hold a component. */
    @Test
    fun `requirements are found inside layouts, fragments and content slots`() {

        val screen = BundleUiNode.Column(
            modifiers = emptyList(),
            children = listOf(
                BundleUiNode.Fragment(
                    listOf(
                        BundleUiNode.Component(
                            adapterId = iconButton,
                            children = mapOf(
                                "content" to listOf(
                                    BundleUiNode.Component(
                                        adapterId = icon,
                                        props = mapOf(
                                            "painter" to
                                                PropValue.PainterResourceValue("drawable:deep"),
                                        ),
                                    )
                                )
                            ),
                        )
                    )
                )
            ),
        )

        assertEquals(listOf("drawable:deep"), screen.requirements().resources)
    }
}
