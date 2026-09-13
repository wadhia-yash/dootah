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

allprojects {
    // Applied to every project; the plugin stands down in modules with no Kotlin
    // compilation, which is most of them in a large build.
    apply<dev.dootah.gradle.coverage.DootahCoveragePlugin>()
}
