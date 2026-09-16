package com.ucas.qingxin.signin.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.ucas.qingxin.signin.R

/**
 * 跨厂商「固定小部件到桌面」引导。
 *
 * 现状（2026 实测与社区反馈）：
 * - 多数国产 ROM 需要先开启「创建桌面快捷方式 / 桌面快捷方式」权限，
 *   [AppWidgetManager.requestPinAppWidget] 才会弹出确认；
 * - ColorOS 16 / OxygenOS 16 破坏了系统自带的 `ACTION_APPWIDGET_PICK` 选择器
 *   （表现为「长按桌面 → 小部件」无响应或「无法添加小部件」），
 *   因此**应用内主动固定**必须作为主路径，系统选择器只能作为兜底；
 * - 华为 / 荣耀 / vivo 的启动器会「假装接收」固定请求（返回 true）却不真正落地，
 *   需要识别后直接给出图文步骤，而不是让用户白等。
 */
object WidgetPinHelper {

    /** 可固定的小部件规格。 */
    enum class Spec(
        val providerClass: Class<out AppWidgetProvider>,
        val labelRes: Int,
        val sizeLabel: String,
    ) {
        COMPACT(TodayCourseCompactWidgetReceiver::class.java, R.string.widget_description_compact, "2×2"),
        WIDE(TodayCourseWidgetReceiver::class.java, R.string.widget_description, "4×2"),
        TALL(TodayCourseTallWidgetReceiver::class.java, R.string.widget_description_tall, "4×4"),
    }

    /** 已知会「返回成功但不落地」的启动器；对这些桌面直接走手动引导。 */
    private val PIN_BROKEN_LAUNCHERS = setOf(
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.bbk.launcher2",
        "com.vivo.launcher",
    )

    fun launcherPackage(context: Context): String = runCatching {
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            ?.activityInfo?.packageName
            .orEmpty()
    }.getOrDefault("")

    /** 当前 ROM 是否支持应用内直接固定小部件。 */
    fun isPinSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (launcherPackage(context) in PIN_BROKEN_LAUNCHERS) return false
        return runCatching {
            AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true
        }.getOrDefault(false)
    }

    /**
     * 请求把指定规格固定到桌面。
     * 返回 true 表示请求已交给系统（用户还需在弹窗中确认）。
     */
    fun requestPin(activity: Activity, spec: Spec): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(activity, R.string.widget_pin_manual_hint)
            return false
        }
        if (!isPinSupported(activity)) {
            showToast(
                activity,
                if (launcherPackage(activity) in PIN_BROKEN_LAUNCHERS) R.string.widget_pin_launcher_unsupported
                else R.string.widget_pin_manual_hint,
            )
            return false
        }
        val manager = AppWidgetManager.getInstance(activity) ?: run {
            showToast(activity, R.string.widget_pin_manual_hint)
            return false
        }
        return try {
            val ok = manager.requestPinAppWidget(
                ComponentName(activity, spec.providerClass),
                null,
                null,
            )
            showToast(
                activity,
                if (ok) R.string.widget_pin_confirm_hint else R.string.widget_pin_need_shortcut_permission,
            )
            ok
        } catch (_: SecurityException) {
            showToast(activity, R.string.widget_pin_need_shortcut_permission)
            false
        } catch (_: Exception) {
            showToast(activity, R.string.widget_pin_manual_hint)
            false
        }
    }

    /** 打开应用详情页，便于用户开启「创建桌面快捷方式」等权限。 */
    fun openAppDetails(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { showToast(activity, R.string.widget_open_settings_failed) }
    }

    /**
     * 按当前 ROM 生成「手动添加小部件」的分步说明。
     * 这是所有厂商都必然可用的兜底路径，也是 ColorOS 16 官方社区给出的绕行方案。
     */
    fun manualGuide(context: Context): String {
        val launcher = launcherPackage(context).lowercase()
        val steps = when {
            launcher.contains("miui") || launcher.contains("hyper") ->
                context.getString(R.string.widget_guide_xiaomi)
            launcher.contains("oppo") || launcher.contains("oneplus") || launcher.contains("oplus") ->
                context.getString(R.string.widget_guide_coloros)
            launcher.contains("vivo") || launcher.contains("bbk") || launcher.contains("iqoo") ->
                context.getString(R.string.widget_guide_originos)
            launcher.contains("huawei") || launcher.contains("hihonor") ->
                context.getString(R.string.widget_guide_harmony)
            launcher.contains("sec.android") ->
                context.getString(R.string.widget_guide_samsung)
            else ->
                context.getString(R.string.widget_guide_aosp)
        }
        val permission = context.getString(R.string.widget_pin_permission_hint)
        return "$permission\n\n$steps"
    }

    /** 当前桌面名（用于在设置页展示「已识别到 XX 桌面」）。 */
    fun launcherLabel(context: Context): String {
        val pkg = launcherPackage(context)
        if (pkg.isBlank()) return "未知桌面"
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    private fun showToast(activity: Activity, resId: Int) {
        runCatching { Toast.makeText(activity, resId, Toast.LENGTH_LONG).show() }
    }
}
