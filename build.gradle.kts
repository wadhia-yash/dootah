// Version shared by the publishable Dootah modules (annotations, compiler
// plugin, Gradle plugin). Consumed via rootProject.extra so the modules do not
// each hard-code it.
extra["dootahVersion"] = "0.1.0-SNAPSHOT"

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
