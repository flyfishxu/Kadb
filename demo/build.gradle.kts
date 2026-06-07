plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "com.flyfishxu.kadb.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flyfishxu.kadb.demo"
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "1.0"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kadb"))

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
}
