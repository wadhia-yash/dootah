pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)

    repositories {
        google()
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from(files("Android-Dootah/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "Dootah"

include(":app")
project(":app").projectDir = file("Android-Dootah/app")

include(":dootah-android")

include(":dootah-bundle")
