plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The Flutter Gradle Plugin must be applied after the Android Gradle plugin.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.gaze"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    aaptOptions {
        noCompress("task", "onnx", "data")
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.gaze"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO: Replace with production keystore before publishing.
            // Use: signingConfig = signingConfigs.create("release") { ... } loaded from keystore.properties
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            // No ProGuard on debug — faster iteration builds
        }
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // Maven artifact coordinate stays com.microsoft.onnxruntime; internal Java package is ai.onnxruntime.* (v1.14+)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    // Required by camera_android_camerax plugin
    implementation("androidx.concurrent:concurrent-futures:1.1.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")
}

flutter {
    source = "../.."
}
