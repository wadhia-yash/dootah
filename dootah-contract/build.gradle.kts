plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

kotlin {
    // This module is read by three parties that cannot share anything else: the
    // compiler plugin (inside kotlin-compiler-embeddable's classloader), the
    // Gradle plugin, and the Android runtime shipped into host apps. It
    // therefore carries no third-party dependency at all -- a serialisation
    // library here would be a classpath risk in the first of those.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    testImplementation(libs.junit)
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
