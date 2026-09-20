plugins {
    alias(libs.plugins.kotlin.jvm)
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
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    implementation(project(":dootah-contract"))
    testImplementation(libs.junit)
}
publishing { publications.create<MavenPublication>("maven") { from(components["java"]) } }
