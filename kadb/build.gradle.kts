plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("com.vanniktech.maven.publish")
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
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.bcprov)
            implementation(libs.bcpkix)
            api(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.spake2)
            implementation(libs.documentfile)
            implementation(libs.hiddenapibypass)
        }

        jvmMain.dependencies {
            implementation(libs.spake2)
            implementation(libs.jmdns)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.conscrypt.java)
        }
    }
}

android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.flyfishxu", "kadb", "1.3.0")

    pom {

        name.set("Kadb")
        description.set("A modern and versatile Kotlin Multiplatform ADB client library that simplifies the interaction with Android devices.")
        url.set("https://github.com/flyfishxu/Kadb.git")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("flyfishxu")
                name.set("Flyfish Xu")
                url.set("https://github.com/flyfishxu")
                organization.set("Flyfish Studio")
                email.set("flyfishxu@outlook.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/flyfishxu/Kadb.git")
            developerConnection.set("scm:git:ssh://github.com/flyfishxu/Kadb.git")
            url.set("https://github.com/flyfishxu/Kadb.git")
        }
    }
}