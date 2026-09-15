package com.ucas.qingxin.signin.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.ucas.qingxin.signin.R

/**
 * 跨厂商「固定小部件到桌面」引导。
 * 多数国产系统需要先开启「创建桌面快捷方式 / 桌面快捷方式」权限，
 * [AppWidgetManager.requestPinAppWidget] 才会弹出确认。
 */
object WidgetPinHelper {

    fun requestPinWide(activity: Activity): Boolean {
        val mgr = AppWidgetManager.getInstance(activity)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(activity, R.string.widget_pin_manual_hint, Toast.LENGTH_LONG).show()
            return false
        }
        if (!mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(activity, R.string.widget_pin_manual_hint, Toast.LENGTH_LONG).show()
            return false
        }
        val provider = ComponentName(activity, TodayCourseWidgetReceiver::class.java)
        return try {
            val ok = mgr.requestPinAppWidget(provider, null, null)
            if (ok) {
                Toast.makeText(activity, R.string.widget_pin_confirm_hint, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, R.string.widget_pin_need_shortcut_permission, Toast.LENGTH_LONG).show()
            }
            ok
        } catch (_: SecurityException) {
            Toast.makeText(activity, R.string.widget_pin_need_shortcut_permission, Toast.LENGTH_LONG).show()
            false
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.widget_pin_manual_hint, Toast.LENGTH_LONG).show()
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
            .onFailure {
                Toast.makeText(activity, R.string.widget_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
    }
}
