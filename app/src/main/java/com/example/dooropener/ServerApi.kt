package com.example.dooropener

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 與自己的伺服器（VM）溝通：手動開門、同步參數。
 * 全部是「阻塞式」呼叫，請在背景執行緒使用。
 * 「伺服器網址」欄位通常填 https://你的網域/arrive，其他路徑（/open、/config）由它推出來。
 */
object ServerApi {

    class OpenResult(val opened: Boolean, val reason: String)

    class ConfigResult(val params: Map<String, Double>, val version: String, val error: String?)
    class TelemetryResult(val ok: Boolean, val message: String)

    /** 把 https://網域/arrive 換成同一台伺服器的其他路徑；網址空白回傳 null。 */
    fun endpoint(serverUrl: String, path: String): String? {
        val u = serverUrl.trim().substringBefore('?').trimEnd('/')
        if (u.isEmpty()) return null
        val root = if (u.endsWith("/arrive")) u.removeSuffix("/arrive") else u
        return root + path
    }

    fun isConfigured(serverUrl: String, secret: String) = serverUrl.isNotBlank() && secret.isNotBlank()

    private fun friendlyError(e: Exception, url: String): String = when {
        url.startsWith("http://") -> "網址要用 https://（App 不允許明文連線）"
        else -> "連線失敗：" + (e.message ?: e.javaClass.simpleName)
    }

    /** 手動／語音／圖示開門：POST /open（不核對位置，只驗密碼） */
    fun postOpen(serverUrl: String, secret: String, profileId: String? = null, deviceId: String? = null, accessToken: String? = null): OpenResult {
        val url = endpoint(serverUrl, "/open") ?: return OpenResult(false, "還沒填伺服器網址")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Secret", secret.trim())
            accessToken?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            profileId?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Profile-Id", it.take(64)) }
            deviceId?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Device-Id", it.take(64)) }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val opened = json?.optBoolean("opened") == true
            val reason = json?.optString("reason").orEmpty().ifEmpty { if (opened) "已開門" else "HTTP $code ${text.take(60)}" }
            OpenResult(opened, reason)
        } catch (e: Exception) {
            OpenResult(false, friendlyError(e, url))
        }
    }

    /** 同步參數：GET /config，回傳 {key: 數值}（key 與設定頁「進階參數」相同） */
    fun fetchConfig(serverUrl: String, secret: String, accessToken: String? = null): ConfigResult {
        val url = endpoint(serverUrl, "/config") ?: return ConfigResult(emptyMap(), "", "還沒填伺服器網址")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("X-Secret", secret.trim())
            accessToken?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) return ConfigResult(emptyMap(), "", "密碼錯誤")
            if (code == 429) return ConfigResult(emptyMap(), "", "密碼錯誤次數過多，已被伺服器暫時鎖定")
            if (code !in 200..299) return ConfigResult(emptyMap(), "", "HTTP $code ${text.take(60)}（伺服器可能還是舊版，沒有 /config）")
            val json = JSONObject(text)
            val p = json.getJSONObject("params")
            val out = LinkedHashMap<String, Double>()
            for (k in p.keys()) out[k] = p.getDouble(k)
            ConfigResult(out, json.optString("version"), null)
        } catch (e: Exception) {
            ConfigResult(emptyMap(), "", friendlyError(e, url))
        }
    }

    /** 批次上傳事件／定位樣本。此端點只寫 Log，絕不執行開門命令。 */
    fun postTelemetryBatch(serverUrl: String, secret: String, events: org.json.JSONArray, accessToken: String? = null): TelemetryResult {
        val url = endpoint(serverUrl, "/telemetry/batch")
            ?: return TelemetryResult(false, "還沒填伺服器網址")
        return try {
            val payload = JSONObject().put("events", events).toString()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Secret", secret.trim())
            accessToken?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val ok = code in 200..299 && json?.optBoolean("ok") == true
            TelemetryResult(ok, if (ok) "accepted=${json?.optInt("accepted") ?: 0}" else "HTTP $code ${json?.optString("error") ?: text.take(100)}")
        } catch (e: Exception) {
            TelemetryResult(false, friendlyError(e, url))
        }
    }

}
