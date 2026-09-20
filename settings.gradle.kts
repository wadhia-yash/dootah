pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)

    repositories {
        google()
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from(files("Android-Dootah/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "Dootah"

include(":app")
project(":app").projectDir = file("Android-Dootah/app")

include(":dootah-android")

include(":dootah-bundle")
include(":dootah-bundle-runtime")

// Compiler Milestone 1: annotation, compiler plugin, Gradle plugin.
include(":dootah-annotations")
include(":dootah-contract")
include(":dootah-compiler-plugin")
include(":dootah-gradle-plugin")

// Phase 6.5 local Java/Spring control plane.
include(":dootah-server")

include(":dootah-compiler-core")

val compilerBackends = java.util.Properties().apply {
    file("gradle/compiler-backends.properties").inputStream().use { load(it) }
}
compilerBackends.stringPropertyNames().filter { it != "2.3" }.sorted().forEach { family ->
    include(":dootah-compiler-plugin-kotlin-$family")
    // These matrix projects share sources/build logic and have no tracked files.
    // Gradle 9 requires their directories to exist even on a fresh checkout.
    project(":dootah-compiler-plugin-kotlin-$family").projectDir.mkdirs()
    project(":dootah-compiler-plugin-kotlin-$family").buildFileName = "../dootah-compiler-plugin/build.gradle.kts"
}
