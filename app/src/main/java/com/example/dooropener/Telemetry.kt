package com.example.dooropener

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 個人化歷史紀錄：每次安裝固定 deviceId、使用者可設定 profileId、每趟回家以 sessionId 串接。
 * 先寫入 App 私有檔案，批次上傳；網路失敗時保留佇列，不阻塞定位／開門主流程。
 */
object Telemetry {
    private const val KEY_DEVICE_ID = "telemetry_device_id"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_SESSION_ID = "telemetry_session_id"
    private const val KEY_LAST_UPLOAD = "telemetry_last_upload"
    private const val KEY_LAST_ERROR = "telemetry_last_error"
    private const val BATCH_SIZE = 50
    private const val FLUSH_THRESHOLD = 12
    private const val MAX_PENDING_LINES = 5000

    private val fileLock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    @Volatile private var retryAfterMs = 0L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(DoorService.PREFS, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        val current = p.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        if (current != null) return current
        val generated = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, generated).commit()
        return p.getString(KEY_DEVICE_ID, generated) ?: generated
    }

    /** 未設定個人代號時提供穩定預設值；三支手機建議分別改成 A、B、C 或各自代號。 */
    fun profileId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_PROFILE_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val generated = "person-${deviceId(context).take(6)}"
        p.edit().putString(KEY_PROFILE_ID, generated).commit()
        return generated
    }

    fun setProfileId(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PROFILE_ID, value.trim()).apply()
    }

    fun sessionId(context: Context): String = prefs(context).getString(KEY_SESSION_ID, "").orEmpty()

    fun beginTrip(context: Context): String {
        val id = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_SESSION_ID, id).apply()
        return id
    }

    fun ensureTrip(context: Context): String {
        val existing = sessionId(context)
        return if (existing.isNotBlank()) existing else beginTrip(context)
    }

    fun finishTrip(context: Context) {
        prefs(context).edit().remove(KEY_SESSION_ID).apply()
    }

    fun pendingCount(context: Context): Int = synchronized(fileLock) { readLines(context).size }
    fun lastUploadStatus(context: Context): String {
        val p = prefs(context)
        val success = p.getLong(KEY_LAST_UPLOAD, 0L)
        val error = p.getString(KEY_LAST_ERROR, "").orEmpty()
        return when {
            success > 0L && error.isBlank() -> "上次成功 ${android.text.format.DateFormat.format("MM-dd HH:mm", success)}"
            error.isNotBlank() -> "上傳待重試：$error"
            else -> "尚未上傳"
        }
    }

    fun recordEvent(
        context: Context,
        type: String,
        message: String = "",
        fields: JSONObject = JSONObject(),
        flushNow: Boolean = false,
    ) {
        val app = context.applicationContext
        val p = prefs(app)
        val event = JSONObject().apply {
            put("eventId", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
            put("profileId", profileId(app))
            put("deviceId", deviceId(app))
            val sid = sessionId(app)
            if (sid.isNotBlank()) put("sessionId", sid)
            put("type", type.take(48))
            put("message", message.take(200))
            put("fields", fields)
        }
        try {
            synchronized(fileLock) {
                val f = pendingFile(app)
                f.parentFile?.mkdirs()
                f.appendText(event.toString() + "\n", Charsets.UTF_8)
                pruneIfNeeded(f)
            }
            if (flushNow || pendingCount(app) >= FLUSH_THRESHOLD) flushAsync(app, force = flushNow)
        } catch (_: Exception) {
            // 診斷紀錄失敗不能影響定位服務或開門流程。
        }
    }

    fun recordLocationFix(context: Context, fields: JSONObject, flushNow: Boolean = false) {
        recordEvent(context, "location_fix", "定位樣本", fields, flushNow)
    }

    fun flushAsync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val p = prefs(app)
        val url = p.getString("url", "").orEmpty().trim()
        val secret = p.getString("secret", "").orEmpty().trim()
        if (!url.startsWith("https://") || secret.isBlank()) return
        if (!force && System.currentTimeMillis() < retryAfterMs) return
        if (!force && pendingCount(app) < FLUSH_THRESHOLD) return
        if (!uploading.compareAndSet(false, true)) return
        executor.execute {
            var shouldResume = true
            try {
                var loops = 0
                while (loops++ < 5) {
                    val selected = synchronized(fileLock) { readLines(app).take(BATCH_SIZE) }
                    if (selected.isEmpty()) break
                    val batch = JSONArray()
                    val ids = HashSet<String>()
                    selected.forEach { line ->
                        val obj = runCatching { JSONObject(line) }.getOrNull()
                        if (obj != null) {
                            batch.put(obj)
                            obj.optString("eventId").takeIf { it.isNotBlank() }?.let { ids.add(it) }
                        }
                    }
                    if (batch.length() == 0) break
                    val result = ServerApi.postTelemetryBatch(url, secret, batch, AppAuth.token(app))
                    if (!result.ok) {
                        p.edit().putString(KEY_LAST_ERROR, result.message.take(150)).apply()
                        retryAfterMs = System.currentTimeMillis() + 60_000L
                        shouldResume = false
                        break // Keep pending rows; do not spin on a failed network request.
                    }
                    synchronized(fileLock) {
                        val remain = readLines(app).filter { line ->
                            val id = runCatching { JSONObject(line).optString("eventId") }.getOrDefault("")
                            id !in ids
                        }
                        rewriteLines(app, remain)
                    }
                    retryAfterMs = 0L
                    p.edit().putLong(KEY_LAST_UPLOAD, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
                    if (!force && pendingCount(app) < FLUSH_THRESHOLD) break
                }
            } catch (e: Exception) {
                p.edit().putString(KEY_LAST_ERROR, (e.message ?: e.javaClass.simpleName).take(150)).apply()
                retryAfterMs = System.currentTimeMillis() + 60_000L
                shouldResume = false
            } finally {
                uploading.set(false)
                // Continue only after a successful batch; failures wait for the next event or app resume.
                if (shouldResume && pendingCount(app) >= FLUSH_THRESHOLD) flushAsync(app, force = false)
            }
        }
    }

    private fun pendingFile(context: Context) = File(context.filesDir, "telemetry/pending.jsonl")

    private fun readLines(context: Context): List<String> {
        val f = pendingFile(context)
        if (!f.exists()) return emptyList()
        return try { f.readLines(Charsets.UTF_8).filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
    }

    private fun rewriteLines(context: Context, lines: List<String>) {
        val f = pendingFile(context)
        f.parentFile?.mkdirs()
        val temp = File(f.parentFile, f.name + ".tmp")
        temp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        if (!temp.renameTo(f)) {
            f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun pruneIfNeeded(file: File) {
        if (file.length() < 2_000_000L) return
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.size <= MAX_PENDING_LINES) return
        val kept = lines.takeLast(MAX_PENDING_LINES)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        if (!temp.renameTo(file)) { file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8); temp.delete() }
    }
}
