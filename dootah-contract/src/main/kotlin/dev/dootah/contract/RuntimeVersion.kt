package dev.dootah.contract

/**
 * The version of the agreement between a bundle and the app that renders it.
 *
 * Bumped when the two have to change together -- a new node type, a new kind of
 * value, a different shape on the wire. The gate is equality in both directions:
 * an app refuses a bundle built for another version rather than trying to make
 * sense of it, because a bundle that half-renders is worse than one that does
 * not render at all.
 *
 * Kept here because four things have to agree on it -- the compiler that stamps
 * it into a bundle, the Gradle plugin that writes it into a manifest, the app
 * that checks it, and the contract a bundle is validated against -- and every
 * time it lived in more than one of them they drifted.
 *
 * "6" lets a native container's content be a list of entries rather than
 * children. A "5" renderer knows no `builders` field and, because unknown keys
 * are ignored rather than refused, would draw the container with nothing in it
 * -- an empty list where an article was, on a screen whose whole content is
 * that list.
 *
 * "5" lets a length be a name instead of a number, so a bundle can use the app's
 * own spacing. A "4" renderer reads a length as a number and would find an
 * object where it expected one -- and the failure is not the shape but what
 * follows it: every anchored length would fall to zero, and the layout would
 * collapse rather than refuse.
 *
 * "4" lets a layout carry an alignment and an arrangement. A "3" renderer knows
 * no such fields and, because unknown keys are ignored rather than refused,
 * would draw the layout without them -- a screen that is subtly not the one the
 * bundle described, which is exactly the outcome this gate exists to prevent.
 */
public object RuntimeVersion {
    public const val CURRENT: String = "6"
}
