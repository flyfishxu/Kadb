pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Kadb"
include(":kadb")
include(":kadb-mdns")
