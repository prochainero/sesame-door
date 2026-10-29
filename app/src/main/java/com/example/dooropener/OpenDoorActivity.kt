package com.example.dooropener

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 沒有畫面的「一鍵開門」入口：被打開就立刻送出 eWeLink 的 Intent，然後自己關掉。
 * 可放主畫面圖示，或用語音說「開啟大門」來叫它；抵達時也由背景服務／全螢幕通知叫起。
 * 內建冷卻：成功送出後 COOLDOWN_MS 內再次被叫起會直接忽略，避免語音誤辨識連續觸發。
 *
 * 鎖屏時：eWeLink 的捷徑畫面會被蓋在鎖屏後面、不會真的執行，
 * 所以先請系統解除鎖屏（沒設安全鎖會直接解除；有設 PIN／指紋／臉部則會要求解鎖一次），解除後才送出 Intent。
 */
class OpenDoorActivity : Activity() {

    companion object {
        private const val COOLDOWN_MS = 10_000L
        private const val KEY_LAST = "last_open_ms"
        private const val UNLOCK_WAIT_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 已經啟動了，就不需要抵達時留下的全螢幕／點擊通知
        getSystemService(NotificationManager::class.java).cancel(DoorEvents.NOTIF_ID_OPEN)
        val prefs = getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        // 「全部停用」：圖示／語音／捷徑都不開門，改開設定頁，讓使用者能解除
        if (prefs.getBoolean("all_disabled", false)) {
            Toast.makeText(this, "芝麻開門目前為「全部停用」，請在設定頁按「解除全部停用」", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (!AppAuth.hasValidToken(this)) {
            DoorEvents.record(this, "未登入或登入已過期，已阻止開門", notify = true, eventType = "auth_required")
            Toast.makeText(this, "請先開啟「芝麻開門設定」完成 Google 白名單登入，再使用開門捷徑。", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val uri = prefs.getString("intent", "")?.trim().orEmpty()

        val serverUrl = prefs.getString("url", "")?.trim().orEmpty()
        val serverSecret = prefs.getString("secret", "")?.trim().orEmpty()
        val serverMode = uri.isEmpty() && ServerApi.isConfigured(serverUrl, serverSecret)

        if (uri.isEmpty() && !serverMode) {
            Toast.makeText(
                this,
                "尚未設定開門方式：請到「開門設定」貼上 Intent，或填好伺服器網址與密碼，再按「儲存並啟動」",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        DoorEvents.record(this, "開門入口已啟動（OpenDoorActivity 有被叫起來）", eventType = "open_activity_started")
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        if (now - last < COOLDOWN_MS) {
            DoorEvents.record(this, "10 秒冷卻中，略過這次開門", eventType = "manual_open_cooldown")
            finish()
            return
        }

        if (serverMode) {
            // 雲端開門不需要解鎖手機、也不需要叫起 eWeLink：直接請伺服器開，等結果再關閉自己
            prefs.edit().putLong(KEY_LAST, now).apply()
            val app = applicationContext
            Thread {
                val r = ServerApi.postOpen(serverUrl, serverSecret, Telemetry.profileId(app), Telemetry.deviceId(app), AppAuth.token(app))
                DoorEvents.record(app, if (r.opened) "伺服器已開門" else "伺服器沒有開門：${r.reason}", notify = !r.opened, eventType = "manual_open_result", fields = org.json.JSONObject().put("opened", r.opened).put("reason", r.reason.take(100)))
                if (!r.opened) prefs.edit().putLong(KEY_LAST, 0L).apply() // 失敗就不要卡 10 秒冷卻，方便立刻重試
                runOnUiThread {
                    Toast.makeText(this, if (r.opened) "已開門" else "沒有開門：${r.reason}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }.start()
            return
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            val secure = if (km.isDeviceSecure) "有安全鎖（PIN／圖形／指紋／臉部）" else "沒有安全鎖"
            DoorEvents.record(this, "手機鎖屏中（$secure），先請系統解除鎖屏再開門", eventType = "device_locked")
            handler.postDelayed({
                DoorEvents.record(this, "等待解鎖逾時，已放棄這次開門", eventType = "unlock_timeout")
                finish()
            }, UNLOCK_WAIT_MS)
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    handler.removeCallbacksAndMessages(null)
                    DoorEvents.record(this@OpenDoorActivity, "鎖屏已解除，送出開門 Intent", eventType = "device_unlocked")
                    launchTarget(uri)
                }

                override fun onDismissCancelled() {
                    handler.removeCallbacksAndMessages(null)
                    DoorEvents.record(this@OpenDoorActivity, "解鎖被取消，沒有開門", eventType = "unlock_cancelled")
                    finish()
                }

                override fun onDismissError() {
                    handler.removeCallbacksAndMessages(null)
                    DoorEvents.record(this@OpenDoorActivity, "系統無法解除鎖屏，沒有開門", eventType = "unlock_failed")
                    finish()
                }
            })
            return
        }

        launchTarget(uri)
    }

    private fun launchTarget(uri: String) {
        val prefs = getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)
        try {
            val target = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(target)
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            DoorEvents.record(this, "已從開門入口送出 eWeLink Intent（沒丟例外；eWeLink 有沒有動作要看手機）", eventType = "intent_dispatch_succeeded")
        } catch (e: Exception) {
            DoorEvents.record(this, "送出 eWeLink Intent 失敗：${e.javaClass.simpleName}: ${e.message ?: "未知"}", eventType = "intent_dispatch_failed", fields = org.json.JSONObject().put("errorType", e.javaClass.simpleName))
            Toast.makeText(
                this,
                "啟動失敗：${e.javaClass.simpleName}: ${e.message ?: "未知錯誤"}",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
