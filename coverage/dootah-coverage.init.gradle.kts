// Measures an app without editing it.
//
// An init script rather than a build-file change, because the whole question is
// what Dootah makes of an app as it actually is. Anything added to the app's own
// build is a thumb on the scale, and a number produced that way would not answer
// it.
//
//   ./gradlew --init-script <this file> \
//       -Ddootah.gradle.plugin.jar=<dootah-gradle-plugin.jar> \
//       -Pdootah.plugin.jars=<compiler-plugin.jar>:<contract.jar> \
//       dootahCoverage
//
// A system property rather than a Gradle property for the jar: the corpus spans
// several Gradle versions and `providers` is not available inside `initscript`
// on the older ones.
initscript {
    dependencies {
        classpath(
            files(
                System.getProperty("dootah.gradle.plugin.jar")
                    ?: error("Set -Ddootah.gradle.plugin.jar=<path to dootah-gradle-plugin.jar>")
            )
        )
    }
}

// `beforeProject` rather than `allprojects`: a large build may have isolated
// projects switched on, and cross-project configuration from an init script is
// exactly what that forbids. Each project applies the plugin to itself instead.
//
// The plugin stands down in modules with no Kotlin compilation, which is most of
// them in a large build.
gradle.beforeProject {
    apply<dev.dootah.gradle.coverage.DootahCoveragePlugin>()
}
