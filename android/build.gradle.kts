plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

val cameraxVersion = "1.3.1"
val lifecycleVersion = "2.7.0"

android {
    namespace = "com.example.kem"
    compileSdk = 34 // Updated to latest
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
        minSdk = 21
        targetSdk = 34 // Updated to latest
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        
        // Add these to prevent background service from being killed
        manifestPlaceholders["excludeFromRecents"] = "false"
        manifestPlaceholders["persistent"] = "true"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // CameraX dependencies
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    
    // Lifecycle dependencies for better background handling
    implementation("androidx.lifecycle:lifecycle-process:${lifecycleVersion}")
    implementation("androidx.lifecycle:lifecycle-service:${lifecycleVersion}")
    implementation("androidx.lifecycle:lifecycle-common-java8:${lifecycleVersion}")
    
    // WorkManager for background task management
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // For better process management
    implementation("androidx.startup:startup-runtime:1.1.1")
}