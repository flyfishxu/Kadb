plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("com.vanniktech.maven.publish")
    id("signing")
}

apply(from = "gradle/ios-native.gradle.kts")

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val nativeOutput = layout.buildDirectory.dir("native/${target.name}")
        val buildNative = tasks.named("buildNative${target.name.replaceFirstChar(Char::uppercaseChar)}")
        val interop = target.compilations.getByName("main").cinterops.create("kadbNative") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/kadb.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
            extraOpts("-libraryPath", nativeOutput.get().asFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName).configure {
            dependsOn(buildNative)
            // Cinterop embeds these archives; changing C must invalidate the produced KLIB.
            inputs.files(nativeOutput.map { it.file("libkadb_native.a") },
                nativeOutput.map { it.file("libssl.a") }, nativeOutput.map { it.file("libcrypto.a") })
        }
        target.binaries.framework {
            baseName = "Kadb"
            isStatic = true
            binaryOption("bundleId", "com.flyfishxu.kadb")
        }
    }

    jvm {
        kotlin {
            jvmToolchain(25)
        }
    }

    android {
        namespace = "com.flyfishxu.kadb"
        compileSdk = 37
        minSdk = 23

        withJava()
        withHostTest {}
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }
        // Android and desktop share JVM crypto/TLS backends, not common APIs.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bcprov)
                implementation(libs.bcpkix)
                implementation(libs.spake2)
            }
        }
        androidMain.get().dependsOn(jvmAndroidMain)
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.dependencies {
            implementation(libs.documentfile)
            implementation(libs.hiddenapibypass)
        }

        jvmMain.dependencies {
            implementation(libs.java.does.usb)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val jvmAndroidTest = create("jvmAndroidTest") {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(jvmAndroidTest)
        getByName("androidHostTest").dependsOn(jvmAndroidTest)

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

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("iosSimulatorDevice").orElse("booted"))
    standalone.set(false)
}

signing {
    useGpgCmd()
}

providers.gradleProperty("workspaceMavenRepository").orNull?.let { repositoryPath ->
    publishing.repositories.maven {
        name = "workspace"
        url = uri(repositoryPath)
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signAllPublications").map { it.toBoolean() }.orElse(true).get()) {
        signAllPublications()
    }

    coordinates("com.flyfishxu", "kadb", "2.1.5")

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

// Explicit hardware task; plain jvmTest never enumerates or claims USB devices.
val desktopUsbTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
tasks.register<JavaExec>("desktopUsbSmoke") {
    group = "verification"
    description = "Enumerate desktop USB devices, or test the device selected by -PusbSerial"
    dependsOn("jvmTestClasses")
    classpath(desktopUsbTestCompilation.output.allOutputs, desktopUsbTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.flyfishxu.kadb.usb.DesktopUsbSmoke")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(providers.gradleProperty("usbSerial").orElse("").get(),
        providers.gradleProperty("usbKey").orElse("").get(),
        providers.gradleProperty("usbApk").orElse("").get())
}

// Independent JVM peer for the optional iOS/JVM pairing interoperability test.
tasks.register<JavaExec>("iosInteropPeer") {
    group = "verification"
    description = "Serve two loopback pairing sessions for the iOS interoperability test"
    dependsOn("jvmTestClasses")
    classpath(desktopUsbTestCompilation.output.allOutputs, desktopUsbTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.flyfishxu.kadb.pair.IosPairingPeer")
    args(providers.gradleProperty("iosInteropReadyFile").orElse(layout.buildDirectory.file("ios-peer-ready").get().asFile.absolutePath).get())
}
