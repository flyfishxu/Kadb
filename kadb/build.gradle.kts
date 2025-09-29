plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("module.publication")
}

kotlin {
    jvm {
        kotlin {
            jvmToolchain(21)
        }
    }

    androidTarget {
        kotlin {
            jvmToolchain(21)
        }
        publishLibraryVariants("release", "debug")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.bcprov)
                implementation(libs.bcpkix)
                api(libs.okio)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.spake2)
                implementation(libs.documentfile)
                implementation(libs.hiddenapibypass)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.spake2)
                implementation(libs.jmdns)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.conscrypt.java)
            }
        }
    }
}

android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
}