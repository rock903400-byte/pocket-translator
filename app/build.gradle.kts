plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.translator.pocket"
    compileSdk = 35

    signingConfigs {
        create("releaseSigning") {
            storeFile = file("${rootDir}/keystore/pocket_translator.jks")
            storePassword = "pocket_translator_key_2026"
            keyAlias = "pocket_key"
            keyPassword = "pocket_translator_key_2026"
        }
    }

    defaultConfig {
        applicationId = "com.translator.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseSigning")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("releaseSigning")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle & Service
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Networking (for Groq / Gemini API calls)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Google ML Kit Translate (Offline / Built-in engine support)
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
