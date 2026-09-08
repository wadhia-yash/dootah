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
