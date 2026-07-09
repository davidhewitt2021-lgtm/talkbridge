plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciBuildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.talkbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.talkbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuildNumber
        versionName = "3.$ciBuildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "talkbridge"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // Whisper + Silero VAD on-device via sherpa-onnx
    // (the AAR is downloaded into app/libs/ by the CI workflow)
    implementation(files("libs/sherpa-onnx.aar"))

    // Offline translation (models download once, then work offline forever)
    implementation("com.google.mlkit:translate:17.0.2")
}
