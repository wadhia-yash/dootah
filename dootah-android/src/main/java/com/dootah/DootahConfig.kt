package com.dootah

/** Shared log tag, so every Dootah line can be filtered with one adb command. */
internal const val DOOTAH_LOG_TAG = "Dootah"

/**
 * How Dootah should behave in a host app.
 *
 * [manifestUrl] selects the update endpoint; [trustedPublicKey] authorizes its
 * publisher. Without a key the app can use its APK content but cannot accept OTA.
 */
data class DootahConfig(

    /** HTTPS URL of the bundle manifest. */
    val manifestUrl: String,

    /** Asset in the host APK used when no remote bundle is available. */
    val assetBundleName: String = "bundle.js",

    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,

    /** Wall-clock budget for a single bundle evaluation. */
    val executionTimeoutMillis: Long = 5_000,

    val maxManifestSizeBytes: Int = 64 * 1024,
    val maxBundleSizeBytes: Int = 8 * 1024 * 1024,

    /** Base64 of the trusted 32-byte Ed25519 public key. Missing key rejects OTA. */
    val trustedPublicKey: String? = null,

    /** Defaults to the installed application package name. */
    val appId: String? = null,

    /** Null keeps the static-manifest flow. Otherwise manifestUrl is /updates/check.
     * Explicit native choice: development, staging or production. Fixed for this process.
     */
    val channel: String? = null,

    /**
     * How long the first frame may be held while an already-active bundle loads.
     *
     * Only ever applies when a valid bundle is already on disk, and only to the
     * first activity of the process. Within it the app shows what it shows
     * before its own first frame -- its launch theme, or the system splash --
     * and then draws once, from the active bundle. Without it the app draws its
     * installed implementation first and replaces it a moment later, which is
     * the old version of the screen appearing and then visibly changing.
     *
     * A budget, not a wait: the frame is released the moment the bundle's
     * screens are ready, and released regardless once this expires. Set it to
     * zero to draw the installed implementation immediately and let remote
     * content replace it when it arrives.
     *
     * The default is large because reaching it is the failure case, not the
     * normal one. What the hold waits for is not Dootah's own load, which
     * finishes in well under a second, but the host app's first composition --
     * and a big app on a slow device takes seconds to get there. A budget
     * shorter than that releases the frame before the app has drawn anything,
     * which puts the stale screen back. Every ordinary launch ends the hold on
     * readiness long before this.
     */
    val firstFrameHoldMillis: Long = 5_000,

    /**
     * The quiet period between update checks.
     *
     * Every intercepted screen asks for a check when it appears, because any of
     * them may be the one a kill switch has to reach. An app drawing forty
     * screens does not need forty requests, and a list whose rows enter
     * composition as they scroll does not need one per row: checks within this
     * window of the last completed one are answered from it, and checks that
     * overlap one in flight join it.
     */
    val updateCheckIntervalMillis: Long = 30_000,
)
