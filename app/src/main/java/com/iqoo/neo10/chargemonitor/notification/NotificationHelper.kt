package com.iqoo.neo10.chargemonitor.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iqoo.neo10.chargemonitor.R
import com.iqoo.neo10.chargemonitor.ui.main.MainActivity

object NotificationHelper {

    const val CHANNEL_MONITOR = "charging_monitor_channel"
    const val CHANNEL_ALERTS = "charging_alerts_channel"

    const val NOTIF_ID_MONITOR = 1001
    const val NOTIF_ID_HIGH_TEMP = 2001
    const val NOTIF_ID_FULL = 2002

    private const val HIGH_TEMP_THRESHOLD = 42f

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CHANNEL_MONITOR) == null) {
            val ch = NotificationChannel(
                CHANNEL_MONITOR,
                "充电监测常驻通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "充电过程中显示实时电量与功率的常驻通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
            val ch = NotificationChannel(
                CHANNEL_ALERTS,
                "充电告警提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "充电高温、电量充满等提醒"
                enableVibration(true)
                enableLights(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun mainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 前台服务常驻通知，展示实时充电信息 */
    fun buildMonitorNotification(
        context: Context,
        capacity: Int,
        power: Float,
        temp: Float,
        source: String
    ): Notification {
        val title = "充电中  $capacity%  ·  $source"
        val text = String.format("功率 %.1fW  ·  温度 %.1f°C", power, temp)
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bolt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent(context))
            .build()
    }

    /** 充满提醒 */
    fun notifyFullCharge(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle("电量已充满")
            .setContentText("电池已充至 100%，建议断开充电器")
            .setSmallIcon(R.drawable.ic_battery_full)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent(context))
            .build()
        nm.notify(NOTIF_ID_FULL, notif)
    }

    /** 高温提醒 */
    fun notifyHighTemp(context: Context, temp: Float) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle("充电温度过高")
            .setContentText(String.format("电池温度 %.1f°C，已超过 42°C 阈值，请留意", temp))
            .setSmallIcon(R.drawable.ic_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent(context))
            .build()
        nm.notify(NOTIF_ID_HIGH_TEMP, notif)
    }

    fun isHighTemp(temp: Float) = temp >= HIGH_TEMP_THRESHOLD
}
