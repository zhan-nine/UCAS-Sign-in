# UCAS-Sign-in

**UCAS | 国科大 | 果壳 | 当天课程签到 app**

面向国科大轻新课堂 / iClass 的 Android 客户端：当天课程查询、一键签到、二维码时间轴同步、HyperOS 桌面/负一屏小部件。

> [!CAUTION]
> **本项目仅供学习交流使用，请勿用于任何商业用途或非法用途。**

## 普通用户安装（无需 Android Studio）

适用于只想直接使用 App 的 Android 用户：

1. 下载 [qingxin-signin-1.1.6-release.apk](https://github.com/zhan-nine/UCAS-Sign-in/raw/refs/heads/main/releases/qingxin-signin-1.1.6-release.apk) 安装包，也可以从项目的 [Releases 发布页](https://github.com/zhan-nine/UCAS-Sign-in/releases) 获取后续版本。
2. 下载完成后，点击 APK 文件开始安装。若系统提示“禁止安装未知来源应用”，请在系统设置中允许当前使用的浏览器或文件管理器“安装未知应用”，然后返回继续安装。
3. 如果 Android 或 HyperOS 显示“风险应用”“此应用可能有害”等侧载提醒，请先确认安装包来自本项目的可信发布页；确认来源无误后，点击“仍要安装”“继续安装”或类似按钮完成安装。
4. 安装完成后点击“打开”，输入学校账号即可使用。

### 账号与密码

- 用户名和密码会保存在当前手机的本地加密存储中，用于会话失效后的重新登录，不会写入 APK 或公开仓库。
- 登录时，账号和密码仍会通过网络提交给学校登录接口；请勿在他人手机上保存账号密码。
- 卸载 App 或在 App 内清除数据后，本机保存的账号、密码和会话信息会被删除。

## 功能概览

- 学号密码登录，会话加密存储（EncryptedSharedPreferences）
- 今日课程列表、当前/下一节判定
- 一键签到 / 自动签到 / 通知提醒
- 签到二维码与学校时间轴对齐（`get_timestamp` + TTL）
- HyperOS 小部件：2×2 / 4×2 / 4×4（`miuiWidget` 标识与曝光刷新）

## 目录结构

```text
UCAS-Sign-in/
├── android-app/          # Android Studio / Gradle 工程
├── releases/             # Release APK 发布目录
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

## 签名说明

- `keystore/keystore.properties` 与 `*.jks` **禁止提交**
- 请使用 `keystore.properties.example` 作为模板在本地生成配置
- 若缺少签名配置，Release 构建可能为 unsigned，或按你的 `build.gradle.kts` 行为跳过 signingConfig

## 小部件（HyperOS）

侧载安装后：

1. 打开 App 登录并刷新课程  
2. **设置 → 添加桌面/负一屏小部件**，或系统「安卓小部件」入口  
3. 更换版本后建议删除旧小部件再添加  

完整进入小米「小部件中心」需按小米开放平台提交审核。

## 致谢 / 上游项目

本项目参考并使用了 [lccipher/UCAS-Course-Sign-in](https://github.com/lccipher/UCAS-Course-Sign-in) 的课程查询与签到相关实现思路与接口约定。该项目采用 **AGPL-3.0** 协议，作者为 **lccipher**。

本仓库为面向 Android 的衍生/改写版本（客户端 UI、小部件、本地会话与自动签到等）。按 AGPL-3.0 要求：

- 本仓库同样以 **AGPL-3.0** 发布（见 [`LICENSE`](./LICENSE)）
- 上游归属声明见 [`NOTICE`](./NOTICE)
- 分发 APK 等二进制时，须同时提供对应源代码（通常即本公开仓库）

## 隐私与合规

- **Caution：本项目仅供学习交流使用，请勿用于任何商业用途或非法用途。**
- 不使用设备 GPS；签到走学校官方 HTTPS 接口
- 请勿在公开仓库中提交账号、密码、签名密钥或含会话的调试日志

## License

[GNU Affero General Public License v3.0 (AGPL-3.0)](./LICENSE)

Copyright (C) 的上游部分归 [lccipher/UCAS-Course-Sign-in](https://github.com/lccipher/UCAS-Course-Sign-in) 原作者所有；本仓库新增与修改部分的著作权归本仓库贡献者所有，整体以 AGPL-3.0 许可。
