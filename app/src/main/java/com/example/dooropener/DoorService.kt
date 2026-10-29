package com.example.dooropener

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Low-power design:
 * - Normally register a geofence (radius set in the settings page, default 55m) and stop this service.
 * - On geofence ENTER after leaving home, briefly monitor location to confirm arrival:
 *   N consecutive fixes (default 2) must each be within the arrive distance and the max accuracy.
 * - Near the door: high accuracy at a short interval (default 2.5s); farther away: balanced power.
 * - Preserve the original rule: must leave first, then return before an opening attempt is allowed.
 * - All numbers come from DoorConfig (editable in the settings page, no rebuild needed).
 */
class DoorService : Service() {
    companion object {
        const val PREFS = "door"
        const val ACTION_REGISTER_GEOFENCE = "com.example.dooropener.REGISTER_GEOFENCE"
        const val ACTION_CONFIRM_APPROACH = "com.example.dooropener.CONFIRM_APPROACH"
        const val ACTION_SIMULATE_ARRIVAL = "com.example.dooropener.SIMULATE_ARRIVAL"
        private const val CHANNEL = "door_channel"
        private const val NOTIF_ID = 1

        private const val MIN_POST_GAP_MS = 10_000L
    }

    private lateinit var prefs: SharedPreferences
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var curPriority = -1
    private var curIntervalMs = 0L
    private var lastPost = 0L
    private var confirming = false
    private var streak = 0          // 目前連續符合條件的定位筆數
    private var lastFixTime = 0L    // 避免同一筆定位被重複計入連續筆數
    private var fixCount = 0        // 這一輪確認收到的定位總筆數（回報給伺服器做頻率分析）
    private var confirmStartMs = 0L // 這一輪確認開始的時間（進入圍欄的時刻）
    private var foregroundActive = false
    private var overlayView: View? = null

