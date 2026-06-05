pluginManagement {
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
        google()
    }
}

rootProject.name = "koin-compiler-plugin"

include("koin-compiler-plugin")
include("koin-compiler-gradle-plugin")
include("koin-compiler-version-adapter")
include("koin-compiler-version-adapter:kotlin-2.3.20")
include("koin-compiler-version-adapter:kotlin-2.4.0")
