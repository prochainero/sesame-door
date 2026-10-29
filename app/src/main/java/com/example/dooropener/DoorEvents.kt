package com.example.dooropener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 事件紀錄 + 獨立通知。
 * - record()：寫入 geofence_status 與事件紀錄（設定頁會即時顯示），可選擇同時發出通知。
 * - setStatus()：只更新「最近狀態」，不進事件紀錄（給高頻的定位進度用）。
 * - 通知使用獨立的高重要性頻道、固定 ID，不會被前景服務結束時的 stopForeground 一起移除。
 */
object DoorEvents {
    const val KEY_STATUS = "geofence_status"
    const val KEY_LOG = "event_log"
    private const val MAX_LOG_LINES = 40

    private const val CHANNEL_EVENTS = "door_events"
    private const val NOTIF_ID_EVENT = 2
    const val NOTIF_ID_OPEN = 3

    fun record(context: Context, message: String, notify: Boolean = false, eventType: String = "app_event", fields: JSONObject = JSONObject()) {
        val prefs = context.applicationContext.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        val old = prefs.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() }
        val lines = (listOf("$time  $message") + old).take(MAX_LOG_LINES)
        prefs.edit()
            .putString(KEY_STATUS, message)
            .putString(KEY_LOG, lines.joinToString("\n"))
            .apply()
        // 長期分析用的完整 Log；主畫面仍只顯示最近 40 行。
        Telemetry.recordEvent(context, eventType, message, fields, flushNow = notify)
        if (notify) notify(context, message)
    }

    fun setStatus(context: Context, message: String) {
        context.applicationContext.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, message).apply()
    }

    fun notify(context: Context, message: String) {
        val app = context.applicationContext
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "開門事件提醒", NotificationManager.IMPORTANCE_HIGH)
            )
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
            val n = NotificationCompat.Builder(app, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("芝麻開門")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_EVENT, n)
        } catch (e: Exception) {
            // 通知失敗不能影響開門流程
        }
    }

    /**
     * 抵達時的開門通知：全螢幕通知會直接拉起 OpenDoorActivity（系統允許從背景這樣做）；
     * 若系統只顯示成浮動通知，點一下通知也是同一個入口。
     * OpenDoorActivity 啟動時會自己把這則通知取消，避免重複。
     */
    fun notifyOpen(context: Context, message: String) {
        val app = context.applicationContext
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "開門事件提醒", NotificationManager.IMPORTANCE_HIGH)
            )
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                record(app, "通知權限被關閉，全螢幕備援無法使用")
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
                record(app, "「全螢幕通知」權限未開，備援只會顯示成一般通知（請到設定頁按對應按鈕）")
            }
            val open = PendingIntent.getActivity(
                app,
                7302,
                Intent(app, OpenDoorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(app, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("芝麻開門")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_OPEN, n)
        } catch (e: Exception) {
            // 通知失敗不能影響開門流程
        }
    }
}
