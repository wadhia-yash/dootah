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
)

public data class AdapterUse(
    public val id: String,
    public val props: List<String>,
)
