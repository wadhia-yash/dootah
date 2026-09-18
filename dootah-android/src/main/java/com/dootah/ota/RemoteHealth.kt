package com.dootah.ota

/** One loaded isolate's startup proof. Only native code can complete a render receipt. */
internal class RemoteHealth(private val promote: () -> Unit) {
    private var initialized = false
    private var failed = false
    private var confirmed = false

    @Synchronized fun initialized() { initialized = true }
    @Synchronized fun failed() { failed = true }

    /** Issued only after remote screen execution and response parsing have succeeded. */
    @Synchronized fun executed(): (() -> Unit)? {
        if (!initialized || failed) return null
        return {
            synchronized(this) {
                if (initialized && !failed && !confirmed) {
                    promote()
                    confirmed = true
                }
            }
        }
    }
}
