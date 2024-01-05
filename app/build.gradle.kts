plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = 34

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }

    defaultConfig {
        minSdk = 23
        version = 1.0
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")

    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("com.github.MuntashirAkon.spake2-java:android:2.0.0")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    api("com.squareup.okio:okio:3.7.0")

    // Comment out the following line to disable Custom Conscrypt Libs for below Android 9
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.flyfishxu"
            artifactId = "kadb"
            version = "1.0.0"

            // 上传 AAR 包
            afterEvaluate { artifact(tasks.getByName("bundleReleaseAar")) }
            // 向 Maven 仓库中上传源码
            artifact(sourceJar)

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
        mavenLocal()
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications.getByName<MavenPublication>("mavenJava"))
}