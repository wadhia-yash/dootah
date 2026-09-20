import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    // The Kotlin Gradle plugin API supplies KotlinCompilerPluginSupportPlugin,
    // which is how a compiler plugin attaches to a host project's compilation.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.0.20")
    // KotlinCompile exposes the Java source provider separately from JVM sources.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.20")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.0.20")

    // The include/exclude rules are parsed by the compiler plugin from a string
    // this plugin encodes. Both ends use the same class so they cannot disagree
    // about what a pattern means.
    implementation(project(":dootah-contract"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    testImplementation(localGroovy())

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

// TestKit uses withPluginClasspath(); no implicit Maven Local publication is needed.

val extractionTestCompiler by configurations.creating
val extractionTestKotlinPlugin by configurations.creating
dependencies {
    extractionTestCompiler(project(":dootah-compiler-plugin"))
    extractionTestKotlinPlugin("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}
tasks.test {
    inputs.files(extractionTestCompiler, extractionTestKotlinPlugin)
    doFirst {
        systemProperty("dootah.extraction.compiler", extractionTestCompiler.asPath)
        systemProperty("dootah.extraction.kotlinPlugin", extractionTestKotlinPlugin.asPath)
    }
}

tasks.processResources {
    from(rootProject.file("gradle/compiler-backends.properties")) { into("dev/dootah") }
}

val releaseMetadata = tasks.register("generateReleaseMetadata") {
    val destination = layout.buildDirectory.file("generated/release-resources/dev/dootah/release-version.txt")
    inputs.property("version", project.version.toString())
    outputs.file(destination)
    val release = project.version.toString()
    doLast { destination.get().asFile.apply { parentFile.mkdirs(); writeText(release) } }
}
tasks.processResources {
    dependsOn(releaseMetadata)
    from(layout.buildDirectory.dir("generated/release-resources"))
}

// TestKit consumers load the requested KGP separately, exactly as an app does.
val backendMatrixForTests = Properties().apply {
    rootProject.file("gradle/compiler-backends.properties").inputStream().use { load(it) }
}
backendMatrixForTests.stringPropertyNames().sorted().forEach { family ->
    val compilerVersion = backendMatrixForTests.getProperty(family).split(",").first()
    val compiler = configurations.create("backendTestCompiler$family")
    val kgp = configurations.create("backendTestKgp$family")
    dependencies.add(compiler.name, project(if (family == "2.3") ":dootah-compiler-plugin" else ":dootah-compiler-plugin-kotlin-$family"))
    dependencies.add(kgp.name, "org.jetbrains.kotlin:kotlin-gradle-plugin:$compilerVersion")
    tasks.test {
        inputs.files(compiler, kgp)
        doFirst {
            systemProperty("dootah.test.compiler.$compilerVersion", compiler.asPath)
            systemProperty("dootah.test.kgp.$compilerVersion", kgp.asPath)
        }
    }
}

// A real Kotlin release deliberately outside the matrix, so the version gate's
// refusal is proven against a real Kotlin Gradle plugin rather than a string.
val unsupportedKotlinForTests = "2.2.20"
val unsupportedKgp = configurations.create("backendTestKgpUnsupported")
dependencies.add(unsupportedKgp.name, "org.jetbrains.kotlin:kotlin-gradle-plugin:$unsupportedKotlinForTests")
tasks.test {
    inputs.files(unsupportedKgp)
    doFirst {
        systemProperty("dootah.test.unsupportedKotlin", unsupportedKotlinForTests)
        systemProperty("dootah.test.kgp.$unsupportedKotlinForTests", unsupportedKgp.asPath)
    }
}
