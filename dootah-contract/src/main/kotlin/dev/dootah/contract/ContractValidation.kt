package dev.dootah.contract

/**
 * Checks a published bundle against an installed binary.
 *
 * The rule is one sentence: a bundle is refused when it needs native code, an
 * action, a value or a resource the binary genuinely does not contain, and for
 * no other reason. Adding, removing, reordering and repeating components, and
 * changing what they are given, are ordinary updates and must pass.
 *
 * Pure, with no I/O and no build-system types, so the rule can be tested as a
 * rule rather than through a build.
 */
public object ContractValidation {

    public fun validate(
        installed: InstalledContract,
        required: BundleRequirements,
    ): List<Finding> {

        val findings = mutableListOf<Finding>()

        if (installed.runtimeVersion != required.runtimeVersion) {
            findings += Finding(
                code = Code.RUNTIME_VERSION_MISMATCH,
                screen = null,
                detail = "this bundle targets runtime '${required.runtimeVersion}', " +
                    "the installed app implements '${installed.runtimeVersion}'",
            )
        }

        val byId = installed.screens.associateBy { screen -> screen.id }

        for (screen in required.screens) {

            val available = byId[screen.id]

            if (available == null) {
                // Not a failure. A bundle may implement a screen this build does
                // not have; the app simply keeps its native one, which is the
                // same path as a screen the bundle never implemented.
                findings += Finding(Code.SCREEN_NOT_INSTALLED, screen.id, screen.id)
                continue
            }

            findings += screen.check(available)
        }

        return findings
    }

    private fun ScreenRequirements.check(installed: ScreenContract): List<Finding> {

        val findings = mutableListOf<Finding>()

        val adapters = installed.adapters.associateBy { adapter -> adapter.id }

        for (use in this.adapters) {

            val adapter = adapters[use.id]

            if (adapter == null) {
                findings += Finding(Code.MISSING_ADAPTER, id, use.id)
                continue
            }

            // The binary can only pass what its generated adapter passes. An
            // argument outside that set is not a rearrangement of what shipped.
            use.props.filterNot { prop -> prop in adapter.supportedProps }
                .forEach { prop ->
                    findings += Finding(
                        code = Code.UNSUPPORTED_PROP,
                        screen = id,
                        detail = "${use.id} cannot be given '$prop'",
                    )
                }
        }

        val capabilities = installed.capabilities.associateBy { capability -> capability.id }

        for (capability in this.capabilities) {

            val available = capabilities[capability.id]

            if (available == null) {
                findings += Finding(Code.MISSING_CAPABILITY, id, capability.id)
                continue
            }

            if (available.arity != capability.arity) {
                findings += Finding(
                    code = Code.CAPABILITY_ARITY_CHANGED,
                    screen = id,
                    detail = "${capability.id} takes ${available.arity} value(s) in the " +
                        "installed app and ${capability.arity} here",
                )
            }
        }

        // A callback is checked by name *and* signature: the values the bundle
        // sends are coerced to the declared types on arrival, so a build that
        // kept the name and changed the types cannot carry this bundle.
        for (callback in callbacks) {

            if (callback in installed.callbacks) continue

            val name = CallbackId.nameOf(callback)
            val installedShape = installed.callbacks
                .firstOrNull { candidate -> CallbackId.nameOf(candidate) == name }

            findings += if (installedShape == null) {
                Finding(Code.MISSING_CALLBACK, id, callback)
            } else {
                Finding(
                    code = Code.CALLBACK_SIGNATURE_CHANGED,
                    screen = id,
                    detail = "'$name' is $installedShape in the installed app " +
                        "and $callback here",
                )
            }
        }

        handles.filterNot { handle -> handle in installed.handles }
            .forEach { handle -> findings += Finding(Code.MISSING_HANDLE, id, handle) }

        resources.filterNot { resource -> resource in installed.resources }
            .forEach { resource -> findings += Finding(Code.MISSING_RESOURCE, id, resource) }

        // An anchor is the app's own number, so the check is whether this build
        // still reads that property in this screen. Deleting the line that read
        // it is exactly the edit that has to be caught: the bundle would go on
        // naming a value the APK no longer computes.
        anchors.filterNot { anchor -> anchor in installed.anchors }
            .forEach { anchor -> findings += Finding(Code.MISSING_ANCHOR, id, anchor) }

        // A builder region is named by the text it was written as, so editing
        // the app's own list-building code renames it. A bundle that named the
        // old one has to be refused here, rather than publishing a list whose
        // entries the installed app cannot perform.
        builders.filterNot { builder -> builder in installed.builders }
            .forEach { builder -> findings += Finding(Code.MISSING_BUILDER, id, builder) }

        return findings
    }

    public data class Finding(
        public val code: Code,
        public val screen: String?,
        public val detail: String,
    ) {
        public val isFatal: Boolean get() = code.isFatal

        public fun render(): String {
            val where = if (screen == null) "" else "$screen: "
            return where + code.describe(detail)
        }
    }

    public enum class Code(public val isFatal: Boolean) {

        RUNTIME_VERSION_MISMATCH(true),
        MISSING_ADAPTER(true),
        UNSUPPORTED_PROP(true),
        MISSING_CAPABILITY(true),
        CAPABILITY_ARITY_CHANGED(true),
        MISSING_CALLBACK(true),
        CALLBACK_SIGNATURE_CHANGED(true),
        MISSING_HANDLE(true),
        MISSING_RESOURCE(true),
        MISSING_ANCHOR(true),
        MISSING_BUILDER(true),

        /** The installed app has no such screen, and keeps its native one. */
        SCREEN_NOT_INSTALLED(false),
        ;

        public fun describe(detail: String): String = when (this) {
            RUNTIME_VERSION_MISMATCH -> detail
            MISSING_ADAPTER ->
                "the installed app has no component '$detail'. Adding a component " +
                    "the app has never drawn needs a new build of the app."
            UNSUPPORTED_PROP ->
                "$detail. The installed app passes only the arguments its own " +
                    "source gave that component, so this one needs a new build."
            MISSING_CAPABILITY ->
                "the installed app has no action '$detail'. An action is generated " +
                    "from a handler written in the app's source, so a new one needs " +
                    "a new build."
            CAPABILITY_ARITY_CHANGED -> detail
            MISSING_CALLBACK ->
                "the installed app's screen has no callback parameter '$detail'. " +
                    "A bundle reaches the app through the parameters the screen " +
                    "already declares, so a new one needs a new build."
            CALLBACK_SIGNATURE_CHANGED -> detail
            MISSING_HANDLE ->
                "the installed app has no value named '$detail' on this screen."
            MISSING_BUILDER ->
                "the installed app does not build the list entries '$detail' on " +
                    "this screen. A list's own entries are the app's code, and a " +
                    "bundle can choose where they appear but not what they are."
            MISSING_RESOURCE ->
                "the installed app does not contain the resource '$detail'. A bundle " +
                    "may choose among the resources already shipped, but cannot add one."
            MISSING_ANCHOR ->
                "the installed app does not read '$detail' on this screen, so it has " +
                    "no such value to supply. A bundle may name any value the screen's " +
                    "own source reads, and cannot introduce one."
            SCREEN_NOT_INSTALLED ->
                "the installed app has no screen '$detail', so it keeps its own."
        }
    }
}
