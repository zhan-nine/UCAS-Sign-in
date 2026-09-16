# UCAS-Sign-in

**UCAS | 国科大 | 果壳 | 当天课程签到 app**

面向国科大轻新课堂 / iClass 的 Android 客户端：当天课程查询、一键签到、二维码时间轴同步、跨安卓桌面小部件（含小米负一屏）。

> [!CAUTION]
> **本项目仅供学习交流使用，请勿用于任何商业用途或非法用途。**

## 普通用户安装（无需 Android Studio）

适用于只想直接使用 App 的 Android 用户：

1. 下载最新安装包 [qingxin-signin-1.1.13-release.apk](https://github.com/zhan-nine/UCAS-Sign-in/raw/refs/heads/main/releases/qingxin-signin-1.1.13-release.apk)，也可以从项目的 [Releases 发布页](https://github.com/zhan-nine/UCAS-Sign-in/releases) 或仓库 [`releases/`](./releases/) 目录获取。
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
- 一键签到 / 后台随机自动签到（常驻通知 + 强制结果通知）/ 通知提醒
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
| **ColorOS 16 / OxygenOS 16** | 已知问题较多，请按下列顺序处理：① **优先用 App 内「选择规格添加到桌面」**（系统选择器常坏）；② 开启「创建桌面快捷方式」与「自启动」；③ 若长按桌面「小部件」无响应：`设置 → 应用 → 应用管理 → 显示系统程序 → Shelf → 启用`，然后强制停止一次桌面；④ 本版已移除 `targetCellWidth/Height`（成熟项目 BeeCount 验证 ColorOS 会错误解释该字段导致小部件被压扁），改用 `minWidth=300dp` 让启动器按网格自行换算 |
| **vivo / iQOO（OriginOS）** | 桌面可能「假装接收」固定请求却不落地，App 会检测并直接给出图文步骤 |
| **华为 / 荣耀（HarmonyOS、EMUI）** | 同上，请用「双指捏合桌面 → 窗口小工具」手动添加 |
| **小米 / HyperOS** | 额外支持负一屏：左滑到负一屏 → 点右上角「+ / 编辑」→ 添加小部件；并已声明 `miuiWidget*` 元数据以启用曝光刷新与优先级排序 |

### 实现要点（为什么这样写）

- **基础与 API 31+ 两套 provider 声明**（`res/xml/` 与 `res/xml-v31/`）：**故意不写 `targetCellWidth/Height`**——ColorOS / OxygenOS 会错误解释该字段并把小部件压扁（成熟项目 [BeeCount #307](https://github.com/TNT-Likely/BeeCount/pull/307) 已验证）。改为只声明 `minWidth/minHeight`（4×2 用小米官方 300×110dp），由启动器按本机网格换算；`minResize` 放小，避免被判定为不可用。三规格使用**不同 label**，避免 ColorOS Shelf 合并隐藏。
- **自适应渲染**（改编自 Breezy Weather，该文件按 LGPL-3.0 授权）：Android 12+ 优先按宿主上报的**全部可用尺寸**各渲染一套 `RemoteViews`；不支持该字段时退化为「横屏 / 竖屏」两套布局。并按实际高度决定可见课程行数，避免拉伸后文字被裁切。
- **快照兜底 + 冷启动正确性**：渲染结果会写入明文快照，应用进程被系统回收后仍能立即出图，不再卡在「加载中…」；同时区分「尚未加载数据」与「今天确实没有课」，避免冷启动时误显示「今日暂无课程」。跨天后过期快照会切到「正在同步」而不是继续显示昨天的课表。退出登录会清空快照。
- **及时刷新（四层互补）**：小部件显示内容是时间的函数，单靠某种刷新都会滞后。因此：
  1. **进程内定时器**（60 秒）只做本地重绘，让「当前课 / 下一节 / 可签到」随时间即时切换；应用在前台时常驻，退到后台后保留 3 分钟宽限。
  2. **边界闹钟**在每节课的「开课前 25 分钟（签到窗口开始）/ 上课 / 下课」以及**次日零点**触发，使内容在语义变化的那一刻更新，而不是等固定周期。使用 `AlarmManager.setAndAllowWhileIdle`（**非精确**闹钟），因此小部件链路本身**不需要** `SCHEDULE_EXACT_ALARM`。
  3. **数据兜底闹钟**：近期没有课时边界时最多 10 分钟抓一次数据，保证「临时加课」「他人代签」这类变化能被带进来。**自动签到守护进程存活期间，该间隔自动放宽到 45 分钟**——这一条单独即减少约 100+ 次/天的唤醒。
  4. **WorkManager**（15 分钟）作为进程被杀后的最终兜底；provider 的 `updatePeriodMillis`（30 分钟）则由宿主推送，是唯一不依赖本应用进程存活的标准通道。
- **进程健壮性**：`Application` 初始化、加密存储（Keystore）读取全部做异常降级，任何一环失败都不会让「小部件整块变成无法加载」。

## 后台自动签到

在 **设置 → 自动签到** 中开启后，App 会在后台为今天的每一节课自动完成签到。

### 怎么签

- **时间**：在 **[开课前 15 分钟, 上课时刻]** 这个区间内**随机**取一个时刻签到。
- **随机值不落盘**：随机时刻**只存在于内存**，不写入 SharedPreferences、不写文件。
  设备上不会留下任何「固定签到时刻」的可疑记录。
- **每节课重新随机**：每节课（每次课次）都会重新取一次随机值，不会形成固定模式。
- **失败重试**：若返回「未到签到时间」「二维码过期」等非终态结果，会在窗口内以
  **60–90 秒**的随机间隔重试，直到成功或超过**开课后 20 分钟**上限；
  每节课**只会推送一条**结果通知。
- **手动签到不受影响**：后台任务使用非阻塞的 `tryLock`，抢不到锁时**主动让路**。
  你在签到窗口里手动点「一键签到」时，永远不会看到「签到进行中，请勿重复点击」。

### 会通知什么

| 通知 | 时机 | 是否受「签到通知」开关影响 |
| --- | --- | --- |
| **常驻状态通知**（静默） | 一直显示「下次自动签到：HH:mm · 课程名」，点击可进入 App | 否（由系统通知权限控制） |
| **签到前提醒** | 随机时刻前约 2 分钟 | 否 |
| **结果通知** | 自动签到成功或最终失败 | **否**（强制发出） |
| 手动签到提醒 / 结果 | 你手动点「一键签到」时 | 是 |

> [!IMPORTANT]
> 自动签到的**结果通知是强制的**：它直接关系到考勤，因此刻意不受「签到通知」开关影响。
> 若你不希望看到它，请关闭系统通知权限（那样常驻通知也会一并消失）。

### 保活引导（设置 → 后台运行与省电）

要在后台准时签到，需要三件事，设置页提供了**可点击的入口**与**实时状态**：

1. **关闭省电策略** —— 一键弹出系统「忽略电池优化」授权框（未豁免时，系统会把签到
   推迟十几分钟甚至直接跳过，且 App Standby 分桶会限制闹钟频次）。
2. **允许自启动** —— 跳转厂商自启动管理页（ColorOS / HyperOS / OriginOS / HarmonyOS / One UI 分别适配）。
   App 无法查询该权限，因此用「是否收到过开机广播」代替：**重启一次手机后会自动确认**。
3. **锁定后台** —— 按当前 ROM 给出「最近任务 → 下拉/长按卡片 → 加锁」的分步图文说明。
4. **允许通知** —— 通知权限被拒时，常驻通知与结果通知都不会显示。
5. **允许精确闹钟**（Android 12+）—— 未授权时自动降级为非精确闹钟，不会静默丢失唤醒。

### 耗电设计（为什么它比看起来省电）

以「**每天的唤醒次数**」为一等约束设计，全程事件驱动、**零轮询**：

- **每节课只排 1 个闹钟**。已取得电池豁免且允许精确闹钟时用
  `setExactAndAllowWhileIdle`；否则用 `setAndAllowWhileIdle` **加**一个
  `OneTimeWorkRequest`（交由 JobScheduler 与其他 App 的任务批量合并，官方明确比 wakeup 闹钟省电）。
  两条路径**互斥**，同一次签到不会被唤醒两遍。4 节课的一天约 **5 次**唤醒。
- **不再有 15 分钟的周期任务**：原来的 `auto_sign_work` 与 `widget_refresh_work` 两个
  `PeriodicWorkRequest` 已被移除/改造，避免了 192 次/天量级的周期性网络请求。
- **守护服务不做轮询**：它用**单次 `delay`** 睡到下一个随机时刻。协程 `delay`
  不持有 wakelock、不唤醒 CPU，设备休眠期间真实功耗约为 0；精确唤醒由闹钟负责。
- **签到前提醒不排闹钟**：提醒由守护服务在**进程内**定时发出，额外唤醒成本为零。
- **重试不排闹钟**：只在当次唤醒的电量豁免窗口与守护进程内进行（Doze 下非精确闹钟
  最快也要约 15 分钟才放行一次，用它做秒级重试是错的）。
- **复用缓存**：本地课程数据 10 分钟内新鲜就**不发任何网络请求**；校时优先复用
  30 秒 TTL 的样本。对比旧版「每次唤醒必发 2 个 HTTP」显著下降。
- **通知不做无效刷新**：内容未变化时直接跳过 `notify()`，避免通知栏反复重绘。
- **不申请任何自定义 wakelock**。
- **低耗电模式**（可选开关）：关闭常驻通知与后台服务，只保留每节课一个闹钟。
  更省电，但可能晚几十秒到十几分钟，也没有「下次签到时刻」的常驻提示。

设置页会直接显示「今日预计唤醒：N 次（每节课 1 次 + 跨天 1 次）」。

### 实现说明

- 守护服务使用 `specialUse` 前台服务类型，而**不是** `dataSync`：
  Android 15 起 `dataSync` 有「24 小时共 6 小时」配额（撑不住常驻通知），
  且 **`BOOT_COMPLETED` 禁止启动 `dataSync` 类型前台服务**（`specialUse` 不受此限制）。
- 三个触发来源（精确/非精确闹钟、守护服务进程内定时器、WorkManager 兜底）共用
  **同一个执行引擎**，并由同一把 `Mutex` 串行化，因此多路径叠加既不会重复签到，
  也不会放大请求量。
- 若系统拒绝启动常驻服务（例如被系统在后台拉起时），App 会自动**降级为「仅闹钟」模式**
  并在设置页提示；把 App 切到前台即可自动重试恢复。

### 权限

| 权限 | 用途 |
| --- | --- |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 直接弹出「忽略电池优化」授权框（**仅在你点击对应按钮时才使用**） |
| `SCHEDULE_EXACT_ALARM` | 让随机签到时刻不被 Doze 推迟；被撤销时自动降级 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 承载常驻状态通知与进程内精确计时 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复自动签到 |
| `POST_NOTIFICATIONS` | 常驻状态通知与自动签到结果通知 |

本项目通过 GitHub 侧载分发，不涉及 Google Play 对上述权限的政策审核。

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
