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
        versionCode = 2
        versionName = "0.5.0"
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

    // Kotlin 原生目录资源放 assets；历史 rootfs 不再由 Android 主路径解包。
    sourceSets["main"].assets.srcDirs("src/main/assets")
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // JVM 单测用真实 org.json（Android 端用平台版）
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