    private val timeout = Runnable {
        if (confirming) {
            val mins = DoorConfig.timeoutMs(prefs) / 60_000.0
            DoorEvents.record(
                this,
                "位置確認逾時（${DoorConfig.TIMEOUT_MIN.format(mins)} 分鐘內未連續 ${DoorConfig.confirmCount(prefs)} 筆達到 " +
                    "${DoorConfig.arriveRadius(prefs).toInt()} m／誤差 ${DoorConfig.maxAccuracy(prefs).toInt()} m 的條件）；已停止高頻定位，等待下次進入範圍",
                notify = true, eventType = "approach_timeout"
            )
            Telemetry.finishTrip(this)
            stopConfirming()
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleConfirmation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "自動開門", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        try {
            startForegroundCompat(buildNotification(if (action == ACTION_CONFIRM_APPROACH) "確認住家附近的位置…" else "設定省電地理圍欄…"))
        } catch (e: Exception) {
            // 例如背景位置不是「一律允許」時，location 類型前景服務會被系統拒絕
            DoorEvents.record(
                this,
                "無法啟動前景定位服務：${e.javaClass.simpleName}（請確認背景位置為「一律允許」）",
                notify = true, eventType = "foreground_service_failed"
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (prefs.getBoolean("all_disabled", false)) {
            // 已全部停用：不做任何事（前景服務已啟動，所以要正常收掉）
            stopForegroundCompat()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_SIMULATE_ARRIVAL -> {
                val serverMode = prefs.getString("intent", "").isNullOrBlank()
                if (serverMode) {
                    // 伺服器模式：5 秒後以門口座標當作定位，走完整的 /arrive（伺服器會照樣檢查、記錄、開門）
                    DoorEvents.record(this, "模擬抵達（伺服器模式）：5 秒後把門口座標回報給伺服器", eventType = "simulate_started")
                    handler.postDelayed({
                        val lat = prefs.getString("lat", "")?.toDoubleOrNull()
                        val lon = prefs.getString("lon", "")?.toDoubleOrNull()
                        if (lat == null || lon == null) {
                            DoorEvents.record(this, "模擬抵達失敗：沒有門口座標", notify = true, eventType = "simulate_failed")
                        } else {
                            post(lat, lon, 5f, System.currentTimeMillis(), false, "simulate")
                        }
                        handler.postDelayed({
                            stopForegroundCompat()
                            stopSelfResult(startId)
                        }, 15_000L)
                    }, 5_000L)
                } else {
                    // 診斷用：5 秒後以背景服務身分走「抵達後開門」那一段，不經過 GPS／圍欄
                    DoorEvents.record(this, "模擬抵達：5 秒後從背景服務開門，請現在按 Home 離開本 App", eventType = "simulate_started")
                    handler.postDelayed({
                        openDoorFromBackground("模擬抵達")
                        handler.postDelayed({
                            stopForegroundCompat()
                            stopSelfResult(startId)
                        }, 6_000L)
                    }, 5_000L)
                }
            }
            ACTION_CONFIRM_APPROACH -> {
                if (!prefs.getBoolean("geofence_armed", false)) {
                    DoorEvents.record(this, "收到定位確認要求，但目前未布防，已略過", eventType = "approach_ignored_unarmed")
                    stopForegroundCompat()
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                Telemetry.ensureTrip(this)
                DoorEvents.record(this, "開始抵達定位確認", eventType = "approach_started")
                confirming = true
                streak = 0
                lastFixTime = 0L
                fixCount = 0
                confirmStartMs = System.currentTimeMillis()
                curPriority = -1
                curIntervalMs = 0L
                // 剛進入圍欄就是最需要精準的時候：先直接用高精度短間隔，第一筆定位若發現還很遠再降回省電模式
                applyPlan(Priority.PRIORITY_HIGH_ACCURACY, DoorConfig.nearIntervalMs(prefs))
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, DoorConfig.timeoutMs(prefs))
            }
            else -> {
                GeofenceManager.register(this) { success, message ->
                    DoorEvents.record(this, message, notify = true, eventType = "geofence_registration")
                    updateNotification(message)
                    // Geofences run through Play services; no continuous foreground service is needed.
                    if (success) {
                        stopForegroundCompat()
                        stopSelfResult(startId)
                    } else {
                        handler.postDelayed({
                            stopForegroundCompat()
                            stopSelfResult(startId)
                        }, 2500L)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeTinyOverlay()
        client.removeLocationUpdates(callback)
        io.shutdown()
        Telemetry.flushAsync(this, force = true)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        foregroundActive = true
    }

    private fun stopForegroundCompat() {
        foregroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyPlan(priority: Int, intervalMs: Long) {
        if (!confirming) return
        if (priority == curPriority && intervalMs == curIntervalMs) return
        curPriority = priority
        curIntervalMs = intervalMs
        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(1000L))
            .build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                DoorEvents.record(this, "定位請求失敗：${e.localizedMessage ?: "未知錯誤"}", notify = true, eventType = "location_request_failed")
            }
    }

    private fun handleConfirmation(loc: Location) {
        if (!confirming || !prefs.getBoolean("geofence_armed", false)) return
        val doorLat = prefs.getString("lat", "")?.toDoubleOrNull() ?: return
        val doorLon = prefs.getString("lon", "")?.toDoubleOrNull() ?: return
        // 同一筆定位（時間戳相同）只算一次，避免連續筆數被重複計入
        if (loc.time != 0L && loc.time == lastFixTime) return
        lastFixTime = loc.time
        fixCount += 1

        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, doorLat, doorLon, out)
        val dist = out[0]
        val accuracy = loc.accuracy

        // 每筆都即時讀設定，確認途中改設定下一筆就生效
        val arriveRadius = DoorConfig.arriveRadius(prefs)
        val maxAccuracy = DoorConfig.maxAccuracy(prefs)
        val need = DoorConfig.confirmCount(prefs)

        // 靠近時：高精度＋短間隔；距離較遠：省電模式
        val desired = if (dist <= DoorConfig.nearRange(prefs))
            Priority.PRIORITY_HIGH_ACCURACY to DoorConfig.nearIntervalMs(prefs)
        else
            Priority.PRIORITY_BALANCED_POWER_ACCURACY to DoorConfig.farIntervalMs(prefs)
        applyPlan(desired.first, desired.second)

        val ok = accuracy <= maxAccuracy && dist <= arriveRadius
        streak = if (ok) streak + 1 else 0

        // 每筆確認定位都保留必要的分析欄位；只在定位確認階段採樣，不會整天追蹤 GPS。
        val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null
        val movementEstimate = when {
            speedMps == null -> "unknown"
            speedMps < 0.5 -> "stationary_or_slow"
            speedMps < 2.2 -> "walking_or_slow_movement"
            speedMps < 8.0 -> "cycling_or_slow_vehicle"
            else -> "vehicle"
        }
        val sample = JSONObject().apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("accuracyM", accuracy.toDouble())
            put("distanceM", dist.toDouble())
            if (speedMps != null) put("speedMps", speedMps)
            if (loc.hasBearing()) put("bearingDeg", loc.bearing.toDouble())
            put("fixAgeMs", (System.currentTimeMillis() - loc.time).coerceAtLeast(0L))
            put("intervalMs", curIntervalMs)
            put("priority", if (desired.first == Priority.PRIORITY_HIGH_ACCURACY) "high_accuracy" else "balanced_power")
            put("streak", streak)
            put("requiredStreak", need)
            put("fixCount", fixCount)
            put("sessionElapsedS", if (confirmStartMs > 0) ((System.currentTimeMillis() - confirmStartMs) / 1000) else 0)
            put("mock", isMockLocation(loc))
            put("movementEstimate", movementEstimate)
            put("fenceRadiusM", DoorConfig.fenceRadius(prefs).toDouble())
            put("arriveRadiusM", arriveRadius.toDouble())
            put("maxAccuracyM", maxAccuracy.toDouble())
            put("confirmCount", need)
            put("nearIntervalMs", DoorConfig.nearIntervalMs(prefs))
            put("farIntervalMs", DoorConfig.farIntervalMs(prefs))
            put("nearRangeM", DoorConfig.nearRange(prefs).toDouble())
            put("timeoutMin", DoorConfig.timeoutMs(prefs) / 60_000.0)
        }
        Telemetry.recordLocationFix(this, sample, flushNow = fixCount % 12 == 0)

        DoorEvents.setStatus(
            this,
            "精確確認中：距離 ${dist.toInt()} m，誤差 ${accuracy.toInt()} m" +
                "（需 ≤${arriveRadius.toInt()} m 且誤差 ≤${maxAccuracy.toInt()} m，連續符合 $streak/$need 筆）"
        )
        updateNotification("距離 ${dist.toInt()} m（誤差 ${accuracy.toInt()} m），連續 $streak/$need")

        if (streak < need) return

        val intentUri = prefs.getString("intent", "")?.trim().orEmpty()
        if (intentUri.isNotEmpty()) {
            // Disarm before attempting the action, so duplicate location callbacks cannot open twice.
            prefs.edit().putBoolean("geofence_armed", false).apply()
            openDoorFromBackground("已確認抵達（連續 $need 筆符合，距離 ${dist.toInt()} m，誤差 ${accuracy.toInt()} m）")
            stopConfirming()
        } else if (System.currentTimeMillis() - lastPost >= MIN_POST_GAP_MS) {
            lastPost = System.currentTimeMillis()
            post(loc.latitude, loc.longitude, accuracy, loc.time, isMockLocation(loc), "arrive", if (loc.hasSpeed()) loc.speed else null, if (loc.hasBearing()) loc.bearing else null)
        }
    }

    private fun isMockLocation(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider

    /** 抵達（或模擬抵達）後的開門動作：與點圖示／語音同一入口，另掛透明視窗與全螢幕通知當備援。 */
    private fun openDoorFromBackground(reason: String) {
        showTinyOverlay()
        DoorEvents.notifyOpen(this, "已抵達住家，正在開門")
        try {
            val i = Intent(this, OpenDoorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            DoorEvents.record(this, "$reason，已從背景服務呼叫開門入口（若沒看到「開門入口已啟動」，就是被系統擋下）", notify = true, eventType = "open_activity_requested")
        } catch (e: Exception) {
            DoorEvents.record(this, "$reason，但直接啟動開門入口失敗：${e.localizedMessage ?: "未知錯誤"}", notify = true)
        }
    }

    /**
     * 掛一個 1px 透明的「上層視窗」：有可見視窗的 App，系統才比較願意讓它從背景啟動畫面
     * （新版 Android 對只有權限、沒有可見視窗的情況收得更緊）。幾秒後自動移除。
     */
    private fun showTinyOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            DoorEvents.record(this, "沒有「顯示在上層」權限，無法掛上背景啟動用的透明視窗", eventType = "overlay_permission_missing")
            return
        }
        try {
            removeTinyOverlay()
            val wm = getSystemService(WindowManager::class.java)
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            overlayView = v
            handler.postDelayed({ removeTinyOverlay() }, 4_000L)
        } catch (e: Exception) {
            DoorEvents.record(this, "掛上透明視窗失敗：${e.javaClass.simpleName}", eventType = "overlay_attach_failed")
        }
    }

    private fun removeTinyOverlay() {
        overlayView?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (e: Exception) { }
        }
        overlayView = null
    }

    private fun stopConfirming() {
        confirming = false
        streak = 0
        handler.removeCallbacks(timeout)
        client.removeLocationUpdates(callback)
        Telemetry.flushAsync(this, force = true)
        stopForegroundCompat()
        stopSelf()
    }

    private fun post(
        lat: Double, lon: Double, acc: Float,
        fixTime: Long = 0L, mock: Boolean = false, source: String = "arrive",
        speedMps: Float? = null, bearingDeg: Float? = null
    ) {
        val url = prefs.getString("url", "") ?: return
        val secret = prefs.getString("secret", "") ?: return
        // 診斷欄位：伺服器會原樣記下來，累積後能分析「圍欄太小／定位飄移／頻率不足」到底是哪個
        val payload = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("accuracy", acc.toDouble())
            put("source", source)
            put("profileId", Telemetry.profileId(this@DoorService))
            put("deviceId", Telemetry.deviceId(this@DoorService))
            val session = Telemetry.sessionId(this@DoorService)
            if (session.isNotBlank()) put("sessionId", session)
            if (fixTime > 0) put("fixTime", fixTime)
            if (speedMps != null) put("speedMps", speedMps.toDouble())
            if (bearingDeg != null) put("bearingDeg", bearingDeg.toDouble())
            put("mock", mock)
            put("streak", streak)
            put("fixes", fixCount)
            if (confirmStartMs > 0) put("sinceEnterS", (System.currentTimeMillis() - confirmStartMs) / 1000)
            put("intervalMs", curIntervalMs)
            put("cfg", JSONObject().apply {
                put("fence", DoorConfig.fenceRadius(prefs).toDouble())
                put("arrive", DoorConfig.arriveRadius(prefs).toDouble())
                put("maxAcc", DoorConfig.maxAccuracy(prefs).toDouble())
                put("need", DoorConfig.confirmCount(prefs))
                put("nearMs", DoorConfig.nearIntervalMs(prefs))
                put("farMs", DoorConfig.farIntervalMs(prefs))
            })
        }.toString()
        io.execute {
            val requestStartedMs = System.currentTimeMillis()
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 25_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Secret", secret)
                AppAuth.token(this@DoorService).takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json?.optBoolean("opened") == true) {
                    prefs.edit().putBoolean("geofence_armed", false).apply()
                    val msg = if (json.optBoolean("deduped")) "伺服器回報：剛剛已開過門" else "伺服器已開門"
                    DoorEvents.record(this, msg, notify = true, eventType = "arrive_opened", fields = JSONObject()
                        .put("httpStatus", conn.responseCode)
                        .put("responseMs", System.currentTimeMillis() - requestStartedMs))
                    Telemetry.finishTrip(this)
                    handler.post { stopConfirming() }
                } else {
                    val why = "伺服器：" + (json?.optString("reason") ?: text.take(60))
                    DoorEvents.record(this, why, eventType = "server_arrive_rejected", fields = JSONObject().put("httpStatus", conn.responseCode).put("responseMs", System.currentTimeMillis() - requestStartedMs).put("reason", json?.optString("reason") ?: "unknown"))
                    DoorEvents.setStatus(this, why)
                    updateNotification(why)
                }
            } catch (e: Exception) {
                val why = if (url.startsWith("http://")) "網址要用 https://（App 不允許明文連線）"
                else "連線失敗：" + (e.message ?: "未知錯誤")
                DoorEvents.record(this, why, eventType = "server_request_failed", fields = JSONObject().put("responseMs", System.currentTimeMillis() - requestStartedMs).put("reason", why.take(100)))
                DoorEvents.setStatus(this, why)
                updateNotification(why)
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Door Sesame 省電定位")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        // 前景服務結束後不要再貼出無法滑掉的常駐通知
        if (!foregroundActive) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
