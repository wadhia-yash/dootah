// Measures an app without editing it.
//
// An init script rather than a build-file change, because the whole question is
// what Dootah makes of an app as it actually is. Anything added to the app's own
// build is a thumb on the scale, and a number produced that way would not answer
// it.
//
//   ./gradlew --init-script <this file> \
//       -Pdootah.gradle.plugin.jar=<dootah-gradle-plugin.jar> \
//       -Pdootah.plugin.jars=<compiler-plugin.jar>:<contract.jar> \
//       dootahCoverage
initscript {
    dependencies {
        classpath(files(providers.gradleProperty("dootah.gradle.plugin.jar").get()))
    }
}

allprojects {
    // Applied to every project; the plugin stands down in modules with no Kotlin
    // compilation, which is most of them in a large build.
    apply<dev.dootah.gradle.coverage.DootahCoveragePlugin>()
}
