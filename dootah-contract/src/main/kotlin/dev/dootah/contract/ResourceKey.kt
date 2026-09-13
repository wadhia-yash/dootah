package dev.dootah.contract

/**
 * Names an Android resource in a way that survives a rebuild.
 *
 * `R.drawable.brush_24px` is an integer the build assigns, and it is not the
 * same integer in the next build. A bundle that carried the number would resolve
 * to whatever resource happened to land on it -- so a bundle carries the name,
 * and the APK ships a table from these keys to its own current integers.
 *
 * A bundle can therefore choose freely among the resources the installed app
 * already contains, and cannot invent one it does not: a key with no entry in
 * the table is a missing requirement, reported when the bundle is published
 * rather than discovered as a blank icon on a device.
 */
public object ResourceKey {

    public fun of(type: String, name: String): String = "$type:$name"

    public fun typeOf(key: String): String = key.substringBefore(':')

    public fun nameOf(key: String): String = key.substringAfter(':')
}
