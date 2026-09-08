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
            from(files("Android-Pravah/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "Pravah"

include(":app")
project(":app").projectDir = file("Android-Pravah/app")

include(":pravah-android")

include(":patch-bundle")