package com.ucas.qingxin.signin.attendance

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ucas.qingxin.signin.R

class AttendanceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, AttendanceNotifier.CHANNEL_ID)
            .setContentTitle("自动签到运行中")
            .setContentText("QR 同步跟随后台时间轴")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    companion object {
        private const val NOTIF_ID = 2001
        fun start(context: Context) {
            val i = Intent(context, AttendanceForegroundService::class.java)
            context.startForegroundService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AttendanceForegroundService::class.java))
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        if (app is com.ucas.qingxin.signin.QingxinApp) {
            if (app.attendanceScheduler.getSettings().autoSignEnabled) {
                app.attendanceScheduler.start()
            }
        }
    }
}
