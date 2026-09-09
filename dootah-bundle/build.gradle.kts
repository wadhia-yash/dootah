plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser()

        binaries.executable()

        // No outputModuleName here on purpose. The webpack bundle name and the
        // UMD global it exports both derive from this Gradle project's name, so
        // the bundle is dootah-bundle.js exporting globalThis["dootah-bundle"].
        // The Android runtime looks the bundle up under that exact global, so
        // renaming this Gradle project is a breaking change to the bundle
        // protocol and requires updating BUNDLE_MODULE_NAME on the Android side.
    }

    sourceSets {
        jsMain {
            dependencies {
                // The UI DSL, native bridge and JSON escaping live in the
                // publishable runtime, so this fixture and generated bundles
                // compile against exactly the same code.
                implementation(project(":dootah-bundle-runtime"))
            }
        }
    }
}
