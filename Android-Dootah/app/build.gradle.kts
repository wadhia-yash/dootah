plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dootah"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dootah"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(project(":dootah-android"))
    implementation(project(":dootah-annotations"))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

}

/**
 * Attaches the compiler plugin the way a host app's build attaches it, but
 * resolved from this build instead of from Maven Local.
 *
 * This module is the only place Dootah's output meets the *real* Compose
 * compiler and the real Material3 declarations. The compiler plugin's own tests
 * compile against hand-written Compose stubs, so a failure in the Compose
 * backend cannot appear there -- it only appears in an app build, which is why
 * one lives here.
 *
 * Dootah is put at the *front* of the plugin classpath because plugin order is
 * classpath order, and Dootah's rewrite has to happen before Compose lowers the
 * result. That is the same ordering `DootahProjectPlugin` enforces for a real
 * app by refusing to be applied after the Compose plugin.
 */
afterEvaluate {
    configurations
        .matching { it.name.startsWith("kotlinCompilerPluginClasspath") }
        .configureEach {
            val alreadyThere = dependencies.toList()
            dependencies.clear()
            dependencies.add(project.dependencies.create(project(":dootah-compiler-plugin")))
            dependencies.addAll(alreadyThere)
        }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    val reports = layout.buildDirectory.dir("dootah/reports").get().asFile.absolutePath
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:dev.dootah:mode=intercept",
        "-P", "plugin:dev.dootah:reportDir=$reports",
    )

}
