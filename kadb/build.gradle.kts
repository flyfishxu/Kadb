plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("module.publication")
}

kotlin {
    kotlin.applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
        kotlin {
            jvmToolchain(17)
        }
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(libs.documentfile)
                implementation(libs.sun.security.android)
                runtimeOnly(libs.conscrypt.android)
            }
        }
        val commonMain by getting {
            dependencies {
                implementation(libs.spake2.java)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.bcprov.jdk15on)
                api(libs.okio)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

allprojects {
    artifacts
    group = "com.flyfishxu"
    version = "1.1.0"
}

android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

val javadocJar by tasks.registering(Jar::class) {
    from("javadoc")
    archiveClassifier.set("javadoc")
}

// Maven Central Publish Config
apply(from = "../../../publish.gradle.kts")

publishing {
    publications {
        publications.configureEach {
            if (this is MavenPublication) {
                pom {
                    name = "Kadb"
                    description = "A KMP (Kotlin Multiplatform) based CROSS-PLATFORM library for connecting Wlan ADB(Android Debug Bridge) via sockets but without ADB binary file."
                    url = "https://github.com/flyfishxu/Kadb"

                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    developers {
                        developer {
                            id = "flyfishxu"
                            name = "Flyfish Xu"
                            email = "flyfishxu@outlook.com"
                        }
                    }
                    scm {
                        connection = "scm:git:git://github.com/flyfishxu/Kadb.git"
                        developerConnection = "scm:git:ssh://github.com/flyfishxu/Kadb.git"
                        url = "https://github.com/flyfishxu/Kadb.git"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            credentials {
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
                username = extra["sonatypeUsername"] as String
                password = extra["sonatypePassword"] as String
            }
        }
        mavenLocal()
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}