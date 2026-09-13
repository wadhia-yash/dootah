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

// The plugin reads its own version out of this to resolve the compiler plugin,
// the annotation and the bundle runtime it has to stay in lockstep with. Without
// it every lookup fell back to a hardcoded default, which happened to be right
// and would have gone on looking right the first time the version moved.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

dependencies {
    // The Kotlin Gradle plugin API supplies KotlinCompilerPluginSupportPlugin,
    // which is how a compiler plugin attaches to a host project's compilation.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")

    // The include/exclude rules are parsed by the compiler plugin from a string
    // this plugin encodes. Both ends use the same class so they cannot disagree
    // about what a pattern means.
    implementation(project(":dootah-contract"))

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("dootah") {
            id = "dev.dootah"
            implementationClass = "dev.dootah.gradle.DootahProjectPlugin"
            displayName = "Dootah"
            description = "Over-the-air updates for ordinary Compose code"
        }
    }
}

tasks.test {
    // GradleRunner builds need to find the plugin and its siblings.
    dependsOn(
        ":dootah-annotations:publishToMavenLocal",
        ":dootah-contract:publishToMavenLocal",
        ":dootah-compiler-plugin:publishToMavenLocal",
    )
}
