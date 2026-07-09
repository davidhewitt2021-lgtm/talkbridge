plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI builds get an auto-incrementing version from the GitHub Actions run number
val ciBuildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.talkbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.talkbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuildNumber
        versionName = "2.$ciBuildNumber"
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Offline translation (models download once, then work offline forever)
    implementation("com.google.mlkit:translate:17.0.2")
}
