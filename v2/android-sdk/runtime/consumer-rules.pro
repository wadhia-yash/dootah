# Expo discovers this generated registry by reflection.
-keep class expo.modules.ExpoModulesPackageList { *; }
-keep class dev.dootah.runtime.DootahApplication { *; }
# Generated in the application by the Dootah Gradle plugin; absent only when the plugin is not applied.
-dontwarn dev.dootah.generated.DootahAdapterIndex
