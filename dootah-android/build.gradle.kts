plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

android {
    namespace = "com.dootah.android"

    // Matched to the apps that consume this artifact rather than to the newest
    // available, so a published runtime can be used by an app that has not moved
    // its own compileSdk yet.
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // `Log` is a stub in a unit test and throws when called. What it reports --
    // a prop that arrived as the wrong type -- is exactly what these tests are
    // for, so the stub has to answer rather than throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin.compilerOptions {
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
}

dependencies {
    // Public SDK metadata and dependencies must be readable by the oldest supported compiler.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    // `api` for what appears in this module's own public signatures: a screen's
    // Modifier, and the composable lambdas a generated call site passes in. A
    // host app already has both, but a consumer should not have to know that to
    // compile against this.
    // `api`: the contract's value model appears in this module's own node types,
    // and it is the same agreement the compiler that produced the bundle used.
    api(project(":dootah-contract"))

    // The runtime's own composables are marked `@DootahNative`: an app that
    // compiled this module as source rather than resolving it as a library
    // would otherwise have Dootah discover and intercept Dootah.
    implementation(project(":dootah-annotations"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.runtime)

    // `api` because a builder slot's type appears in this module's own
    // signatures: a generated adapter is handed a `LazyListScope.() -> Unit`,
    // and an app compiling against this has to be able to name it.
    api(libs.androidx.compose.foundation)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.guava)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.google.crypto.tink:tink-android:1.23.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// The Android components are only registered once the variants are known, which
// is after evaluation.
afterEvaluate {
    publishing {
        publications.create<MavenPublication>("maven") { from(components["release"]) }
    }
}
