plugins {
    kotlin("multiplatform") version "2.4.10"
}

kotlin {
    js {
        browser()

        binaries.executable()

        // No outputModuleName here on purpose. It was previously set to
        // "pravahPatch" and had no effect: the webpack bundle name and the UMD
        // global it exports both derive from this Gradle project's name, so the
        // bundle is patch-bundle.js exporting globalThis["patch-bundle"].
        // The Android runtime looks the patch up under that exact global, so
        // renaming this Gradle project is a breaking change to the patch
        // protocol and requires updating PATCH_MODULE_NAME on the Android side.
    }
}