package com.ucas.qingxin.signin.attendance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.ui.MainActivity

class AttendanceNotifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_attendance),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_attendance_desc)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    fun notifyClassSoon(courseName: String) =
        show(ID_CLASS, "即将上课", courseName)

    fun notifyReady(courseName: String) =
        show(ID_READY, "可以签到", courseName)

    fun notifySuccess(courseName: String) =
        show(ID_SUCCESS, "签到成功", courseName)

    fun notifyFailure(message: String) =
        show(ID_FAIL, "签到失败", message)

    fun notifyLoginExpired() =
        show(ID_LOGIN, "登录失效", "请打开应用重新登录")

    private fun show(id: Int, title: String, body: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "attendance"
        private const val ID_CLASS = 1001
        private const val ID_READY = 1002
        private const val ID_SUCCESS = 1003
        private const val ID_FAIL = 1004
        private const val ID_LOGIN = 1005
    }
}
