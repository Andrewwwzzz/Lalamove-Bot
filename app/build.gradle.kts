plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zx.stacker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zx.stacker"
        minSdk = 26
        targetSdk = 34
        // Each cloud build gets a higher number so installs always count as an update.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // On-device text recognition (bundled model, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Live GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
