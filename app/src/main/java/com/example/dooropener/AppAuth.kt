package com.example.dooropener

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Google OAuth bridge: browser -> sesame://auth callback -> one-time code exchange. */
object AppAuth {
    private const val AUTH_BASE = "https://nn0117.duckdns.org/door-sesame"
    private const val KEY_TOKEN = "google_access_token"
    private const val KEY_EXP = "google_access_token_exp"
    private const val KEY_EMAIL = "google_access_email"
    private const val KEY_STATE = "google_oauth_state"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)

    fun token(c: Context): String = if (hasValidToken(c)) prefs(c).getString(KEY_TOKEN, "").orEmpty() else ""
    fun email(c: Context): String = prefs(c).getString(KEY_EMAIL, "").orEmpty()
    fun hasValidToken(c: Context): Boolean = tokenExpiry(c) > System.currentTimeMillis() + 30_000L && prefs(c).getString(KEY_TOKEN, "").orEmpty().isNotBlank()
    private fun tokenExpiry(c: Context) = prefs(c).getLong(KEY_EXP, 0L)
    fun logout(c: Context) { prefs(c).edit().remove(KEY_TOKEN).remove(KEY_EXP).remove(KEY_EMAIL).apply() }

    fun startLogin(activity: Activity) {
        val state = UUID.randomUUID().toString().replace("-", "")
        prefs(activity).edit().putString(KEY_STATE, state).apply()
        val url = "$AUTH_BASE/app/login?state=" + URLEncoder.encode(state, "UTF-8")
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Returns null when this intent is not our callback; otherwise runs exchange on a worker thread. */
    fun handleCallback(activity: Activity, intent: Intent?, done: (String) -> Unit): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "sesame" || uri.host != "auth") return false
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val error = uri.getQueryParameter("error").orEmpty()
        val expected = prefs(activity).getString(KEY_STATE, "").orEmpty()
        if (state.isBlank() || expected.isBlank() || state != expected) {
            done("登入回呼驗證失敗，請重新登入")
            return true
        }
        if (error.isNotBlank()) {
            prefs(activity).edit().remove(KEY_STATE).apply()
            done(if (error == "not_allowlisted") "此 Google 帳號不在白名單內，無法使用芝麻開門。" else "Google 登入未完成（$error），請重試。")
            return true
        }
        if (code.isBlank()) {
            done("登入回呼缺少授權碼，請重新登入")
            return true
        }
        prefs(activity).edit().remove(KEY_STATE).apply()
        Thread {
            val message = try {
                val conn = URL("$AUTH_BASE/api/app-token").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw Exception("伺服器拒絕登入（HTTP $status）：${JSONObject(body).optString("error", "請重新登入")}")
                val j = JSONObject(body)
                val access = j.optString("access_token")
                val expiry = j.optLong("expires_at", 0L) * 1000L
                val mail = j.optString("email")
                if (access.isBlank() || expiry <= System.currentTimeMillis()) throw Exception("伺服器回傳的登入憑證無效")
                prefs(activity).edit().putString(KEY_TOKEN, access).putLong(KEY_EXP, expiry).putString(KEY_EMAIL, mail).apply()
                "登入成功：$mail"
            } catch (e: Exception) { "登入失敗：${e.message ?: e.javaClass.simpleName}" }
            activity.runOnUiThread { done(message) }
        }.start()
        return true
    }
}
