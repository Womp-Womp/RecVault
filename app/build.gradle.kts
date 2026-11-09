import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// keystore/keystore.properties is local-only (gitignored): storePassword, keyPassword
val ks = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.womp.recvault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.womp.recvault"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("vault") {
            storeFile = rootProject.file("keystore/recvault.jks")
            storePassword = ks.getProperty("storePassword")
            keyAlias = "recvault"
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        // One signing key for everything, so the SHA-1 registered with Google never changes.
        getByName("debug") { signingConfig = signingConfigs.getByName("vault") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vault")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
