package com.example.dooropener

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.atomic.AtomicBoolean

/** Registers a low-power geofence around the saved door coordinates. */
object GeofenceManager {
    const val GEOFENCE_ID = "door_home_area"
    private const val REQUEST_CODE = 7301
    private const val RESPONSIVENESS_MS = 15_000
    private const val FRESH_LOCATION_MS = 10 * 60_000L
    private const val BOOT_FINISH_TIMEOUT_MS = 8_000L

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION_GEOFENCE_EVENT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context, callback: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString("lat", "")?.toDoubleOrNull()
        val lon = prefs.getString("lon", "")?.toDoubleOrNull()
        if (lat == null || lon == null) {
            callback(false, "請先設定門口座標")
            return
        }
        if (!hasFineLocation(app) || !hasBackgroundLocation(app)) {
            callback(false, "缺少定位權限：需要「精確位置」與背景位置「一律允許」")
            return
        }

        val added = AtomicBoolean(false)
        val addFence = {
            if (added.compareAndSet(false, true)) {
                try {
                    addGeofence(app, prefs, lat, lon, callback)
                } catch (e: SecurityException) {
                    callback(false, "地理圍欄設定失敗：沒有定位權限")
                }
            }
        }

        // 不能只依賴 INITIAL_TRIGGER_EXIT（事件可能很晚才到）：
        // 註冊時若已能確定人在圍欄外，就直接布防。
        try {
            LocationServices.getFusedLocationProviderClient(app).lastLocation
                .addOnSuccessListener { loc ->
                    armIfClearlyOutside(app, prefs, loc, lat, lon)
                    addFence()
                }
                .addOnFailureListener { addFence() }
        } catch (e: SecurityException) {
            addFence()
        }
    }

    private fun armIfClearlyOutside(context: Context, prefs: SharedPreferences, loc: Location?, lat: Double, lon: Double) {
        if (loc == null) return
        if (System.currentTimeMillis() - loc.time > FRESH_LOCATION_MS) return
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, out)
        if (out[0] - loc.accuracy > DoorConfig.fenceRadius(prefs)) {
            prefs.edit().putBoolean("geofence_armed", true).apply()
            DoorEvents.record(context, "註冊時人在圍欄外約 ${out[0].toInt()} m，已直接布防", eventType = "geofence_armed_at_registration", fields = org.json.JSONObject().put("distanceM", out[0].toDouble()))
        }
    }

    @SuppressLint("MissingPermission")
    private fun addGeofence(
        context: Context,
        prefs: SharedPreferences,
        lat: Double,
        lon: Double,
        callback: (Boolean, String) -> Unit
    ) {
        val radius = DoorConfig.fenceRadius(prefs)
        val fence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(lat, lon, radius)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            // 初次註冊時若人已在家外，也要收到 EXIT 來布防；人在家時不會因 ENTER 而啟動確認。
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(fence)
            .build()

        LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context))
            .addOnSuccessListener {
                prefs.edit().putBoolean("geofence_enabled", true).apply()
                val armed = if (prefs.getBoolean("geofence_armed", false)) "已布防" else "尚未布防（需先離開 ${radius.toInt()} m 外）"
                callback(true, "已啟用地理圍欄（半徑 ${radius.toInt()} 公尺，圓心 $lat, $lon）；$armed")
            }
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                val hint = when (code) {
                    GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                        "（請開啟手機定位，並在定位設定打開 Wi-Fi 掃描／Google 定位精確度）"
                    GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                        "（背景位置請設為「一律允許」）"
                    else -> ""
                }
                callback(false, "地理圍欄設定失敗：${e.localizedMessage ?: e.javaClass.simpleName}$hint")
            }
    }

    fun remove(context: Context, callback: ((Boolean) -> Unit)? = null) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
            .addOnCompleteListener { task -> callback?.invoke(task.isSuccessful) }
        context.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("geofence_enabled", false)
            .putBoolean("geofence_armed", false)
            .apply()
    }

    /** 開機或 App 更新後重新註冊（系統會清掉舊的地理圍欄）。 */
    fun reRegisterAfterBoot(context: Context, finished: () -> Unit = {}) {
        val prefs: SharedPreferences = context.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("geofence_enabled", false)) {
            finished()
            return
        }
        // goAsync 有時間限制：定位逾時也要放行
        val done = AtomicBoolean(false)
        val finishOnce = { if (done.compareAndSet(false, true)) finished() }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, BOOT_FINISH_TIMEOUT_MS)
        register(context) { ok, msg ->
            DoorEvents.record(context, "開機／更新後重新註冊：$msg", notify = !ok, eventType = "geofence_re_registered", fields = org.json.JSONObject().put("success", ok))
            finishOnce()
        }
    }
}
