package dev.dootah.compiler.lowering

import dev.dootah.compiler.source.SourceNamedFunction

fun lowerScreen(
    function: SourceNamedFunction,
    filePath: String,
    screenId: String,
): LoweringResult {

    var keptNative = emptySet<String>()

    // Why the first run could not describe the screen, carried forward.
    //
    // Once a value is left to the app, everything that reads it is native too,
    // and the reason the *last* run reports is that read rather than the thing
    // that started it. A developer who wrote `checkout()` in a click handler
    // needs to be told about `checkout()`, not about the counter beside it.
    var firstRefusal = emptyList<UnsupportedConstruct>()

    while (true) {

        val lowering = ScreenLowering(function, filePath, keptNative)
        val result = lowering.lower(screenId)

        if (result !is LoweringResult.Rejected) return result.withEarlierRefusal(firstRefusal)

        if (keptNative.isEmpty()) firstRefusal = result.reasons

        val wanted = lowering.demotionCandidates - keptNative
        if (wanted.isEmpty()) return result

        keptNative = keptNative + wanted
    }
}

/** Adds the reasons an earlier run gave to what this one kept native. */
private fun LoweringResult.withEarlierRefusal(
    earlier: List<UnsupportedConstruct>,
): LoweringResult = when {
    earlier.isEmpty() -> this
    this is LoweringResult.Lowered -> copy(degraded = earlier + degraded)
    else -> this
}
