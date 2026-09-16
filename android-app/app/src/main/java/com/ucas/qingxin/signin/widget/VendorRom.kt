package com.ucas.qingxin.signin.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 厂商 ROM 识别与「自启动 / 后台运行 / Shelf」等系统页跳转。
 *
 * ColorOS / OxygenOS 16 上小部件「用不了」常见有三类原因（社区与成熟项目
 * BeeCount、OnePage 已确认）：
 * 1. `targetCell*` 尺寸声明被启动器算错 → 已在 provider XML 中移除；
 * 2. 系统 Shelf 被禁用 → 长按桌面「小部件」入口失效；
 * 3. 省电策略杀掉进程 → 小部件空白 / 不刷新。
 *
 * 本类负责 2、3：给出可点击的系统设置跳转，而不是只丢一段文字。
 */
internal object VendorRom {

    enum class Family {
        COLOR_OS,   // OPPO / 一加 / realme（ColorOS / OxygenOS / realmeUI）
        HYPER_OS,   // 小米 / Redmi / POCO
        ORIGIN_OS,  // vivo / iQOO
        HARMONY,    // 华为 / 荣耀
        ONE_UI,     // 三星
        AOSP,       // 原生 / Pixel / 其他
    }

    fun family(context: Context): Family {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val launcher = launcherPackage(context).lowercase()
        return when {
            brand in setOf("oppo", "oneplus", "realme") ||
                manufacturer in setOf("oppo", "oneplus", "realme") ||
                launcher.contains("oppo") || launcher.contains("oneplus") ||
                launcher.contains("oplus") || launcher.contains("realme") -> Family.COLOR_OS
            brand in setOf("xiaomi", "redmi", "poco") ||
                manufacturer.contains("xiaomi") ||
                launcher.contains("miui") || launcher.contains("hyper") -> Family.HYPER_OS
            brand in setOf("vivo", "iqoo") ||
                manufacturer in setOf("vivo", "iqoo") ||
                launcher.contains("vivo") || launcher.contains("bbk") -> Family.ORIGIN_OS
            brand in setOf("huawei", "honor") ||
                manufacturer in setOf("huawei", "honor") ||
                launcher.contains("huawei") || launcher.contains("hihonor") -> Family.HARMONY
            brand == "samsung" || manufacturer == "samsung" ||
                launcher.contains("sec.android") -> Family.ONE_UI
            else -> Family.AOSP
        }
    }

    fun isColorOs(context: Context): Boolean = family(context) == Family.COLOR_OS

    fun launcherPackage(context: Context): String = runCatching {
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            ?.activityInfo?.packageName
            .orEmpty()
    }.getOrDefault("")

    fun launcherLabel(context: Context): String {
        val pkg = launcherPackage(context)
        if (pkg.isBlank()) return "未知桌面"
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    /**
     * ColorOS 16 的 Shelf（负一屏/小部件选择器宿主）常见包名。
     * 停用任一都会让「长按桌面 → 小部件」无响应。
     */
    val colorOsShelfPackages = listOf(
        "com.coloros.assistantscreen",
        "com.oplus.assistantscreen",
        "com.coloros.sceneservice",
        "com.heytap.speechassist",
        "com.oplus.secondaryhome",
    )

    /** 打开本应用详情页（创建快捷方式 / 耗电管理入口）。 */
    fun openAppDetails(context: Context): Boolean =
        startSafely(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
        )

    /**
     * 打开「自启动 / 后台运行」相关页面（厂商私有 Intent，按优先级尝试）。
     * 小部件进程被杀后只显示 initialLayout，看起来就像「坏了」。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = when (family(context)) {
            Family.COLOR_OS -> listOf(
                // ColorOS / OxygenOS 权限管理 - 自启动
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oplus.battery", "com.oplus.battery.ui.BatteryMainActivity"),
                component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                Intent().setAction("com.oplus.permission.safe.ACTION_PERMISSION"),
                Intent().setAction("oppo.intent.action.OPPO_POWER_MANAGER"),
            )
            Family.HYPER_OS -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings",
                    ),
                ),
            )
            Family.ORIGIN_OS -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
            Family.HARMONY -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            Family.ONE_UI -> listOf(
                Intent().setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Family.AOSP -> listOf(
                Intent().setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        for (intent in candidates) {
            if (startSafely(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true
        }
        return openAppDetails(context)
    }

    /**
     * 尝试打开 ColorOS Shelf 应用详情，方便用户重新启用。
     * 返回 true 表示至少打开了某一个相关页面。
     */
    fun openColorOsShelfDetails(context: Context): Boolean {
        for (pkg in colorOsShelfPackages) {
            if (!isPackageInstalled(context, pkg)) continue
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) return true
        }
        // 找不到 Shelf 包时退回应用列表，让用户手动搜索。
        return startSafely(
            context,
            Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun startSafely(context: Context, intent: Intent): Boolean =
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
