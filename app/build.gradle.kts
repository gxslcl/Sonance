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
        versionCode = 16
        versionName = "2.5.3"
        resourceConfigurations += listOf("zh", "en")
    }

    // 双发行版：modern 是主力包（Android 7+，全功能）；legacy 是 Android 5/6 老机专版。
    // 两者共用同一份 src/main 代码与同一个 applicationId（目标设备不同，不会共存）。
    // 差异只有三处：minSdk、Shizuku 依赖与源码（src/modern vs src/legacy 的 ShizukuShell）、
    // 以及 BuildConfig.FLAVOR 门控的少量 UI 入口（B站缓存 / 网易云嗅探 / USB 独占）。
    flavorDimensions += "api"
    productFlavors {
        create("modern") {
            dimension = "api"
            isDefault = true
            minSdk = 24
        }
        create("legacy") {
            dimension = "api"
            minSdk = 21
        }
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
        // java.time / java.util.stream 这些 API 26+ 的类在老系统上靠脱糖运行。
        // legacy（API 21）必需；modern 也顺带修掉 Android 7.0/7.1 上的同类隐患。
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

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

    // FLAC 纯 Java 软解（org.jflac）：老车机/精简 ROM 可能没有 FLAC 的 MediaCodec
    // 解码器，硬件解不了时由 JflacExtractor 软解成 PCM 兜底。只被 legacy 路径引用，
    // modern 的 R8 会把整个解码器裁掉。本地 jar 避免构建期再走网络。
    implementation(files("libs/jflac-codec-1.5.2.jar"))

    // Shizuku：以 shell（uid 2000）身份读 Android/data 下的 B 站缓存目录。
    // Android 11+ 起普通应用（含 SAF、所有文件访问权限）都进不去那个目录，
    // shell 是唯一不需要 root 的通道。provider 负责权限请求的回调。
    // 只进 modern：Shizuku 自身要求 Android 6+，legacy 用同包名的空实现顶替。
    "modernImplementation"("dev.rikka.shizuku:api:13.1.5")
    "modernImplementation"("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.documentfile:documentfile:1.0.1")

    // 扫码登录网易云：二维码矩阵由 zxing 纯 Java 生成，自己画到 Compose Canvas 上。
    // 只引 core（不打 zxing-android 那套 Activity/Intent 集成），混淆后约 300 KB。
    implementation("com.google.zxing:core:3.5.3")

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
