plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 读取环境变量或 Gradle -P 属性
fun envOrProp(name: String): String? = (project.findProperty(name) as String?) ?: System.getenv(name)

android {
    namespace = "com.zhuqing.ba_recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhuqing.ba_recorder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 如果提供了签名参数，则创建 release 签名配置
    val hasKeystore = listOf(
        envOrProp("ANDROID_KEYSTORE_PATH"),
        envOrProp("ANDROID_KEYSTORE_PASSWORD"),
        envOrProp("ANDROID_KEY_ALIAS"),
        envOrProp("ANDROID_KEY_PASSWORD")
    ).all { it != null && it.isNotBlank() }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                val ksPath = envOrProp("ANDROID_KEYSTORE_PATH")!!
                storeFile = file(ksPath)
                storePassword = envOrProp("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = envOrProp("ANDROID_KEY_ALIAS")
                keyPassword = envOrProp("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 应用 release 签名（如果有提供密钥）
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}