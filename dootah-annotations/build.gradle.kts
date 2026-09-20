plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

kotlin {
    // The annotation ships onto the compile classpath of host Android apps, so it
    // is compiled conservatively rather than against the newest language version.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
    jvmToolchain(17)
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}

dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20") }
