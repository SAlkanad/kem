plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val cameraxVersion = "1.3.1" // Define camerax_version here using val for Kotlin
val lifecycleVersion = "2.7.0"

android {
    namespace = "com.example.kem"
    compileSdk = flutter.compileSdkVersion // Ensure this is 33 or higher for latest CameraX features
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true 
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "com.example.kem"
        minSdk = 21 // CameraX requires minSdk 21. Ensure flutter.minSdkVersion is also >= 21
        targetSdk = flutter.targetSdkVersion // Ensure this is 33 or higher
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    // CameraX dependencies
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.lifecycle:lifecycle-process:${lifecycleVersion}")    
    // For specific effects like Bokeh, if needed in the future:
    // implementation("androidx.camera:camera-extensions:${cameraxVersion}")
}

