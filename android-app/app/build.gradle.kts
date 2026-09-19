plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties
import java.io.FileInputStream

// 必须显式 import：脚本体里的 `java` 会被 Gradle Kotlin DSL 解析成 JavaPluginExtension，
// 因此 `java.net.URI(...)` 会报 "Unresolved reference: net"。
import java.net.URI

val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

/**
 * 后端端点外置（**隐私约束，勿回退为硬编码**）。
 *
 * 学校 iClass 的主机名与端口不写进仓库源码，改由**本地** `local.properties` 注入
 * `BuildConfig`。这样即使仓库被公开，源码里也不出现任何主机 / 端口 / 后台路径。
 *
 * 代价：克隆者需自行创建 `local.properties`（可参考 `local.properties.example`）。
 */
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun localProp(key: String): String {
    val value = (localProperties.getProperty(key) ?: System.getenv(key))?.trim().orEmpty()
    if (value.isEmpty()) {
        throw GradleException(
            "缺少本地配置 `$key`。请在 ${localPropertiesFile.absolutePath} 中补齐" +
                "（可复制 local.properties.example 作为模板），然后重新构建。",
        )
    }
    return value
}

/** 让任意文本可安全嵌入 Java 字符串字面量（BuildConfig 生成的是 Java 源码）。 */
fun javaStringLiteral(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 读取**可选**的本地配置；缺失时返回空串（与 [localProp] 的「缺失即报错」相反）。
 *
 * 用于「默认不需要、出问题时才填」的键：让它缺失也能构建，
 * 否则每个构建者都被迫去填一个自己看不懂的值。
 */
fun localPropOrEmpty(key: String): String =
    (localProperties.getProperty(key) ?: System.getenv(key))?.trim().orEmpty()

/** 拼接站点根地址与栏目路径，容忍两侧斜杠的任意写法。 */
fun joinUrl(base: String, path: String): String =
    base.trim().trimEnd('/') + "/" + path.trim().trimStart('/')

val apiBaseUrl = localProp("ucas.iclass.apiBaseUrl")
val verifyUrlTemplate = localProp("ucas.iclass.verifyUrlTemplate")

// 人文讲座通知（人文学院网站）——同样是校内端点，因此同样外置。
// 单独暴露 baseUrl 是因为「查看详情」要跳到具体文章页，而文章页地址是从
// 列表页的相对路径拼出来的（源码里只保存相对路径，见 LectureNotice 的说明）。
val renwenBaseUrl = localProp("ucas.renwen.baseUrl")
val renwenMingdeUrl = joinUrl(renwenBaseUrl, localProp("ucas.renwen.mingdePath"))
val renwenArtUrl = joinUrl(renwenBaseUrl, localProp("ucas.renwen.artPath"))

// 课程注册表（iClass 后台的匿名只读目录）——同上，属于校内端点，因此外置。
//
// 只有哨兵的「找下一场讲座」用它，且**只读课程名**。这里把「怎么问」也一起外置：
// 查询参数名与它的取值（一个动作名）能直接标识这台服务器，留在源码里等于把端点的
// 一半写进了公开仓库。
val registryUrl = localProp("ucas.iclass.registryUrl")
val registryMethodParam = localProp("ucas.iclass.registryMethodParam")
val registryDetailMethod = localProp("ucas.iclass.registryDetailMethod")

// 哨兵基点：本学期第一场讲座在注册表里的编号。
//
// 放在本地配置而不是源码里，是因为它**随学期变化**：编号单调递增，新学期要从新的
// 位置起扫，改一行配置比改代码再发版现实得多。留空 = 不启用哨兵（功能静默关闭）。
val sentinelAnchorCid = localProp("ucas.iclass.sentinelAnchorCid").trim()

// 讲座预约系统（xkcts）——校内端点，同样外置。
//
// ## 为什么必须外置，而不是写死在源码里
// 它是本项目唯一**需要登录**的通道，地址里带端口，且它的两个栏目路径（人文 / 科研）
// 本身就是可识别信息 —— 按本项目的隐私红线，这些一律不进公开仓库。
//
// ## 为什么需要这三个值
// 讲座时间表是一个服务端渲染的 HTML 页面，因此地址必须完整（含栏目路径）；
// 单独暴露 baseUrl 是为了让 WebView 的登录跳转与页面判定都有统一的根地址可比对。
// SEP 统一认证登录页：WebView **必须先打开这里**，不能直开 xkcts 栏目页
// （直开会落到 /redirect → /appStore，后者在 API 协商下会吐出 401 JSON）。
val sepLoginUrl = localProp("ucas.sep.loginUrl")
// 新版选课主页：SEP「选课系统」SSO 之后的落点；讲座预告菜单在这里（见 UCAS-Desktop）。
val xkgoMainUrl = joinUrl(localProp("ucas.xkgo.baseUrl"), localProp("ucas.xkgo.mainPath"))
val xkctsBaseUrl = localProp("ucas.xkcts.baseUrl")
val xkctsHumanityUrl = joinUrl(xkctsBaseUrl, localProp("ucas.xkcts.humanityPath"))
val xkctsScienceUrl = joinUrl(xkctsBaseUrl, localProp("ucas.xkcts.sciencePath"))

/**
 * 额外放行明文流量的主机（逗号分隔），**默认空**。
 *
 * ## 为什么需要这个逃生口
 * 明文策略对 **WebView 同样生效**：讲座页的内嵌登录流程若中途经过一个 `http://`
 * 的校内跳转，会被系统直接拦掉，而表现只是「登录页刷不出来」——
 * 与网络不通完全无法区分，排查会非常费劲。
 *
 * 目前实测到的跳转全程都是 https（`/ve/` 与 `/redirect` 都 302 到 `https://sso.…`），
 * 因此**默认不需要填**。留这个键是为了：一旦某个环境确实需要，
 * 改本地配置即可，不必改代码、重新審查、再发版。
 *
 * 只放行这里列出的主机，其余仍是「仅系统 CA + 禁明文」——不放宽全局。
 */
val extraCleartextHosts: List<String> = localPropOrEmpty("ucas.net.extraCleartextHosts")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }


android {
    namespace = "com.ucas.qingxin.signin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ucas.qingxin.signin"
        minSdk = 26
        targetSdk = 35
        // versionCode 必须单调递增：任何低于已装版本的数值都会导致无法覆盖安装。
        versionCode = 41
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 端点来自本地 local.properties，绝不硬编码（见文件顶部说明）。
        buildConfigField("String", "API_BASE_URL", javaStringLiteral(apiBaseUrl))
        buildConfigField("String", "VERIFY_URL_TEMPLATE", javaStringLiteral(verifyUrlTemplate))
        // 人文讲座通知：列表页地址 + 站点根地址（后者用于把相对路径拼成详情页链接）。
        buildConfigField("String", "RENWEN_MINGDE_URL", javaStringLiteral(renwenMingdeUrl))
        buildConfigField("String", "RENWEN_ART_URL", javaStringLiteral(renwenArtUrl))
        buildConfigField("String", "RENWEN_BASE_URL", javaStringLiteral(renwenBaseUrl))
        // 课程注册表：仅哨兵使用，只读课程名（端点与查询方式同样来自本地配置）。
        buildConfigField("String", "REGISTRY_URL", javaStringLiteral(registryUrl))
        buildConfigField("String", "REGISTRY_METHOD_PARAM", javaStringLiteral(registryMethodParam))
        buildConfigField("String", "REGISTRY_DETAIL_METHOD", javaStringLiteral(registryDetailMethod))
        buildConfigField("String", "SENTINEL_ANCHOR_CID", javaStringLiteral(sentinelAnchorCid))
        // 讲座预约系统（xkcts）：仅由「讲座预告」页的内嵌 WebView 使用，
        // 会话由用户自己在 WebView 里登录产生，应用不代持凭据。
        // SEP 登录页必须单独注入：起始页是它，不是栏目页（见 local.properties.example）。
        buildConfigField("String", "SEP_LOGIN_URL", javaStringLiteral(sepLoginUrl))
        buildConfigField("String", "XKGO_MAIN_URL", javaStringLiteral(xkgoMainUrl))
        buildConfigField("String", "XKCTS_BASE_URL", javaStringLiteral(xkctsBaseUrl))
        buildConfigField("String", "XKCTS_HUMANITY_URL", javaStringLiteral(xkctsHumanityUrl))
        buildConfigField("String", "XKCTS_SCIENCE_URL", javaStringLiteral(xkctsScienceUrl))
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * 许可证合规：把仓库根目录的第三方声明与许可证全文，在构建时同步进 APK 的
 * `assets/licenses/`，确保每个分发出去的二进制都**自带**其适用的许可证副本
 * （AGPL-3.0 / LGPL-3.0 的要求）。用构建任务生成而非手工拷贝，避免两处漂移。
 */
val repoRoot: File = rootProject.projectDir.parentFile
val licensesAssetsRoot = layout.buildDirectory.dir("generated/licenses")

val syncLicenses by tasks.registering(Copy::class) {
    from(repoRoot.resolve("NOTICE")) { rename { "NOTICE.txt" } }
    from(repoRoot.resolve("LICENSES/AGPL-3.0.txt"))
    from(repoRoot.resolve("LICENSES/LGPL-3.0.txt"))
    // assets 源目录即 assets/ 根，因此放进子目录以得到 assets/licenses/xxx.txt
    into(licensesAssetsRoot.map { it.dir("licenses") })
}

android.sourceSets.getByName("main").assets.srcDir(licensesAssetsRoot)

/**
 * 网络安全配置：**必须生成**，不能写死在源码里。
 *
 * ## 为什么需要它
 * 课程注册表是**明文 `http`**（实测该端口不支持 https：TLS 握手直接报
 * `WRONG_VERSION_NUMBER`），而 Android 9+ 默认禁止明文流量 —— 域名不在白名单里，
 * 请求根本不会发出去，只会得到
 * `CLEARTEXT communication to <host> not permitted`。
 * 这个异常在界面上表现为「连接失败」，与网络不通**无法区分**，
 * 因此排查时容易一路怀疑网络与超时（这一版就踩过）。
 *
 * ## 为什么不用 `android:usesCleartextTraffic="true"` 图省事
 * 那是对**全部域名**放开明文，包括签到接口所在的主机。为了一个只读的课程目录
 * 而放弃全局的传输安全，代价与收益完全不成比例。这里只放行注册表这一个域名，
 * 其余仍是「仅系统 CA + 禁明文」。
 *
 * ## 为什么域名不出现在源码里
 * 它属于校内端点，按本项目的隐私红线不得进入公开仓库，因此从 `local.properties` 取。
 * 生成物落在 `build/` 下（已被 gitignore），仓库里只留这份说明。
 */
val netsecResRoot = layout.buildDirectory.dir("generated/netsec/res")

/** 只放行「确为 http」的那一个主机；注册表若配成 https，返回空串（无需放行，也不该放行）。 */
val cleartextHosts: List<String> = buildList {
    runCatching {
        val uri = URI(registryUrl.trim())
        if (uri.scheme.equals("http", ignoreCase = true)) add(uri.host.orEmpty())
    }
    // 额外放行的主机（默认空，见 extraCleartextHosts 的说明）。
    addAll(extraCleartextHosts)
}.filter { it.isNotBlank() }.distinct()

val generateNetworkSecurityConfig by tasks.registering {
    val outDir = netsecResRoot
    val hosts = cleartextHosts
    outputs.dir(outDir)
    // 域名变化（换环境 / 改配置 / 补逃生口）必须触发重生成，否则白名单会停在旧值上，
    // 表现为「改了配置却依然连不上」。
    inputs.property("cleartextHosts", hosts.joinToString(","))
    doLast {
        val dir = outDir.get().asFile.resolve("xml")
        dir.mkdirs()
        val allow = hosts.joinToString("\n") { host ->
            """
            |    <!-- 该校内主机按本地配置走明文 http（该端口不支持 https），逐个放行。 -->
            |    <domain-config cleartextTrafficPermitted="true">
            |        <domain includeSubdomains="false">$host</domain>
            |    </domain-config>
            |""".trimMargin()
        }
        dir.resolve("network_security_config.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<network-security-config>
            |    <!-- 默认：仅系统 CA，禁明文。 -->
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors>
            |            <certificates src="system" />
            |        </trust-anchors>
            |    </base-config>
            |$allow
            |    <!-- 仅调试包允许用户 CA，便于本地抓包排查。 -->
            |    <debug-overrides>
            |        <trust-anchors>
            |            <certificates src="system" />
            |            <certificates src="user" />
            |        </trust-anchors>
            |    </debug-overrides>
            |</network-security-config>
            |""".trimMargin(),
        )
    }
}

android.sourceSets.getByName("main").res.srcDir(netsecResRoot)

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(syncLicenses, generateNetworkSecurityConfig)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    /*
     * 单元测试里必须有**真实**的 org.json。
     *
     * 单测跑在 AGP 提供的 mockable `android.jar` 上：它把 `org.json` 的方法一律实现为
     * 「抛异常」（有返回值的方法）或「空实现」（void/构造）。讲座公开数据源的解析完全
     * 依赖 org.json，若不在测试 classpath 里显式放一份实现，解析用例只能被迫跳过，
     * 那等于这些用例白写。
     *
     * 注意：这是 **testImplementation**，只进单测 classpath，**不会**打进 APK——
     * 设备上仍然使用 Android 平台自带的 org.json，因此不增加体积、也不改变运行时行为。
     */
    testImplementation("org.json:json:20240303")
}
