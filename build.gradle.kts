plugins {
    alias(libs.plugins.androidLibrary).apply(false)
    alias(libs.plugins.kotlinMultiplatform).apply(false)
    alias(libs.plugins.dokka)
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}
