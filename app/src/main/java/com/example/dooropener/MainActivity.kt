package com.example.dooropener

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var etProfile: EditText
    private lateinit var etUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var etIntent: EditText
    private lateinit var status: TextView
    private lateinit var authStatus: TextView
    private lateinit var diag: TextView
    // 進階參數欄位（地理圍欄半徑、抵達距離、偵測頻率、連續筆數…），定義見 DoorConfig
    private val cfgFields = LinkedHashMap<DoorConfig.Spec, EditText>()

    // 服務與 receiver 與本頁在同一個 process，寫入 prefs 時這裡會即時刷新
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshDiagnostics() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        fun field(
            hint: String,
            key: String,
            type: Int = InputType.TYPE_CLASS_TEXT,
            defaultValue: String = ""
        ): EditText = EditText(this).apply {
            this.hint = hint
            inputType = type
            val savedValue = prefs.getString(key, null)
            setText(if (savedValue.isNullOrBlank() && defaultValue.isNotEmpty()) defaultValue else savedValue ?: "")
            root.addView(this)
        }

        etProfile = field("個人代號（三支手機請填不同代號，例如 A、B、C）", "profile_id", defaultValue = Telemetry.profileId(this))
        root.addView(TextView(this).apply {
            text = "個人代號只用來分開歷史紀錄；請讓三支手機各自使用固定且不同的代號。裝置識別碼會由 App 自動產生。"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
        })
        etUrl = field("伺服器網址，例如 https://你的網域/arrive", "url", InputType.TYPE_TEXT_VARIATION_URI)
        etSecret = field("密碼（DOOR_SHARED_SECRET）", "secret", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etLat = field("門口緯度", "lat", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, "24.988026")
        etLon = field("門口經度", "lon", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, "121.450173")

        etIntent = field("（選填）eWeLink 捷徑的 Intent URI；留空＝改走伺服器雲端開門", "intent")

        // ── 進階參數（可收合）：不用重新編譯就能調整圍欄與定位行為 ──
        val btnCfgToggle = Button(this).apply { text = "▼ 進階參數：圍欄半徑／抵達距離／偵測頻率／連續筆數" }
        val cfgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        cfgBox.addView(TextView(this).apply {
            text = "改完請按下方「儲存並啟動」才會套用；欄位留空＝使用預設值。"
            textSize = 12f
            setPadding(0, 10, 0, 0)
        })
        DoorConfig.ALL.forEach { spec ->
            cfgBox.addView(TextView(this).apply {
                text = "${spec.label}（${spec.unit}｜預設 ${spec.defaultText}｜範圍 ${spec.rangeText}）"
                textSize = 13f
                setPadding(0, 24, 0, 0)
            })
            val et = EditText(this).apply {
                hint = "預設 ${spec.defaultText}"
                inputType = InputType.TYPE_CLASS_NUMBER or
                    (if (spec.decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                setText(DoorConfig.currentText(prefs, spec))
            }
            cfgBox.addView(et)
            if (spec.note.isNotEmpty()) {
                cfgBox.addView(TextView(this).apply {
                    text = spec.note
                    textSize = 11f
                    setTextColor(0xFF777777.toInt())
                })
            }
            cfgFields[spec] = et
        }
        val btnCfgSync = Button(this).apply { text = "從伺服器同步參數（只在按下時連線一次）" }
        val btnCfgDefaults = Button(this).apply { text = "進階參數恢復成預設值" }
        cfgBox.addView(btnCfgSync)
        cfgBox.addView(btnCfgDefaults)
        root.addView(btnCfgToggle)
        root.addView(cfgBox)
        btnCfgToggle.setOnClickListener {
            val open = cfgBox.visibility != View.VISIBLE
            cfgBox.visibility = if (open) View.VISIBLE else View.GONE
            btnCfgToggle.text = (if (open) "▲ " else "▼ ") + "進階參數：圍欄半徑／抵達距離／偵測頻率／連續筆數"
        }
        btnCfgSync.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val secret = etSecret.text.toString().trim()
            if (!ServerApi.isConfigured(url, secret)) {
                status.text = "請先填好上面的「伺服器網址」與「密碼」"
                return@setOnClickListener
            }
            status.text = "正在向伺服器取得參數…"
            Thread {
                val r = ServerApi.fetchConfig(url, secret, AppAuth.token(this@MainActivity))
                runOnUiThread {
                    if (r.error != null) {
                        status.text = "同步失敗：${r.error}"
                    } else {
                        var n = 0
                        cfgFields.forEach { (spec, et) ->
                            r.params[spec.key]?.let { v -> et.setText(spec.format(v)); n++ }
                        }
                        status.text = "已從伺服器取得 $n 個參數（版本 ${r.version}），欄位已更新。確認後按「儲存並啟動」才會套用。"
                    }
                }
            }.start()
        }
        btnCfgDefaults.setOnClickListener {
            cfgFields.forEach { (spec, et) -> et.setText(spec.defaultText) }
            status.text = "已填入預設值（圍欄 ${DoorConfig.FENCE_RADIUS.defaultText} 公尺、連續 ${DoorConfig.CONFIRM_COUNT.defaultText} 筆…）。按「儲存並啟動」後才會套用。"
        }

        val btnLogin = Button(this).apply { text = "Google 登入／重新登入（白名單驗證）" }
        authStatus = TextView(this).apply { setPadding(0, 8, 0, 16) }
        val btnLogout = Button(this).apply { text = "登出 Google 帳號" }
        val btnWizard = Button(this).apply { text = "一鍵設定所有權限（依序帶你跳轉）" }
        val btnStart = Button(this).apply { text = "儲存並啟動" }
        val btnTest = Button(this).apply { text = "手動開門" }
        val btnVoice = Button(this).apply { text = "語音開門（按下後說：開門）" }
        val btnStop = Button(this).apply { text = "停用地理圍欄服務（保留圖示／語音開門）" }
        val btnAllOff = Button(this).apply { text = "全部停用（含地理圍欄、圖示、語音開門）" }
        val btnAllOn = Button(this).apply { text = "解除全部停用" }
        val btnBattery = Button(this).apply { text = "開啟電池最佳化設定（請設為不受限制）" }
        val btnSim = Button(this).apply { text = "模擬抵達（5 秒後從背景開門，按下後請按 Home）" }
        val btnFsi = Button(this).apply { text = "開啟「全螢幕通知」權限（Android 14 以上需手動允許）" }
        status = TextView(this).apply { setPadding(0, 30, 0, 0) }
        diag = TextView(this).apply { setPadding(0, 30, 0, 0); textSize = 13f }
        root.addView(btnLogin)
        root.addView(authStatus)
        root.addView(btnLogout)
        root.addView(btnWizard)
        root.addView(btnStart)
        root.addView(btnTest)
        root.addView(btnVoice)
        root.addView(btnStop)
        root.addView(btnAllOff)
        root.addView(btnAllOn)
        root.addView(btnBattery)
        root.addView(btnFsi)
        root.addView(btnSim)
        root.addView(status)
        root.addView(diag)

        btnLogin.setOnClickListener {
            try { AppAuth.startLogin(this); status.text = "已開啟瀏覽器，請使用白名單內的 Google 帳號登入。" }
            catch (e: Exception) { status.text = "無法開啟登入頁：${e.message}" }
        }
        btnLogout.setOnClickListener {
            AppAuth.logout(this)
            authStatus.text = "尚未登入。開門功能已鎖定。"
            status.text = "已登出。"
        }
        refreshAuthStatus()
        AppAuth.handleCallback(this, intent) { msg -> status.text = msg; refreshAuthStatus() }

        btnWizard.setOnClickListener {
            prefsForService().edit().putBoolean("oem_done", false).apply()
            wizardStep = -1
            wizardAdvance()
        }

        btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        btnSim.setOnClickListener {
            if (!AppAuth.hasValidToken(this)) { status.text = "請先完成 Google 白名單登入。"; return@setOnClickListener }
            val serverReady = ServerApi.isConfigured(etUrl.text.toString(), etSecret.text.toString())
            if (etIntent.text.isBlank() && !serverReady) {
                status.text = "請先貼上 Intent URI，或填好伺服器網址與密碼"
                return@setOnClickListener
            }
            Telemetry.setProfileId(this, etProfile.text.toString().trim().ifBlank { Telemetry.profileId(this) })
            prefs.edit()
                .putString("intent", etIntent.text.toString().trim())
                .putString("url", etUrl.text.toString().trim())
                .putString("secret", etSecret.text.toString().trim())
                .putString("lat", etLat.text.toString().trim())
                .putString("lon", etLon.text.toString().trim())
                .apply()
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, DoorService::class.java).setAction(DoorService.ACTION_SIMULATE_ARRIVAL)
                )
                status.text = if (etIntent.text.isBlank()) "已排程：5 秒後以門口座標模擬抵達，直接回報給伺服器。請站在門邊觀察門有沒有動"
                else "已排程：5 秒後模擬抵達。請現在按 Home 鍵回桌面，觀察 eWeLink 有沒有被叫起"
            } catch (e: Exception) {
                status.text = "無法啟動模擬：${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }

        btnFsi.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                } else {
                    status.text = "此 Android 版本不需要另外開啟全螢幕通知權限"
                }
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        btnStart.setOnClickListener {
            if (!AppAuth.hasValidToken(this)) { status.text = "請先按「Google 登入／重新登入」並通過白名單驗證，再啟動開門功能。"; return@setOnClickListener }
            val intentUri = etIntent.text.toString().trim()
            val latStr = etLat.text.toString().trim()
            val lonStr = etLon.text.toString().trim()
            val hasCoords = latStr.isNotEmpty() || lonStr.isNotEmpty()

            val serverUrl = etUrl.text.toString().trim()
            val serverSecret = etSecret.text.toString().trim()
            val profileId = etProfile.text.toString().trim()
            if (profileId.isBlank() || profileId.length > 64 || profileId.any { it.isISOControl() }) {
                status.text = "請填入 1～64 字且不含控制字元的個人代號；三支手機請分別使用 A、B、C 等不同代號。"
                return@setOnClickListener
            }
            if (intentUri.isEmpty() && !ServerApi.isConfigured(serverUrl, serverSecret)) {
                status.text = "請二選一：貼上 Intent URI（本機捷徑），或填好「伺服器網址」與「密碼」（雲端開門）"
                return@setOnClickListener
            }
            if (intentUri.isEmpty() && !serverUrl.startsWith("https://")) {
                status.text = "伺服器網址必須以 https:// 開頭（App 不允許明文連線）"
                return@setOnClickListener
            }
            if (hasCoords && (latStr.toDoubleOrNull() == null || lonStr.toDoubleOrNull() == null)) {
                status.text = "經緯度要兩欄都填（數字），或兩欄都留空"
                return@setOnClickListener
            }

            // 進階參數：先全部驗證，有錯就整個不存，避免存到一半
            val cfgValues = LinkedHashMap<DoorConfig.Spec, Double?>()
            try {
                cfgFields.forEach { (spec, et) -> cfgValues[spec] = DoorConfig.parseInput(spec, et.text.toString()) }
            } catch (e: IllegalArgumentException) {
                status.text = (e.message ?: "進階參數有誤") + "（請展開「進階參數」修正）"
                return@setOnClickListener
            }
            val fence = cfgValues[DoorConfig.FENCE_RADIUS] ?: DoorConfig.FENCE_RADIUS.default
            val arrive = cfgValues[DoorConfig.ARRIVE_RADIUS] ?: DoorConfig.ARRIVE_RADIUS.default
            if (arrive > fence) {
                status.text = "抵達距離（${DoorConfig.ARRIVE_RADIUS.format(arrive)} 公尺）不能大於地理圍欄半徑（${DoorConfig.FENCE_RADIUS.format(fence)} 公尺）"
                return@setOnClickListener
            }

            prefs.edit().apply {
                cfgValues.forEach { (spec, v) ->
                    if (v == null) remove(spec.key) else putString(spec.key, spec.format(v))
                }
            }.apply()

            Telemetry.setProfileId(this, profileId)
            prefs.edit()
                .putString("url", etUrl.text.toString().trim())
                .putString("secret", etSecret.text.toString().trim())
                .putString("lat", latStr)
                .putString("lon", lonStr)
                .putString("intent", intentUri)
                .putBoolean("all_disabled", false)
                .apply()
            DoorEvents.record(this, "設定已儲存（個人代號 ${profileId}）", eventType = "settings_saved")
            Telemetry.flushAsync(this, force = true)

            if (!hasCoords) {
                // 沒填座標：移除舊地理圍欄，只保留手動／語音開門。
                GeofenceManager.remove(this)
                stopService(Intent(this, DoorService::class.java))
                status.text = "已儲存。未填座標，不會自動定位或開門，可用圖示或語音「開啟大門」開門"
            } else if (!Settings.canDrawOverlays(this)) {
                status.text = "自動開門需要「顯示在其他應用程式上層」權限，請在接下來的畫面開啟，再回來按一次啟動"
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                ensurePermissionsThenStart()
            }
        }

        btnTest.setOnClickListener {
            if (!AppAuth.hasValidToken(this)) { status.text = "請先完成 Google 白名單登入。"; return@setOnClickListener }
            status.text = sendIntentNow()
        }

        btnVoice.setOnClickListener {
            if (!AppAuth.hasValidToken(this)) { status.text = "請先完成 Google 白名單登入。"; return@setOnClickListener }
            if (etIntent.text.isBlank() && !ServerApi.isConfigured(etUrl.text.toString(), etSecret.text.toString())) {
                status.text = "請先貼上 Intent URI，或填好伺服器網址與密碼"
                return@setOnClickListener
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "請說：開門")
            }
            try {
                startActivityForResult(i, REQ_VOICE)
            } catch (e: Exception) {
                status.text = "這支手機沒有語音辨識功能：" + (e.message ?: "")
            }
        }

        btnAllOff.setOnClickListener {
            prefs.edit().putBoolean("all_disabled", true).apply()
            GeofenceManager.remove(this)
            stopService(Intent(this, DoorService::class.java))
            getSystemService(NotificationManager::class.java).cancelAll()
            status.text = "已全部停用：地理圍欄已移除，圖示／語音／捷徑也不會開門（點圖示只會開啟本設定頁）。要恢復請按「解除全部停用」。"
            refreshDiagnostics()
        }

        btnAllOn.setOnClickListener {
            prefs.edit().putBoolean("all_disabled", false).apply()
            status.text = "已解除全部停用：圖示／語音開門恢復。要自動開門，請再按一次「儲存並啟動」。"
            refreshDiagnostics()
        }

        btnStop.setOnClickListener {
            GeofenceManager.remove(this)
            stopService(Intent(this, DoorService::class.java))
            status.text = "已停止自動定位並移除地理圍欄；手動／語音開門仍可使用"
        }

        setContentView(ScrollView(this).apply { addView(root) })
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefListener)
        refreshDiagnostics()
        Telemetry.flushAsync(this, force = true)
        if (wizardStep >= 0) wizardAdvance()
    }

    override fun onPause() {
        getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    /** 顯示圍欄是否啟用、是否已布防、已儲存座標、各項權限，以及最近的事件紀錄。 */
    private fun refreshDiagnostics() {
        val p = getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = GeofenceManager.hasBackgroundLocation(this)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val overlay = Settings.canDrawOverlays(this)
        val fsi = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val lat = p.getString("lat", "").orEmpty()
        val lon = p.getString("lon", "").orEmpty()
        val log = p.getString(DoorEvents.KEY_LOG, "").orEmpty().ifBlank { "（尚無事件）" }

        diag.text = buildString {
            appendLine("── 即時狀態 ──")
            appendLine("地理圍欄：" + if (p.getBoolean("geofence_enabled", false)) "已啟用" else "未啟用")
            appendLine("全部停用：" + if (p.getBoolean("all_disabled", false)) "是（圖示／語音開門停用中）" else "否")
            appendLine("布防：" + if (p.getBoolean("geofence_armed", false)) "是（已離開過，回來會觸發）" else "否（需先離開 ${DoorConfig.fenceRadius(p).toInt()} m 外）")
            appendLine(
                "目前參數：圍欄 ${DoorConfig.fenceRadius(p).toInt()} m｜抵達 ${DoorConfig.arriveRadius(p).toInt()} m｜" +
                    "誤差 ≤${DoorConfig.maxAccuracy(p).toInt()} m｜連續 ${DoorConfig.confirmCount(p)} 筆｜" +
                    "靠近 ${DoorConfig.NEAR_INTERVAL.format(DoorConfig.nearIntervalMs(p) / 1000.0)} 秒（≤${DoorConfig.nearRange(p).toInt()} m）｜" +
                    "遠處 ${DoorConfig.FAR_INTERVAL.format(DoorConfig.farIntervalMs(p) / 1000.0)} 秒｜" +
                    "逾時 ${DoorConfig.TIMEOUT_MIN.format(DoorConfig.timeoutMs(p) / 60_000.0)} 分鐘"
            )
            appendLine("已儲存座標：" + if (lat.isBlank() || lon.isBlank()) "（未設定）" else "$lat, $lon")
            appendLine("個人紀錄：${Telemetry.profileId(this)}｜裝置 ID：${Telemetry.deviceId(this).take(8)}…｜待上傳 Log：${Telemetry.pendingCount(this)} 筆")
            appendLine("Log 上傳：${Telemetry.lastUploadStatus(this)}")
            appendLine("權限：精確位置${mark(fine)}｜背景位置「一律允許」${mark(bg)}｜通知${mark(notif)}｜電池不受限${mark(battery)}｜顯示在上層${mark(overlay)}｜全螢幕通知${mark(fsi)}")
            appendLine("最近狀態：" + p.getString(DoorEvents.KEY_STATUS, "").orEmpty().ifBlank { "（無）" })
            appendLine()
            appendLine("── 事件紀錄（新→舊）──")
            append(log)
        }
    }

    private val REQ_VOICE = 100

    // ── 一鍵設定精靈：依序帶你跳到每個設定頁，已完成的自動略過 ──
    private var wizardStep = -1

    private class WizStep(val hint: String, val done: () -> Boolean, val open: () -> Unit)

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun isXiaomi(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) ||
            Build.MANUFACTURER.equals("POCO", true) || Build.BRAND.equals("Xiaomi", true)

    private fun wizardSteps(): List<WizStep> {
        val list = mutableListOf<WizStep>()
        list += WizStep(
            "允許「精確位置」與通知",
            { granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) },
            {
                val need = mutableListOf<String>()
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    need += Manifest.permission.ACCESS_FINE_LOCATION
                    need += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    need += Manifest.permission.POST_NOTIFICATIONS
                }
                requestPermissions(need.toTypedArray(), 11)
            }
        )
        list += WizStep(
            "背景位置：到「權限 > 位置」選「一律允許」",
            { GeofenceManager.hasBackgroundLocation(this) },
            {
                if (Build.VERSION.SDK_INT == 29) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11)
                } else {
                    openAppDetails()
                }
            }
        )
        list += WizStep(
            "開啟「顯示在其他應用程式上層」",
            { Settings.canDrawOverlays(this) },
            {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        )
        list += WizStep(
            "電池：允許本 App 不受限制（略過最佳化）",
            { getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true },
            {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        openAppDetails()
                    }
                }
            }
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += WizStep(
                "開啟「全螢幕通知」",
                { getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
                {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        openAppDetails()
                    }
                }
            )
        }
        list += WizStep(
            if (isXiaomi())
                "小米／HyperOS：把「鎖定螢幕上顯示」「後台彈出介面」「顯示彈出式視窗」都打開；之後再到「自啟動」允許本 App"
            else
                "廠牌額外設定：到權限頁找「鎖定螢幕上顯示」「背景彈出視窗」「自啟動」並允許",
            { prefsForService().getBoolean("oem_done", false) },
            {
                prefsForService().edit().putBoolean("oem_done", true).apply()
                try {
                    if (isXiaomi()) {
                        startActivity(
                            Intent("miui.intent.action.APP_PERM_EDITOR")
                                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                                .putExtra("extra_pkgname", packageName)
                        )
                    } else {
                        openAppDetails()
                    }
                } catch (e: Exception) {
                    openAppDetails()
                }
            }
        )
        return list
    }

    private fun wizardAdvance() {
        val steps = wizardSteps()
        while (true) {
            wizardStep++
            if (wizardStep >= steps.size) {
                wizardStep = -1
                val left = steps.filter { it !== steps.last() && !it.done() }.map { it.hint }
                status.text = if (left.isEmpty())
                    "設定完成。最後請按一次「儲存並啟動」註冊地理圍欄。"
                else
                    "還有未完成：\n" + left.joinToString("\n") { "・$it" } + "\n（可再按一次精靈重試）"
                refreshDiagnostics()
                return
            }
            val st = steps[wizardStep]
            if (st.done()) continue
            status.text = "步驟 ${wizardStep + 1}/${steps.size}：${st.hint}\n完成後返回本 App，會自動帶你到下一項"
            try {
                st.open()
            } catch (e: Exception) {
                openAppDetails()
            }
            return
        }
    }

    /**
     * 立即開門，回傳要顯示的結果文字。
     * 有填 Intent 就送本機捷徑；沒填就請伺服器開（POST /open，結果稍後顯示在狀態列）。
     */
    private fun sendIntentNow(): String {
        val uri = etIntent.text.toString().trim()
        if (uri.isEmpty()) {
            val url = etUrl.text.toString().trim()
            val secret = etSecret.text.toString().trim()
            if (!ServerApi.isConfigured(url, secret)) return "請先貼上 Intent URI，或填好伺服器網址與密碼"
            Thread {
                val r = ServerApi.postOpen(url, secret, Telemetry.profileId(this@MainActivity), Telemetry.deviceId(this@MainActivity), AppAuth.token(this@MainActivity))
                runOnUiThread { status.text = if (r.opened) "伺服器已開門" else "伺服器沒有開門：${r.reason}" }
            }.start()
            return "已請伺服器開門，等待回應…"
        }
        return try {
            startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME))
            "已送出，請看 eWeLink 有沒有反應"
        } catch (e: Exception) {
            "失敗：" + (e.message ?: "未知錯誤")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppAuth.handleCallback(this, intent) { msg -> status.text = msg; refreshAuthStatus() }
    }

    private fun refreshAuthStatus() {
        if (!::authStatus.isInitialized) return
        authStatus.text = if (AppAuth.hasValidToken(this)) "已登入：${AppAuth.email(this)}（開門授權有效）" else "尚未登入或登入已過期；開門功能鎖定。"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        if (resultCode != RESULT_OK) {
            status.text = "沒有聽到指令"
            return
        }
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        if (heard.any { it.contains("開門") || it.contains("开门") }) {
            status.text = "聽到「開門」。" + sendIntentNow()
        } else {
            status.text = "聽到：" + (heard.firstOrNull() ?: "（空白）") + "。不是「開門」，沒有動作"
        }
    }

    private fun prefsForService() = getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        // 通知權限只問一次，被拒絕也不擋住地理圍欄（但會看不到提醒）
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefsForService().getBoolean("asked_notif", false)
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            status.text = "請在應用程式資訊 > 權限 > 位置，選擇「一律允許」，回來後再按一次「儲存並啟動」"
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT == 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            status.text = "請允許背景位置權限，再回來按一次「儲存並啟動」"
            return
        }
        // 註冊地理圍欄後即停止服務；平常不再持續輪詢位置。
        stopService(Intent(this, DoorService::class.java))
        prefsForService().edit().putBoolean("geofence_armed", false).apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorService::class.java).setAction(DoorService.ACTION_REGISTER_GEOFENCE)
        )
        val notifWarn = if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            "\n⚠ 通知權限未開，將看不到進出圍欄的提醒。" else ""
        val radiusNow = DoorConfig.fenceRadius(prefsForService()).toInt()
        status.text = "正在設定省電地理圍欄（約 $radiusNow 公尺）。離開範圍後再回家，才會短暫啟動精確定位。\n請允許背景位置，並將本 App 電池設為「不受限制」，以免系統延遲地理圍欄事件。" + notifWarn
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1) {
            prefsForService().edit().putBoolean("asked_notif", true).apply()
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                ensurePermissionsThenStart()
            } else {
                status.text = "未取得「精確位置」權限，無法使用地理圍欄（請在權限畫面選「精確」）"
            }
        }
        // code == 2（背景定位）要使用者到系統設定改成「一律允許」後，再手動按一次啟動
    }
}
