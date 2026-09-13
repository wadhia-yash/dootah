// Version shared by every publishable Dootah module. Consumed via
// rootProject.extra so the modules do not each hard-code it.
extra["dootahVersion"] = "0.1.0-SNAPSHOT"

/**
 * Publishes every part of Dootah a host app resolves, in one go.
 *
 * The Gradle plugin, the compiler plugin, the annotation, the bundle runtime and
 * the Android runtime are one release that has to move together: the plugin
 * resolves the others by its own version, and the compiler and the runtime agree
 * on a wire format. Publishing a subset leaves an app building against a mix of
 * two, which is exactly the failure copying the runtime by hand used to cause --
 * a device test once ran against a stale copy and reported a pass.
 */
tasks.register("publishDootahToMavenLocal") {
    group = "dootah"
    description = "Publishes every Dootah artifact to Maven Local"

    dependsOn(
        ":dootah-annotations:publishToMavenLocal",
        ":dootah-contract:publishToMavenLocal",
        ":dootah-compiler-plugin:publishToMavenLocal",
        ":dootah-gradle-plugin:publishToMavenLocal",
        ":dootah-bundle-runtime:publishToMavenLocal",
        ":dootah-android:publishToMavenLocal",
    )
}

/**
 * Compiles the demo app, which is where Dootah's output meets real Compose.
 *
 * The compiler plugin's own tests attach the plugin to a compilation whose
 * Compose declarations are hand-written stubs and whose Compose compiler is not
 * running at all. They are fast and they check the contract between the two
 * passes, but no failure in the Compose backend can appear in them -- and every
 * one Dootah has had so far appeared only in an app build, as a stack trace
 * inside the Compose compiler with no Dootah frame in it.
 *
 * `ToolboxRegressionScreen` exists to be compiled by this, and this exists so
 * that compiling it is something you can ask for by name.
 */
tasks.register("dootahRealComposeCheck") {
    group = "dootah"
    description = "Compiles the regression screen against the real Compose compiler"

    dependsOn(":app:compileDebugKotlin")
}

tasks.register<Copy>("buildBundle") {
    dependsOn(":dootah-bundle:jsBrowserProductionWebpack")

    from(
        "dootah-bundle/build/kotlin-webpack/js/productionExecutable/dootah-bundle.js"
    )

    into(
        "Android-Dootah/app/src/main/assets"
    )

    rename {
        "bundle.js"
    }
}
