# iOS / iPadOS 社区预览发行版

[下载 iOS v0.1.0](https://github.com/Vel044/UCAS-Sign-in/releases/tag/ios-v0.1.0)

这是基于 UCAS-Sign-in 的社区 iOS 移植，不代表学校或原作者的官方发行。
版本号使用独立的 `ios-v` 前缀，与 Android 版本分别管理。源码及许可证仍遵循 AGPL-3.0。

## 安装包

`Qingxin-iOS-0.1.0-unsigned.ipa` 是适用于 iPhone / iPad 的未签名主应用，要求 iOS / iPadOS 17+。
它不含开发者证书、设备描述文件、登录信息或学校端点配置；桌面小组件不包含在这个安装包里。
完整的 App + WidgetKit 工程保留在源码中。

1. 下载 IPA，使用自己的 Apple 账户通过 Sideloadly 等工具签名并安装。
2. 按设备提示启用开发者模式，并在「设置 → 通用 → VPN 与设备管理」中信任签名账户。
3. 打开 App，在「设置 → 接口配置」中填入自己的学校 API 根地址和校验模板，随后登录。
   配置字段见 [`README.md`](./README.md#学校接口配置)。未配置时可以使用离线演示模式。
4. 免费账户的签名通常需要每 7 天续签；IPA 并非 App Store / TestFlight 安装包。

请只使用自己的账户和可信的学校接口。具体签到结果以学校记录为准。
预览版的已知状态显示限制见 [`README.md`](./README.md#预览版已知的签到状态限制)。

## 从对应源码构建相同的公开安装包

要求 macOS、Xcode 15+、Python 3 和 XcodeGen。检出发行版 tag 后，从仓库根目录执行：

```sh
python3 ios-app/scripts/package-unsigned.py
```

脚本先运行 QingxinCore 测试，再生成独立的主应用工程并构建设备版。
这个工程不读取 `Config/Local.xcconfig`，不使用 App Group，不嵌入 Widget 扩展，也不执行开发者签名。
默认产物位于被 Git 忽略的 `dist/ios-public/`，包含 IPA 和 `SHA256SUMS.txt`。
完整 WidgetKit 工程的签名与构建方法见 [`README.md`](./README.md#打开与构建)。

## 验证

- 协议、日期与网络解析测试；模拟器 App 状态和界面测试已覆盖首版主要流程。
- iPhone 和 iPad 的本机构建已完成侧载、开发者信任和启动验证。
- 用户已报告学校环境下可正常使用；不同课程、学校接口及通知 / Widget 行为仍需各自验证。
- 公开 IPA 另外检查空接口配置、无签名资料、无 Widget / App Group，以及安装包校验和。
