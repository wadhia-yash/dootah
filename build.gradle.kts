tasks.register<Copy>("buildPatch") {
    dependsOn(":patch-bundle:jsBrowserProductionWebpack")

    from(
        "patch-bundle/build/kotlin-webpack/js/productionExecutable/patch-bundle.js"
    )

    into(
        "Android-Pravah/app/src/main/assets"
    )

    rename {
        "patch.js"
    }
}