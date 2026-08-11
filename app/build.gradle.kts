plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aynux.afmu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aynux.afmu"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // QR pairing. CameraX drives the viewfinder; zxing-core is a pure-Java decoder we feed
    // the luma plane straight from the analysis stream — no ML Kit, no Play Services.
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("com.google.zxing:core:3.5.3")

    // 纯 JVM 单元测试。只覆盖不依赖 Android 的那部分 —— base32、协议常量、配对表的
    // 编解码，而它们恰好正是「两端必须逐字节一致」或者「错了就等于开着一道门」的东西。
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 是空壳，一调就抛 "not mocked"。把真实现放进测试
    // classpath 才能测配对表的编解码；它和平台上那份是同一套 API，行为一致。
    testImplementation("org.json:json:20250107")

    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
