import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 版本号机制（与 CI android-ci.yml 配套）----
// 基础版本：发布时只手改这一行。
val appVersionName = "0.1.1"

// CI 构建号：`-Phypercar.buildNumber=<github.run_number>`（CI 每次 push 传入）。
//   versionName = 0.1.1.<N>、versionCode = base*10000 + N —— 每次推送 +1，可覆盖安装。
// 本地车测版本 0.1.1.8 / 50008；CI 仍按 run_number 累计。
val buildNumber: Int? = providers.gradleProperty("hypercar.buildNumber").orNull?.toIntOrNull()
val baseVersionCode = 5
val localBuildNumber = 8

// Local test APKs must not need signing questions or machine-specific setup.
// A configured key always wins. CI/formal builds still require that configuration.
val signingPropertiesFile = rootProject.file("keystore.properties")
val hasConfiguredSigning = signingPropertiesFile.exists()
val ciBuild = providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true)
val releaseRequested = gradle.startParameter.taskNames.any {
    it.endsWith("assembleRelease", ignoreCase = true) || it.endsWith("bundleRelease", ignoreCase = true)
}
val requireConfiguredSigning = providers.gradleProperty("hypercar.requireReleaseSigning")
    .orNull.equals("true", ignoreCase = true) || (ciBuild && (buildNumber != null || releaseRequested))

if (requireConfiguredSigning && !hasConfiguredSigning) {
    throw GradleException("SIGNING: missing_required_config — CI/正式构建需要 keystore.properties；本地车测包可不配置签名。")
}
logger.lifecycle(
    when {
        hasConfiguredSigning -> "SIGNING: mode=configured — 复用本地已有签名配置，不询问密钥。"
        ciBuild -> "SIGNING: mode=ci-test — 未配置正式签名，仅允许测试构建。"
        else -> "SIGNING: mode=local-test — 自动复用 Android 测试签名，缺失时由构建工具生成；无需提供密钥。"
    },
)

android {
    namespace = "io.github.hypercar"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hypercar"
        // minSdk 31：静默连车机热点（cmd wifi connect-network）与 FGS connectedDevice
        // 豁免链路在 Android 12+ 才完整；HyperOS4（Android 17/SDK 37）向下兼容。
        minSdk = 31
        // targetSdk 34：与 cardeck 一致——Android 15+ 对 mediaProjection FGS 的
        // 额外 AppOps 校验、以及悬浮窗可见性补充条款都不触发（HyperOS4 实测宽容）。
        targetSdk = 34
        versionCode = baseVersionCode * 10_000 + (buildNumber ?: localBuildNumber)
        versionName = "$appVersionName.${buildNumber ?: localBuildNumber}"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            // rust-core 通过 cargo-ndk 编译后放入 jniLibs（见 docs/BUILD.md），
            // 这里不挂 cmake/ndkBuild，避免 AGP 再拉一遍 NDK 工具链。
        }
    }

    sourceSets {
        getByName("main") {
            // libhypercar_core.so 由 `cargo ndk -o app/src/main/jniLibs` 产出
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    signingConfigs {
        create("release") {
            if (hasConfiguredSigning) {
                val props = Properties().apply { signingPropertiesFile.inputStream().use { load(it) } }
                val required = listOf("storePassword", "keyAlias")
                if (required.any { props.getProperty(it).isNullOrBlank() }) {
                    throw GradleException("SIGNING: incomplete_config — 本地签名配置不完整，未替换已有签名。")
                }
                // 接到局部 val：storeFile 是可变属性，直接读会触发 smart cast 拒绝
                val sf = rootProject.file(props.getProperty("storeFile") ?: "hypercar.jks")
                storeFile = sf
                // 未配 keystore.properties 里显式声明时按扩展名推断（.p12/.pfx = PKCS12）
                storeType = props.getProperty("storeType")
                    ?: if (sf.name.endsWith(".p12", true) || sf.name.endsWith(".pfx", true)) "PKCS12" else "JKS"
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword") ?: props.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩：不开的话 Compose/图标库/Shizuku 全量进 dex（实测 41.9MB、
            // dex 占 40.3MB），开完通常能砍到一半以下。JNI 回调接口必须配 keep 规则
            // （见 proguard-rules.pro，否则 Rust 侧按名回调会被 R8 改名/裁掉）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                hasConfiguredSigning -> signingConfigs.getByName("release")
                !ciBuild -> signingConfigs.getByName("debug")
                else -> null
            }
        }
        debug {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // 注：未引入 navigation-compose 与 datastore-preferences——
    // UI 用 state-based 切换、设置用 SharedPreferences，两者都是白占 dex。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku：静默连车机热点 / 真实蓝牙 MAC / shell 能力（cardeck 同款依赖）
    // provider 是 ShizukuProvider 的宿主（清单需声明，缺它 binder 收不到）。
    // 注意：只有 api + provider 两个构件；不存在 dev.rikka.shizuku:support。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
