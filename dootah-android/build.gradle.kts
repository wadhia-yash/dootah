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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // `api` for what appears in this module's own public signatures: a screen's
    // Modifier, and the composable lambdas a generated call site passes in. A
    // host app already has both, but a consumer should not have to know that to
    // compile against this.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.runtime)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

// The Android components are only registered once the variants are known, which
// is after evaluation.
afterEvaluate {
    publishing {
        publications.create<MavenPublication>("maven") { from(components["release"]) }
    }
}
