plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.forge.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.forge.android"
        minSdk = 26          // Accessibility takeScreenshot 需要 30+，26 起步向下兼容（截图走 Shizuku）
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // PRoot rootfs 是 arm64
    }

    buildTypes {
        release {
            isMinifyEnabled = false     // 桥进程反射较多，首版不混淆
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.11" }

    // rootfs tar 放 assets（>100MB 时改 APK 扩展文件或首启下载）
    sourceSets["main"].assets.srcDirs("src/main/assets")
    androidResources { noCompress += "tar" }   // tar 流式解包，不能压
    packaging { jniLibs.useLegacyPackaging = true } // Native executables must be extracted.
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Compose（BOM 统一版本）
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // Shizuku（官方 API；构建时核对最新版）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // WS/HTTP 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
