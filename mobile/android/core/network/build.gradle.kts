/*
 * AIChat Keyboard: backend client, auth/session handling and the encrypted token store.
 * New module (not part of upstream FlorisBoard). See docs/integration-points.md, section (e).
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val projectMinSdk: String by project
val projectCompileSdk: String by project

/**
 * Debug builds talk to a backend on the developer machine. `10.0.2.2` is the Android emulator's alias for the
 * host loopback interface. Override it for a physical device with `-Paichat.debugApiBaseUrl=http://<host>:3000/`
 * (the host must then also be allowed in `app/src/debug/res/xml/network_security_config.xml`) or use
 * `adb reverse tcp:3000 tcp:3000` together with `http://localhost:3000/`.
 */
val debugApiBaseUrl: String = providers.gradleProperty("aichat.debugApiBaseUrl")
    .getOrElse("http://10.0.2.2:3000/")

// TODO(AIChat): replace with the production API host before the first release build ships.
val releaseApiBaseUrl = "https://api.aichat.example/"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.aichat.keyboard.core.network"
    compileSdk = projectCompileSdk.toInt()

    defaultConfig {
        minSdk = projectMinSdk.toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "AICHAT_API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "AICHAT_API_BASE_URL", "\"$releaseApiBaseUrl\"")
        }
        create("beta") {
            isMinifyEnabled = false
            buildConfigField("String", "AICHAT_API_BASE_URL", "\"$releaseApiBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines)
    api(libs.kotlinx.serialization.json)
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.converter.kotlinx.serialization)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.squareup.okhttp.mockwebserver)
}
