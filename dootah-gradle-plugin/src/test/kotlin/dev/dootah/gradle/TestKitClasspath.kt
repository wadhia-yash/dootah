package dev.dootah.gradle

import java.io.File
import java.util.Properties

/**
 * Dootah's own classes, as a TestKit consumer's build script would see them.
 *
 * `withPluginClasspath` takes a flat list of jars with no conflict resolution,
 * so anything Dootah carries that the host's Kotlin Gradle plugin also provides
 * has to be dropped here. `kotlin-gradle-plugin-api` is exactly that: Dootah
 * builds against the oldest supported line so a real consumer's dependency
 * resolution upgrades it to whatever their KGP needs, and leaving that jar in
 * front of a newer KGP instead pins the older interfaces and the plugin fails
 * to apply with an abstract method it cannot implement.
 *
 * A real build has conflict resolution and does not need this; only the harness
 * does, which is why the filter lives beside the tests rather than in the POM.
 */
internal fun dootahPluginClasspath(): List<File> =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")) {
        "TestKit plugin metadata is missing; the java-gradle-plugin plugin generates it."
    }.use { Properties().apply { load(it) } }
        .getProperty("implementation-classpath")
        .split(File.pathSeparator)
        .map(::File)
        .filterNot { it.name.startsWith("kotlin-gradle-plugin-api-") }

/** The Kotlin Gradle plugin a consumer fixture applies, loaded as an app loads it. */
internal fun kotlinGradlePluginClasspath(property: String): List<File> =
    checkNotNull(System.getProperty(property)) { "Missing system property $property" }
        .split(File.pathSeparator)
        .map(::File)
