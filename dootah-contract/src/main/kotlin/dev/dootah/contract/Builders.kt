package dev.dootah.contract

/**
 * The vocabulary for a native container whose content is built, not drawn.
 *
 * `LazyColumn` does not take children. It takes `LazyListScope.() -> Unit`, and
 * the body of that lambda is a sequence of *declarations* -- this entry, then
 * these entries, then that one -- which Compose then decides how many of to
 * compose, in what order, and when to throw away. A bundle cannot be handed that
 * scope: it runs in a JavaScript sandbox, and the scope is a Compose object with
 * behaviour rather than a value with contents.
 *
 * So the bundle describes the *entries* and the app performs them. The bundle
 * says "one entry holding this UI, then the app's own region, then another
 * entry"; the installed app runs the real `LazyColumn` with the real scope and
 * calls the real `item` for each. Nothing about measurement, scrolling,
 * recycling or virtualisation is described, decided or reimplemented here --
 * those stay where they already work, which is in Compose.
 *
 * The vocabulary is closed for the same reason the layout tokens are. An entry
 * kind the app cannot perform is not a smaller update; it is a list that draws
 * nothing on a device, found by nobody until then.
 */
public object BuilderEntries {

    /**
     * One entry, holding UI the bundle describes.
     *
     * `item { ... }` -- by a distance the common case: 208 of the 251 builder
     * calls in the corpus, against 35 `items` and 7 `itemsIndexed`. Its content
     * is ordinary bundle UI, so it may hold remote layouts, native components,
     * or a mixture, exactly like the content of any other component.
     */
    public const val ITEM: String = "item"

    /**
     * Entries the app declares for itself, as a region kept exactly as written.
     *
     * The degradation ladder at builder level. `items(post.paragraphs) { ... }`
     * ranges over domain objects the bundle has no business holding, and
     * `postContentItems(post)` is an app's own extension on the scope -- neither
     * is describable, and neither needs to be. The app registers the region and
     * performs it against the real scope; the bundle decides only that it
     * appears, and where among the entries it appears.
     *
     * This is what keeps the scope off the wire. A region is a name the app
     * resolves to its own code, not a call the bundle composes.
     */
    public const val REGION: String = "region"

    public val ALL: Set<String> = setOf(ITEM, REGION)

    public fun isKnown(kind: String): Boolean = kind in ALL
}

/**
 * The receiver types whose builders a bundle may describe.
 *
 * Closed, and currently one. A receiver type is only describable once the
 * installed runtime knows how to perform each [BuilderEntries] kind against it
 * -- `item` on a `LazyListScope` is `LazyListScope.item`, and there is no
 * generic way to guess the equivalent for a scope nobody has taught it. Adding
 * `LazyGridScope` is adding a line here and the matching arm in the renderer,
 * which is the point of naming them rather than pattern-matching a shape.
 *
 * `LazyRow` and `LazyColumn` share `LazyListScope`, so both are covered by this
 * one entry; the container is whatever composable the app called, and the app
 * runs it.
 */
public object BuilderScopes {

    public const val LAZY_LIST: String = "androidx.compose.foundation.lazy.LazyListScope"

    public val ALL: Set<String> = setOf(LAZY_LIST)

    public fun isDescribable(qualifiedName: String): Boolean = qualifiedName in ALL

    /**
     * The calls that declare one describable entry, by the scope they are on.
     *
     * Matched by fully qualified name rather than by simple name, so an app's
     * own `item` on a scope of its own is not mistaken for Compose's.
     */
    public val ITEM_CALLS: Set<String> = setOf(
        "androidx.compose.foundation.lazy.LazyListScope.item",
    )

    public fun isItemCall(qualifiedName: String): Boolean = qualifiedName in ITEM_CALLS
}
