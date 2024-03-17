plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("module.publication")
}

kotlin {
    jvm()

    androidTarget {
        publishAllLibraryVariants()
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
        kotlin {
            jvmToolchain(17)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.bcprov.jdk15on)
                api(libs.okio)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.spake2.android)
                implementation(libs.documentfile)
                implementation(libs.sun.security.android)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.spake2.java)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}


android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = 34
    defaultConfig {
        minSdk = 23
    }
}