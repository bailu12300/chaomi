import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 从 local.properties 读取采集密钥（不提交到仓库，避免公开仓库泄露）
val crawlerKey: String = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    val p = Properties()
    FileInputStream(f).use { p.load(it) }
    p.getProperty("CRAWLER_KEY") ?: ""
}

android {
    namespace = "com.zaoba.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaoba.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 58
        versionName = "1.0.21"
        buildConfigField("String", "CRAWLER_KEY", "\"$crawlerKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // 网络 + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // WorkManager（后台采集）
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
