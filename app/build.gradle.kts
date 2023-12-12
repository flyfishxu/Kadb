plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flyfishxu.kadb"
    compileSdk = 34

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

    api("com.squareup.okio:okio:3.6.0")

    // Comment out the following line to disable Custom Conscrypt Libs for below Android 9
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}