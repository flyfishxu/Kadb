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
        namespace = "com.flyfishxu.kadb"
        compileSdk = 36
        minSdk = 23

        withJava()
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
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.conscrypt.java)
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

    coordinates("com.flyfishxu", "kadb", "2.1.1")

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
