plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun generateApkFileName(buildType: String): String {
    val now = Date()
    val day = SimpleDateFormat("dd", Locale("id")).format(now)
    val month = SimpleDateFormat("MMMM", Locale("id")).format(now) // Juli
    val year = SimpleDateFormat("yyyy", Locale("id")).format(now)
    val time = SimpleDateFormat("HHmm", Locale("id")).format(now)
    return "IPCam_${day}${month}${year}_${time}.apk"
}

android {
    namespace = "com.example.ipcam"
    compileSdk = 36  // Diupdate dari 34 ke 36

    defaultConfig {
        applicationId = "com.example.ipcam"
        minSdk = 24
        targetSdk = 34  // Bisa tetap 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    applicationVariants.configureEach {
        val variant = this
        variant.outputs.configureEach {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = generateApkFileName(variant.buildType.name)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // NanoHTTPD web server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-webserver:2.3.1")
    implementation(libs.androidx.graphics.core)

    // ML Kit — Face Detection + Selfie Segmentation
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta5")

    // CameraX extensions (for ML Kit interop)
    implementation("androidx.camera:camera-extensions:1.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}