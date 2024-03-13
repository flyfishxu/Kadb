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
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(libs.documentfile)
                implementation(libs.sun.security.android)
                implementation(libs.conscrypt.android)
            }
        }
        val commonMain by getting {
            dependencies {
                implementation(libs.java)
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
        create<MavenPublication>("mavenJava") {
            groupId = "com.flyfishxu"
            artifactId = "kadb"
            version = "1.0.1"

            afterEvaluate { artifact(tasks.getByName("bundleReleaseAar")) }
            artifact(sourceJar)
            artifact(javadocJar)

            pom {
                name.set("Kadb")
                description.set("Kadb is a Kotlin library for Android Debug Bridge.")
                url.set("https://github.com/flyfishxu/Kadb")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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
                    connection.set("scm:git:git://github.com/flyfishxu/Kadb.git")
                    developerConnection.set("scm:git:ssh://github.com/flyfishxu/Kadb.git")
                    url.set("https://github.com/flyfishxu/Kadb.git")
                }
            }
        }
    }

    repositories {
        maven {
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                username = extra["sonatypeUsername"] as String
                password = extra["sonatypePassword"] as String
            }
        }
        mavenLocal()
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications.getByName<MavenPublication>("mavenJava"))
}