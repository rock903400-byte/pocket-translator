import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.translator.pocket"
    compileSdk = 35

    signingConfigs {
        create("releaseSigning") {
            val localProps = Properties().apply {
                val f = file("${rootDir}/local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file("${rootDir}/keystore/pocket_translator.jks")
            storePassword = localProps.getProperty("pocket.storePassword") ?: System.getenv("POCKET_STORE_PASSWORD") ?: "pocket_translator_key_2026"
            keyAlias = localProps.getProperty("pocket.keyAlias") ?: System.getenv("POCKET_KEY_ALIAS") ?: "pocket_key"
            keyPassword = localProps.getProperty("pocket.keyPassword") ?: System.getenv("POCKET_KEY_PASSWORD") ?: (localProps.getProperty("pocket.storePassword") ?: System.getenv("POCKET_STORE_PASSWORD") ?: "pocket_translator_key_2026")
        }
    }

    defaultConfig {
        applicationId = "com.translator.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.4.3"

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
        buildConfig = true // LatencyLog 需要 BuildConfig.DEBUG；AGP 8 起預設不產生
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // Networking (Gemini Live WebSocket)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
