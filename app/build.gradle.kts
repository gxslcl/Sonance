import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sonance.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonance.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    testOptions {
        unitTests {
            // 让 android.util.Log / org.json 这类 framework 桩方法返回默认值而不是抛
            // "not mocked" —— 这样歌词、tag 解析这些纯逻辑能在 JVM 上直接测。
            isReturnDefaultValues = true
        }
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("sonance.keystore")
            storePassword = "[REDACTED]"
            keyAlias = "sonance"
            keyPassword = "[REDACTED]"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Compose 性能的基本盘：开 R8 + 缩资源。minify=false 时 Compose
            // runtime 全部走解释执行，UI 线程在滑动场景会被卡到 30Hz 以下。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("fixed")
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 生成 Compose 稳定性 / 可跳过性报告，便于定位重组与掉帧来源
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    // 把 src/main/baseline-prof.txt 打进 APK，让 ART 在安装时把关键路径
    // 提前 AOT。没有它 Compose 在 release 包第一次冷启动后依然解释执行，
    // 滚动时主线程帧时分布会显著偏长。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
