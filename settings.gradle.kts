pluginManagement {
    includeBuild("convention-plugins")
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

plugins {
    id("de.fayard.refreshVersions") version "0.60.5"
}

rootProject.name = "IKadb"
include(":kadb")
