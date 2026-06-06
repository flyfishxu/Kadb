plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("com.vanniktech.maven.publish")
    id("signing")
}

kotlin {
    jvm {
        kotlin {
            jvmToolchain(21)
        }
    }

    android {
        namespace = "com.flyfishxu.kadb.mdns"
        compileSdk = 37
        minSdk = 23

        withJava()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmMain.dependencies {
            implementation(libs.jmdns)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

signing {
    useGpgCmd()
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signAllPublications").map { it.toBoolean() }.orElse(true).get()) {
        signAllPublications()
    }

    coordinates("com.flyfishxu", "kadb-mdns", "2.1.2")

    pom {
        name.set("Kadb mDNS")
        description.set("Optional Kotlin Multiplatform mDNS discovery module for ADB services.")
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
