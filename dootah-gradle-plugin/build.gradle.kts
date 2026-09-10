plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The Kotlin Gradle plugin API supplies KotlinCompilerPluginSupportPlugin,
    // which is how a compiler plugin attaches to a host project's compilation.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("dootah") {
            id = "dev.dootah"
            implementationClass = "dev.dootah.gradle.DootahProjectPlugin"
            displayName = "Dootah"
            description = "Over-the-air updates for @Bundlable Compose functions"
        }
    }
}

tasks.test {
    // GradleRunner builds need to find the plugin and its siblings.
    dependsOn(
        ":dootah-annotations:publishToMavenLocal",
        ":dootah-compiler-plugin:publishToMavenLocal",
    )
}
