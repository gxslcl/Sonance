import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
        versionCode = 11
        versionName = "2.3.1"
        resourceConfigurations += listOf("zh", "en")
    }

    testOptions {
        unitTests {
            // 让 android.util.Log / org.json 这类 framework 桩方法返回默认值而不是抛
            // "not mocked" —— 这样歌词、tag 解析这些纯逻辑能在 JVM 上直接测。
            isReturnDefaultValues = true
        }
    }

    // 签名信息从 local.properties 读（该文件不进版本库，见 .gitignore）。
    // 配了就用自备密钥，没配就回退 debug 签名 —— clone 下来的人不改配置也能构建。
    signingConfigs {
        val props = Properties().apply {
            rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.inputStream()?.use { load(it) }
        }
        fun prop(key: String): String? = props.getProperty(key)?.takeIf { it.isNotBlank() }

        val storePath = prop("sonance.storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = prop("sonance.storePassword")
                keyAlias = prop("sonance.keyAlias")
                keyPassword = prop("sonance.keyPassword")
            }
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
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    // 进程级前台/后台判定：视频「锁屏 / 切后台继续播放」要靠它区分
    // 「Activity 被视频页盖住」和「整个应用退到后台」
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")

    // Shizuku：以 shell（uid 2000）身份读 Android/data 下的 B 站缓存目录。
    // Android 11+ 起普通应用（含 SAF、所有文件访问权限）都进不去那个目录，
    // shell 是唯一不需要 root 的通道。provider 负责权限请求的回调。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.documentfile:documentfile:1.0.1")

    // 把 src/main/baseline-prof.txt 打进 APK，让 ART 在安装时把关键路径
    // 提前 AOT。没有它 Compose 在 release 包第一次冷启动后依然解释执行，
    // 滚动时主线程帧时分布会显著偏长。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 是空壳桩（方法体被抽掉），单测跑不了真实解析。
    // 这里挂一份真实现，让封面搜索的 JSON 解析能在 JVM 上测。
    testImplementation("org.json:json:20240303")
}
