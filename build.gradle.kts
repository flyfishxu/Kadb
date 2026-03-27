plugins {
    alias(libs.plugins.androidMultiplatformLibrary).apply(false)
    alias(libs.plugins.kotlinMultiplatform).apply(false)
    alias(libs.plugins.dokka)
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}
