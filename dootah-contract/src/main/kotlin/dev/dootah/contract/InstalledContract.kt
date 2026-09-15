package dev.dootah.contract

/**
 * What an installed binary can be asked for.
 *
 * Produced when the APK is built and checked against when a bundle is published,
 * which are the only two moments at which both sides are knowable. The point is
 * that a bundle needing something the binary has not got is a build failure on
 * the developer's machine rather than a gap on a user's screen.
 *
 * What it records is deliberately narrow: which adapters exist, which arguments
 * each accepts, which actions exist and how many values they take, which of the
 * screen's own values may be routed into a component, and which resources may be
 * named. It records nothing about how many of anything there were or where they
 * sat, because rearranging those is what an update is *for* -- a contract that
 * pinned them would turn every layout edit into a rejection.
 */
public data class InstalledContract(
    public val schemaVersion: Int = SCHEMA_VERSION,
    public val runtimeVersion: String,
    public val screens: List<ScreenContract>,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

public data class ScreenContract(
    public val id: String,
    public val adapters: List<AdapterContract>,
    public val capabilities: List<CapabilityContract>,
    public val handles: List<String>,
    public val resources: List<String>,

    /**
     * The app's own values this screen lets a bundle name -- see [AnchorId].
     *
     * Recorded like resources and for the same reason: the number lives in the
     * APK and only the name travels, so what has to be checked is whether this
     * build still reads that property in that screen.
     */
    public val anchors: List<String> = emptyList(),

    /**
     * The builder regions this screen lets a bundle place -- see [BuilderEntries].
     *
     * Recorded apart from [adapters] because they are a different kind of thing
     * in the binary: an adapter draws when the bundle places it, a builder
     * region declares entries against a scope the app makes. A build that has
     * the adapter and not the region would pass a check that counted them
     * together and then show an empty list.
     */
    public val builders: List<String> = emptyList(),

    /**
     * The screen's own callback parameters a bundle may invoke -- see [CallbackId].
     *
     * Recorded with their parameter types, not just their names, because the
     * values a bundle sends are coerced to those types on arrival. A name alone
     * would let a build that changed `(String) -> Unit` to `(Int) -> Unit` go on
     * accepting a bundle that sends it text.
     */
    public val callbacks: List<String> = emptyList(),
)

/**
 * One native composable, and the arguments this build passes through to it.
 *
 * [supportedProps] is what the generated adapter reads from the bundle, which is
 * the union of the arguments the app's own source gives that composable
 * anywhere. A bundle may vary any of them freely. One outside the set is not a
 * different arrangement of what the binary has -- the binary has no way to pass
 * it at all -- so it needs a new binary.
 */
public data class AdapterContract(
    public val id: String,
    public val supportedProps: List<String>,
)

/**
 * One native action.
 *
 * [arity] is how many values the component hands back when it runs. It is
 * checked because attaching an action to a component that calls it with a
 * different number of values would fail inside the app, where the only evidence
 * is a stack trace on someone's device.
 */
public data class CapabilityContract(
    public val id: String,
    public val arity: Int,
)

/** What a published bundle needs, read back out of the bundle it was built with. */
public data class BundleRequirements(
    public val runtimeVersion: String,
    public val screens: List<ScreenRequirements>,
)

public data class ScreenRequirements(
    public val id: String,
    public val adapters: List<AdapterUse>,
    public val capabilities: List<CapabilityContract>,
    public val handles: List<String>,
    public val resources: List<String>,
    public val anchors: List<String> = emptyList(),
    public val builders: List<String> = emptyList(),
    public val callbacks: List<String> = emptyList(),
)

public data class AdapterUse(
    public val id: String,
    public val props: List<String>,
)
