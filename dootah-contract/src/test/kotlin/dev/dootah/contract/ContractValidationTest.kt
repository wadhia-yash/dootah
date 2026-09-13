package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this exists to enforce is one sentence: a bundle is refused when it
 * needs native code, an action, a value or a resource the installed binary
 * genuinely does not contain, and for no other reason.
 *
 * Half of these tests are therefore about what must *not* be refused. A design
 * that rejected rearranged components would be safe and useless -- adding,
 * removing, reordering and repeating them is what an update is for.
 */
class ContractValidationTest {

    private val iconButton = "androidx.compose.material3.IconButton(content|modifier|onClick)"
    private val icon = "androidx.compose.material3.Icon(contentDescription|modifier|painter)"

    private val installed = InstalledContract(
        runtimeVersion = "3",
        screens = listOf(
            ScreenContract(
                id = "app.Toolbox",
                adapters = listOf(
                    AdapterContract(iconButton, listOf("onClick", "modifier", "content")),
                    AdapterContract(icon, listOf("painter", "contentDescription")),
                ),
                capabilities = listOf(
                    CapabilityContract("menu.value=true", arity = 0),
                    CapabilityContract("vm.changeBrush(\$0)", arity = 1),
                ),
                handles = listOf("customBrushes", "menu"),
                resources = listOf("drawable:brush", "drawable:eraser", "string:brush"),
            )
        ),
    )

    private fun requires(
        adapters: List<AdapterUse> = emptyList(),
        capabilities: List<CapabilityContract> = emptyList(),
        handles: List<String> = emptyList(),
        resources: List<String> = emptyList(),
        runtimeVersion: String = "3",
    ) = BundleRequirements(
        runtimeVersion = runtimeVersion,
        screens = listOf(
            ScreenRequirements("app.Toolbox", adapters, capabilities, handles, resources)
        ),
    )

    private fun findings(required: BundleRequirements) =
        ContractValidation.validate(installed, required).filter { it.isFatal }

    // ---- what must pass --------------------------------------------------

    @Test
    fun `placing a component more often than the app's source did is allowed`() {

        val required = requires(
            adapters = List(6) { AdapterUse(iconButton, listOf("onClick", "content")) },
            capabilities = listOf(CapabilityContract("menu.value=true", 0)),
        )

        assertEquals(emptyList<ContractValidation.Finding>(), findings(required))
    }

    @Test
    fun `placing a component fewer times, or not at all, is allowed`() {

        assertEquals(emptyList<ContractValidation.Finding>(), findings(requires()))
    }

    @Test
    fun `giving a component different arguments from the app's source is allowed`() {

        val required = requires(
            adapters = listOf(AdapterUse(icon, listOf("painter"))),
            resources = listOf("drawable:eraser"),
        )

        assertEquals(emptyList<ContractValidation.Finding>(), findings(required))
    }

    @Test
    fun `attaching an existing action to a different component is allowed`() {

        val required = requires(
            adapters = listOf(AdapterUse(icon, listOf("painter"))),
            capabilities = listOf(CapabilityContract("menu.value=true", 0)),
            resources = listOf("drawable:brush"),
        )

        assertEquals(emptyList<ContractValidation.Finding>(), findings(required))
    }

    // ---- what must fail --------------------------------------------------

    @Test
    fun `a component the app has never drawn needs a new app`() {

        val required = requires(adapters = listOf(AdapterUse("app.Sparkline(points)", emptyList())))

        assertEquals(
            listOf(ContractValidation.Code.MISSING_ADAPTER),
            findings(required).map { it.code },
        )
    }

    @Test
    fun `an argument the app's adapter cannot pass needs a new app`() {

        val required = requires(adapters = listOf(AdapterUse(iconButton, listOf("enabled"))))

        val finding = findings(required).single()

        assertEquals(ContractValidation.Code.UNSUPPORTED_PROP, finding.code)
        assertTrue(finding.render(), finding.render().contains("enabled"))
    }

    @Test
    fun `an action the app has not got needs a new app`() {

        val required = requires(capabilities = listOf(CapabilityContract("vm.deleteEverything()", 0)))

        assertEquals(
            listOf(ContractValidation.Code.MISSING_CAPABILITY),
            findings(required).map { it.code },
        )
    }

    /**
     * An action is called by the component it is attached to, with the values
     * that component produces. Attaching one that takes a different number of
     * values fails inside the app, where the only evidence is a stack trace on
     * someone's device.
     */
    @Test
    fun `an action attached where it takes a different number of values is refused`() {

        val required = requires(capabilities = listOf(CapabilityContract("menu.value=true", 2)))

        assertEquals(
            listOf(ContractValidation.Code.CAPABILITY_ARITY_CHANGED),
            findings(required).map { it.code },
        )
    }

    @Test
    fun `a resource the app does not ship needs a new app`() {

        val required = requires(resources = listOf("drawable:sparkle"))

        val finding = findings(required).single()

        assertEquals(ContractValidation.Code.MISSING_RESOURCE, finding.code)
        assertTrue(finding.render(), finding.render().contains("cannot add one"))
    }

    @Test
    fun `a value the screen does not have needs a new app`() {

        val required = requires(handles = listOf("otherScreensViewModel"))

        assertEquals(
            listOf(ContractValidation.Code.MISSING_HANDLE),
            findings(required).map { it.code },
        )
    }

    @Test
    fun `a bundle built for a different runtime is refused`() {

        assertEquals(
            listOf(ContractValidation.Code.RUNTIME_VERSION_MISMATCH),
            findings(requires(runtimeVersion = "2")).map { it.code },
        )
    }

    /**
     * A bundle may implement a screen this build has not got. The app keeps its
     * native one, which is the same path as a screen the bundle never
     * implemented -- so this is worth saying, not worth failing over.
     */
    @Test
    fun `a screen the app does not have is reported without failing`() {

        val required = BundleRequirements(
            runtimeVersion = "3",
            screens = listOf(ScreenRequirements("app.Unknown", emptyList(), emptyList(), emptyList(), emptyList())),
        )

        val all = ContractValidation.validate(installed, required)

        assertEquals(listOf(ContractValidation.Code.SCREEN_NOT_INSTALLED), all.map { it.code })
        assertEquals(emptyList<ContractValidation.Finding>(), all.filter { it.isFatal })
    }
}
