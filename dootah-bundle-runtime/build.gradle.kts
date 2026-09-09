plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String

kotlin {
    // A library, not an executable: generated bundle sources compile against
    // this, and the executable is produced by the bundle project the Dootah
    // settings plugin creates in the host build.
    js {
        browser()
    }
}
