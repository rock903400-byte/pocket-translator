import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.translator.pocket"
    compileSdk = 35

    // 簽章密碼只從 local.properties 或環境變數讀取，絕不寫死在 git。
    // 本地：local.properties（已備好）；CI：POCKET_* Secrets。
    // 無密碼時 debug 改用預設 debug 簽章照樣可編，release 則保持未簽章並告警。
    val localProps = Properties().apply {
        val f = file("${rootDir}/local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val pocketStorePassword: String? =
        localProps.getProperty("pocket.storePassword") ?: System.getenv("POCKET_STORE_PASSWORD")
    val pocketKeyAlias: String? =
        localProps.getProperty("pocket.keyAlias") ?: System.getenv("POCKET_KEY_ALIAS")
    val pocketKeyPassword: String? =
        localProps.getProperty("pocket.keyPassword") ?: System.getenv("POCKET_KEY_PASSWORD")
    val hasReleaseSigning = !pocketStorePassword.isNullOrBlank() &&
        !pocketKeyAlias.isNullOrBlank() && !pocketKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseSigning") {
                storeFile = file("${rootDir}/keystore/pocket_translator.jks")
                storePassword = pocketStorePassword
                keyAlias = pocketKeyAlias
                keyPassword = pocketKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.translator.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.5.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 有專屬簽章才用（覆蓋升級不斷線）；否則用預設 debug 簽章，CI 無密碼也能編
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseSigning")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseSigning")
            } else {
                logger.warn("未找到簽章密碼（local.properties 或 POCKET_* 環境變數），release 將為未簽章包")
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

    // EncryptedSharedPreferences（API Key 加密存，不再明文）
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
