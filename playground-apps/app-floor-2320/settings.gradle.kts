// Minimal floor-version guard app — pins the OLDEST supported Kotlin (2.3.20)
// so plugin changes can't silently drop the floor. Override for quick probes:
//   ../../gradlew build -PkotlinVersion=2.4.0
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.3.20"
    plugins {
        kotlin("jvm") version kotlinVersion
    }
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "app-floor-2320"
