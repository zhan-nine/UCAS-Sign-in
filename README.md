# UCAS-Sign-in

**UCAS | 国科大 | 果壳 | 当天课程签到 app**

面向国科大轻新课堂 / iClass 的 Android 客户端：当天课程查询、一键签到、二维码时间轴同步、跨安卓桌面小部件（含小米负一屏）。

> [!CAUTION]
> **本项目仅供学习交流使用，请勿用于任何商业用途或非法用途。**

## 普通用户安装（无需 Android Studio）

适用于只想直接使用 App 的 Android 用户：

1. 下载最新安装包 [qingxin-signin-1.1.10-release.apk](https://github.com/zhan-nine/UCAS-Sign-in/raw/refs/heads/main/releases/qingxin-signin-1.1.10-release.apk)，也可以从项目的 [Releases 发布页](https://github.com/zhan-nine/UCAS-Sign-in/releases) 或仓库 [`releases/`](./releases/) 目录获取。
2. 下载完成后，点击 APK 文件开始安装。若系统提示“禁止安装未知来源应用”，请在系统设置中允许当前使用的浏览器或文件管理器“安装未知应用”，然后返回继续安装。
3. 如果 Android 或 HyperOS 显示“风险应用”“此应用可能有害”等侧载提醒，请先确认安装包来自本项目的可信发布页；确认来源无误后，点击“仍要安装”“继续安装”或类似按钮完成安装。
4. 安装完成后点击“打开”，输入学校账号即可使用。
5. 若要使用桌面小部件，请先在系统应用权限中开启「创建桌面快捷方式」（详见下方「桌面小部件」一节）。

> [!NOTE]
> **本软件不需要定位权限。** 不会申请或使用 GPS / 精确位置 / 粗略位置；签到仅通过学校官方网络接口完成。

### 账号与密码

可以使用两套账号，共用同一组「账号 / 密码」输入框：

1. **SEP 邮箱 + SEP 密码**
2. **轻新课堂学号 + 轻新课堂密码**（默认密码多为 `Ucas@2025`）

- 用户名和密码会保存在当前手机的本地加密存储中，用于会话失效后的重新登录，不会写入 APK 或公开仓库。
- 登录时，账号和密码仍会通过网络提交给学校登录接口；请勿在他人手机上保存账号密码。
- 卸载 App 或在 App 内清除数据后，本机保存的账号、密码和会话信息会被删除。

## 功能概览

- 支持 SEP 邮箱或轻新课堂学号登录（共用同一输入框），会话加密存储（EncryptedSharedPreferences）
- 今日课程列表、当前/下一节判定
- 一键签到 / 自动签到 / 通知提醒
- 签到二维码与学校时间轴对齐（`get_timestamp` + TTL）
- 跨安卓桌面小部件：2×2 / 4×2 / 4×4（兼容原生及主流厂商启动器，含 ColorOS / OriginOS / HarmonyOS / One UI；小米另支持负一屏，内容随课时边界与跨天及时刷新）

## 目录结构

```text
UCAS-Sign-in/
├── android-app/          # Android Studio / Gradle 工程
├── releases/             # Release APK 发布目录
├── LICENSES/             # 第三方许可证全文（AGPL-3.0 / LGPL-3.0）
├── LICENSE               # 本项目主许可证（AGPL-3.0）
├── NOTICE                # 上游与第三方组件归属声明
├── README.md
└── .gitignore
```

## 环境要求

- JDK 17+
- Android SDK（compileSdk / targetSdk 35，minSdk 26）
- 推荐 Android Studio Ladybug 或更新版本

## 快速开始

```bash
cd android-app
# 配置本机 SDK
echo "sdk.dir=YOUR_ANDROID_SDK_PATH" > local.properties

# 可选：Release 签名
cp keystore/keystore.properties.example keystore/keystore.properties
# 编辑 keystore.properties，并放入自己的 .jks

./gradlew :app:assembleDebug
# 或
./gradlew :app:assembleRelease
```

产物路径：

- Debug：`android-app/app/build/outputs/apk/debug/`
- Release：`android-app/app/build/outputs/apk/release/`
- 对外发布：请将 Release APK 复制到仓库根目录 `releases/`（不要放在 `dist/`）

## 签名说明

- `keystore/keystore.properties` 与 `*.jks` **禁止提交**
- 请使用 `keystore.properties.example` 作为模板在本地生成配置
- 若缺少签名配置，Release 构建可能为 unsigned，或按你的 `build.gradle.kts` 行为跳过 signingConfig

## 桌面小部件（跨安卓）

支持原生 Android 及小米 / OPPO / vivo / 华为 / 三星等主流启动器；在 HyperOS 上还可用于负一屏。

> [!IMPORTANT]
> **使用桌面小部件前，请先为本应用开启「创建桌面快捷方式」权限**（部分系统显示为「桌面快捷方式」「创建快捷方式」）。未开启时，应用内「添加小部件」可能无弹窗或添加失败。

### 开启权限（各系统名称可能略有不同）

1. 打开系统 **设置 → 应用 → 轻新签到（或本应用）→ 权限 / 其他权限**
2. 找到并开启 **创建桌面快捷方式** / **桌面快捷方式**
3. 也可在 App 内 **设置 → 打开应用权限设置** 进入应用详情页后再找该开关

### 添加小部件

1. 打开 App 并登录，刷新当天课程  
2. **设置 → 选择规格添加到桌面**（需已开启上述权限），在系统弹窗中确认  
3. 或：长按桌面空白处 → **小部件 / 插件 / 卡片**，搜索「轻新签到」  
4. 更换版本后建议删除旧小部件再添加  

规格：`2×2`（快捷）/ `4×2`（今日课程，推荐）/ `4×4`（完整课表）。

### 各系统适配说明

App 内 **设置 → 查看手动添加步骤** 会按当前桌面自动给出对应路径。已知需要额外处理的情况：

| 系统 / 桌面 | 说明 |
| --- | --- |
| **ColorOS 16 / OxygenOS 16** | 系统自带的「小部件」选择器存在已知缺陷，长按桌面可能无响应或提示「无法添加小部件」。**请优先使用 App 内的「选择规格添加到桌面」按钮**；若必须走系统入口，请到 `设置 → 应用 → 应用管理 → 右上角「显示系统程序」→ 搜索 Shelf → 启用`（系统把 Shelf 停用后小部件入口会失效） |
| **vivo / iQOO（OriginOS）** | 桌面可能「假装接收」固定请求却不落地，App 会检测并直接给出图文步骤 |
| **华为 / 荣耀（HarmonyOS、EMUI）** | 同上，请用「双指捏合桌面 → 窗口小工具」手动添加 |
| **小米 / HyperOS** | 额外支持负一屏：左滑到负一屏 → 点右上角「+ / 编辑」→ 添加小部件；并已声明 `miuiWidget*` 元数据以启用曝光刷新与优先级排序 |

### 实现要点（为什么这样写）

- **基础与 API 31+ 两套 provider 声明**（`res/xml/` 与 `res/xml-v31/`）：低版本只依赖官方的 `minSize ≈ 70×n − 30` 换算，高版本额外用 `targetCellWidth/Height` 锁定格数，避免不同启动器把 `4×2` 算成 5 列。
- **自适应渲染**（改编自 Breezy Weather，该文件按 LGPL-3.0 授权）：Android 12+ 优先按宿主上报的**全部可用尺寸**各渲染一套 `RemoteViews`；不支持该字段时退化为「横屏 / 竖屏」两套布局。并按实际高度决定可见课程行数，避免拉伸后文字被裁切。
- **快照兜底 + 冷启动正确性**：渲染结果会写入明文快照，应用进程被系统回收后仍能立即出图，不再卡在「加载中…」；同时区分「尚未加载数据」与「今天确实没有课」，避免冷启动时误显示「今日暂无课程」。跨天后过期快照会切到「正在同步」而不是继续显示昨天的课表。退出登录会清空快照。
- **及时刷新（四层互补）**：小部件显示内容是时间的函数，单靠某种刷新都会滞后。因此：
  1. **进程内定时器**（60 秒）只做本地重绘，让「当前课 / 下一节 / 可签到」随时间即时切换；应用在前台时常驻，退到后台后保留 3 分钟宽限。
  2. **边界闹钟**在每节课的「开课前 25 分钟（签到窗口开始）/ 上课 / 下课」以及**次日零点**触发，使内容在语义变化的那一刻更新，而不是等固定周期。使用 `AlarmManager.setAndAllowWhileIdle`（**非精确**闹钟），因此**不申请** `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` 等敏感权限。
  3. **数据兜底闹钟**：近期没有课时边界时最多 10 分钟抓一次数据，保证「临时加课」「他人代签」这类变化能被带进来。
  4. **WorkManager**（15 分钟）作为进程被杀后的最终兜底；provider 的 `updatePeriodMillis`（30 分钟）则由宿主推送，是唯一不依赖本应用进程存活的标准通道。
- **进程健壮性**：`Application` 初始化、加密存储（Keystore）读取全部做异常降级，任何一环失败都不会让「小部件整块变成无法加载」。

## 致谢 / 上游项目

本项目参考并使用了以下开源项目，相关声明与许可证文本见 [`NOTICE`](./NOTICE) 与 [`LICENSES/`](./LICENSES)：

1. [lccipher/UCAS-Course-Sign-in](https://github.com/lccipher/UCAS-Course-Sign-in)：课程查询与签到相关实现思路与接口约定，作者 **lccipher**，协议 **AGPL-3.0**
2. [breezy-weather/breezy-weather](https://github.com/breezy-weather/breezy-weather)：桌面小部件**尺寸自适应**逻辑，版权所有者为 Breezy Weather 贡献者，协议 **LGPL-3.0**。仅一个文件受影响：
   `android-app/app/src/main/java/com/ucas/qingxin/signin/widget/WidgetSizeCompat.kt`（该文件按 LGPL-3.0 授权，改动说明见文件头）

本仓库为面向 Android 的衍生/改写版本（客户端 UI、小部件、本地会话与自动签到等）。按 AGPL-3.0 与 LGPL-3.0 要求：

- 本仓库整体以 **AGPL-3.0** 发布（见 [`LICENSE`](./LICENSE)），其中 `WidgetSizeCompat.kt` 以 **LGPL-3.0** 发布（见 [`LICENSES/LGPL-3.0.txt`](./LICENSES/LGPL-3.0.txt)）
- 第三方组件归属、来源文件与改动说明见 [`NOTICE`](./NOTICE)
- 分发 APK 等二进制时，须同时提供对应源代码（通常即本公开仓库）；许可证文本与 `NOTICE` 亦已随安装包打包在 `assets/licenses/` 内，应用内 **设置 → 开源许可与第三方组件** 可查看摘要

## 隐私与合规

- **Caution：本项目仅供学习交流使用，请勿用于任何商业用途或非法用途。**
   147|- **本软件不需要定位权限**，不申请、不使用设备 GPS 或位置信息；签到走学校官方 HTTPS 接口
- 请勿在公开仓库中提交账号、密码、签名密钥或含会话的调试日志

## License

[GNU Affero General Public License v3.0 (AGPL-3.0)](./LICENSE)

例外：`android-app/app/src/main/java/com/ucas/qingxin/signin/widget/WidgetSizeCompat.kt` 以 [GNU Lesser General Public License v3.0 (LGPL-3.0)](./LICENSES/LGPL-3.0.txt) 授权（含改编自 Breezy Weather 的代码）。LGPL-3.0 与 AGPL-3.0 兼容，见 [`NOTICE`](./NOTICE) 说明。

Copyright (C) 的上游部分归各自原作者所有（[lccipher/UCAS-Course-Sign-in](https://github.com/lccipher/UCAS-Course-Sign-in)、[breezy-weather/breezy-weather](https://github.com/breezy-weather/breezy-weather)）；本仓库新增与修改部分的著作权归本仓库贡献者所有，整体以 AGPL-3.0 许可。
