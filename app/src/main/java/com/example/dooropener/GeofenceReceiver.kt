package com.example.dooropener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_GEOFENCE_EVENT = "com.example.dooropener.GEOFENCE_EVENT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_EVENT) return
        val prefs = context.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("all_disabled", false)) return
        val radius = DoorConfig.fenceRadius(prefs).toInt()

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            DoorEvents.record(context, "收到地理圍欄廣播，但內容無法解析", notify = true, eventType = "geofence_payload_invalid")
            return
        }
        if (event.hasError()) {
            val name = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            DoorEvents.record(context, "地理圍欄錯誤：$name（${event.errorCode}）", notify = true, eventType = "geofence_error", fields = org.json.JSONObject().put("errorCode", event.errorCode))
            return
        }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                // 離開設定的圍欄半徑才布防；需再進入才開始精確定位。
                prefs.edit().putBoolean("geofence_armed", true).apply()
                Telemetry.beginTrip(context)
                DoorEvents.record(context, "已離開住家 $radius m 範圍，已布防，等待回家", notify = true, eventType = "geofence_exit", fields = org.json.JSONObject().put("fenceRadiusM", radius))
            }
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (!prefs.getBoolean("geofence_armed", false)) {
                    // 以前這裡是靜默忽略，現在一定留下紀錄與通知，方便判斷為什麼沒動作。
                    DoorEvents.record(
                        context,
                        "偵測到進入住家範圍，但尚未布防（需先離開 $radius m 以外再回來），本次忽略",
                        notify = true, eventType = "geofence_enter_unarmed"
                    )
                    return
                }
                if (!GeofenceManager.hasBackgroundLocation(context)) {
                    DoorEvents.record(
                        context,
                        "已進入住家範圍，但背景位置不是「一律允許」，無法啟動定位確認",
                        notify = true, eventType = "background_location_permission_missing"
                    )
                    return
                }
                Telemetry.ensureTrip(context)
                DoorEvents.record(context, "已進入住家範圍，正在確認位置", notify = true, eventType = "geofence_enter", fields = org.json.JSONObject().put("fenceRadiusM", radius))
                val serviceIntent = Intent(context, DoorService::class.java)
                    .setAction(DoorService.ACTION_CONFIRM_APPROACH)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    DoorEvents.record(
                        context,
                        "無法啟動定位確認：${e.localizedMessage ?: e.javaClass.simpleName}",
                        notify = true, eventType = "approach_service_start_failed"
                    )
                }
            }
            else -> DoorEvents.record(context, "收到未預期的圍欄事件：${event.geofenceTransition}", eventType = "geofence_unexpected_transition")
        }
    }
}
