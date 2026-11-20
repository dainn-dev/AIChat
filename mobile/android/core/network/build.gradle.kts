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

/** Production API for beta and release builds. Override with `-Paichat.apiBaseUrl=https://<host>/` (e.g. staging). */
val releaseApiBaseUrl: String = providers.gradleProperty("aichat.apiBaseUrl")
    .getOrElse("https://chat-api.dainn.online/")

// Beta/release block cleartext entirely (network_security_config), so an http:// override could never work.
val releaseApiBaseUrlError: String? = if (releaseApiBaseUrl.startsWith("https://")) {
    null
} else {
    "aichat.apiBaseUrl must be an https:// URL for beta/release builds, got: $releaseApiBaseUrl"
}

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

// Fail beta/release assembly (not configuration, so debug builds and IDE sync keep working) when the API base URL
// override is not https.
tasks.matching { it.name == "generateReleaseBuildConfig" || it.name == "generateBetaBuildConfig" }.configureEach {
    val error = releaseApiBaseUrlError
    doFirst {
        if (error != null) throw GradleException(error)
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
