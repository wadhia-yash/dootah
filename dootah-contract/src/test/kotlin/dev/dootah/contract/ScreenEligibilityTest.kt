package dev.dootah.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared rule for automatic Compose discovery.
 *
 * Both compiler passes ask this the same questions about the same function, in
 * two compilations that may be months apart, and act on the answer permanently:
 * the APK ships interception for what it accepts here and can never gain more.
 * So the rule is tested on its own, away from either compiler.
 */
class ScreenEligibilityTest {

    @Test
    fun `an ordinary composable is taken over without being asked`() {
        assertSame(Eligibility.Eligible, eligibility(screen()))
    }

    @Test
    fun `a composable that produces a value is read, not placed`() {
        assertEquals(
            IneligibleReason.RETURNS_A_VALUE,
            reason(screen().copy(returnsUnit = false)),
        )
    }

    /**
     * A wrapper's content is supplied by its caller, so there is nothing in it
     * for a bundle to own -- and a remotely described body would have nothing to
     * put where the content goes. Every app has dozens of these, which is why
     * they are passed over rather than counted as failures.
     */
    @Test
    fun `a composable taking content is passed over`() {
        assertEquals(
            IneligibleReason.TAKES_COMPOSABLE_CONTENT,
            reason(screen().copy(takesComposableContent = true)),
        )
    }

    @Test
    fun `a composable drawn into its caller's scope is passed over`() {
        assertEquals(
            IneligibleReason.HAS_RECEIVER,
            reason(screen().copy(hasReceiver = true)),
        )
    }

    /**
     * The backend walks declaration containers and never sees a function
     * declared inside another function's body. Accepting one here would put a
     * screen in the bundle that the installed app has no interception for.
     */
    @Test
    fun `a local function is passed over because only one pass can see it`() {
        assertEquals(
            IneligibleReason.LOCAL_FUNCTION,
            reason(screen().copy(isLocal = true)),
        )
    }

    @Test
    fun `a preview is not shipped UI`() {
        assertEquals(IneligibleReason.PREVIEW, reason(screen().copy(isPreview = true)))
    }

    @Test
    fun `something that is not Compose at all is not a screen`() {
        assertEquals(
            IneligibleReason.NOT_COMPOSABLE,
            reason(screen().copy(isComposable = false)),
        )
    }

    /**
     * Reported as the developer's own instruction rather than as whatever else
     * happens to be true about the function, because that is the fact they need
     * to see confirmed.
     */
    @Test
    fun `an opt-out is reported as itself`() {
        assertEquals(
            IneligibleReason.EXCLUDED_BY_ANNOTATION,
            reason(screen().copy(isSuppressed = true, hasReceiver = true)),
        )
    }

    @Test
    fun `a package left out of the filter is passed over`() {
        assertEquals(
            IneligibleReason.EXCLUDED_BY_FILTER,
            reason(screen(), ScreenFilter.of(include = listOf("com.other"), exclude = emptyList())),
        )
    }

    @Test
    fun `every reason a function is skipped is reachable`() {

        val reached = listOf(
            screen().copy(isComposable = false),
            screen().copy(isSuppressed = true),
            screen().copy(returnsUnit = false),
            screen().copy(hasBody = false),
            screen().copy(isLocal = true),
            screen().copy(isInline = true),
            screen().copy(isSuspend = true),
            screen().copy(isPreview = true),
            screen().copy(hasReceiver = true),
            screen().copy(takesComposableContent = true),
        ).map { reason(it) }.toSet() + IneligibleReason.EXCLUDED_BY_FILTER

        assertTrue(
            "unreachable: ${IneligibleReason.entries - reached}",
            reached.containsAll(IneligibleReason.entries),
        )
    }

    private fun eligibility(
        shape: ComposableShape,
        filter: ScreenFilter = ScreenFilter.EVERYTHING,
    ): Eligibility = ScreenEligibility.of(shape, filter)

    private fun reason(
        shape: ComposableShape,
        filter: ScreenFilter = ScreenFilter.EVERYTHING,
    ): IneligibleReason =
        (eligibility(shape, filter) as Eligibility.Ineligible).reason

    /** What an ordinary screen looks like: everything else varies from here. */
    private fun screen() = ComposableShape(
        fqName = "com.example.Toolbox",
        isComposable = true,
        returnsUnit = true,
        hasBody = true,
        isLocal = false,
        isInline = false,
        isSuspend = false,
        isPreview = false,
        hasReceiver = false,
        takesComposableContent = false,
        isSuppressed = false,
    )
}
