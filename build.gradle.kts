// Version shared by every publishable Dootah module. Consumed via
// rootProject.extra so the modules do not each hard-code it.
extra["dootahVersion"] = providers.gradleProperty("dootahVersion").getOrElse("0.1.0-alpha.17")

apply(from = "gradle/alpha-publication.gradle")

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
        ":dootah-compiler-core:publishToMavenLocal",
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

    val classes = layout.projectDirectory
        .dir("Android-Dootah/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    val screen = "com/dootah/demo/ToolboxRegressionScreenKt.class"
    val source = layout.projectDirectory
        .file("Android-Dootah/app/src/main/java/com/dootah/demo/ToolboxRegressionScreen.kt")
    val report = layout.projectDirectory
        .file("Android-Dootah/app/build/dootah/reports/dootah-ordering.txt")

    doLast {
        // The screen is found because of what it is, not because anyone marked
        // it. An annotation creeping back in here would make every assertion
        // below pass for the wrong reason.
        require(!source.asFile.readText().contains("dev.dootah.") &&
            !source.asFile.readText().contains("@Dootah")) {
            "The regression screen must prove automatic discovery, so it carries " +
                "no Dootah annotation. Remove it, or this check no longer tests anything."
        }

        val discovered = report.asFile
            .takeIf { it.exists() }
            ?.readLines()
            .orEmpty()
            .filter { it.startsWith("intercepted=") }

        require("intercepted=com.dootah.demo.ToolboxRegressionScreen" in discovered) {
            "Dootah did not discover the regression screen on its own. Intercepted:\n" +
                discovered.joinToString("\n")
        }
        require("intercepted=com.dootah.demo.ControlStripRegressionScreen" in discovered) {
            "The delegated-state regression screen must remain intercepted."
        }

        val compiled = classes.file(screen).asFile

        require(compiled.exists()) { "The regression screen was not compiled: $compiled" }

        // The constant pool, as text. What the screen registers is a list of
        // names in it, and reading them back is the only way to tell a screen
        // that compiled from one that compiled and registered nothing.
        val text = String(compiled.readBytes(), Charsets.ISO_8859_1)

        // The picker's handlers write `expanded`, a delegated state declared at
        // the top of the screen. That declaration is part of the native
        // prologue -- it runs before the call that registers any of this -- so
        // the accessors the handlers call are in scope by the time the app
        // builds them, and the app can offer the component rather than leave a
        // hole where it was. The guarantee this replaces is the one that
        // mattered: the task fails if `:app:compileDebugKotlin` fails, and the
        // shape that used to be refused here is precisely the one the JVM
        // backend rejected with `Non-mapped local declaration`.
        //
        // Whether a *bundle* may place it is a separate question with a
        // separate answer -- it may not, while the bundle also holds
        // `expanded` -- and `DelegatedLocalTest` is where that is pinned.
        require(text.contains("com.dootah.demo.RegressionRangePicker(onConfirm|onDismissRequest)")) {
            "The picker adapter should be registered now that the state it reads is " +
                "declared in front of the interception point. Losing it means the " +
                "native prologue stopped covering a delegated `var` at the top of a body."
        }

        // A region kept exactly as written, registered on a screen whose body
        // declares state. This is the whole point of the prologue: before it,
        // the first declaration in a screen refused every region below it, and
        // a screen with no region left to keep stayed native in one piece.
        require(text.contains("!com.dootah.demo.")) {
            "No native region was registered. A screen that declares state must " +
                "still be able to keep its own components exactly as written."
        }
        require(text.contains("java/lang/invoke/LambdaMetafactory")) {
            "The real Compose regression must exercise JVM invokedynamic lambda generation."
        }

        // A resource is read as a Java static field, which is a shape the
        // plugin's own fixtures cannot produce -- their `R` is Kotlin. Nothing
        // registered a resource for as long as that went unchecked, so every
        // bundle naming one was refused and its screen stayed native.
        listOf(
            "drawable:regression_brush",
            "string:regression_brush",
            "androidx.compose.material3.IconButton",
            "androidx.compose.material3.Icon(",
            "dootahAdapter",
            "dootahCapability",
        ).forEach { expected ->
            require(text.contains(expected)) {
                "The regression screen compiled but did not register `$expected`. " +
                    "A bundle naming it would be refused and the screen would stay native."
            }
        }
    }
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

/** Every declared compiler ABI runs the same regression suite. */
tasks.register("dootahCompilerMatrixCheck") {
    group = "verification"
    dependsOn(subprojects.filter { it.name.startsWith("dootah-compiler-plugin") }.map { "${it.path}:test" })
    dependsOn(":dootah-compiler-core:test", ":dootah-gradle-plugin:test", "dootahRealComposeCheck")
}
tasks.named("publishDootahToMavenLocal") {
    dependsOn(subprojects.filter { it.name.startsWith("dootah-compiler-plugin-kotlin-") }.map { "${it.path}:publishToMavenLocal" })
}
