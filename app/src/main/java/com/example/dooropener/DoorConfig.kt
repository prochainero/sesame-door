package com.example.dooropener

import android.content.SharedPreferences

/**
 * 可在設定頁調整的參數。全部存在 SharedPreferences（字串），留空或無效就用預設值，
 * 所以不用再為了改幾個數字重新編譯整個 App。
 *
 * 讀取一律即時從 prefs 取值：定位確認進行中改設定，下一筆定位就會套用；
 * 只有「地理圍欄半徑」要按「儲存並啟動」重新註冊圍欄後才會生效（系統端的圍欄是註冊時決定的）。
 */
object DoorConfig {

    class Spec(
        val key: String,
        val label: String,
        val unit: String,
        val default: Double,
        val min: Double,
        val max: Double,
        val decimal: Boolean = false,
        val note: String = ""
    ) {
        fun format(v: Double): String =
            if (decimal && v != Math.floor(v)) v.toString() else v.toLong().toString()

        val defaultText: String get() = format(default)
        val rangeText: String get() = "${format(min)}～${format(max)}"
    }

    val FENCE_RADIUS = Spec(
        "cfg_fence_radius", "地理圍欄半徑", "公尺", 55.0, 20.0, 2000.0,
        note = "離開此範圍後布防，回到範圍內才開始精確定位。需按「儲存並啟動」重新註冊才生效。Google 建議圍欄至少約 100 公尺才穩定，太小可能延遲或漏觸發。"
    )
    val ARRIVE_RADIUS = Spec(
        "cfg_arrive_radius", "抵達距離（幾公尺內算到家）", "公尺", 15.0, 3.0, 200.0,
        note = "定位點離門口小於等於這個距離，才算符合開門條件。不能大於圍欄半徑。"
    )
    val MAX_ACCURACY = Spec(
        "cfg_max_accuracy", "最大允許定位誤差", "公尺", 15.0, 3.0, 100.0,
        note = "誤差比這個大的定位點不採用。調大比較容易開門，但也比較容易被飄移騙到。"
    )
    val CONFIRM_COUNT = Spec(
        "cfg_confirm_count", "連續幾筆定位符合才開門", "筆", 2.0, 1.0, 10.0,
        note = "必須連續符合（距離＋誤差）這麼多筆才開門；中間只要有一筆不符合就重新計算。1 = 單筆就開。"
    )
    val NEAR_INTERVAL = Spec(
        "cfg_near_interval", "靠近時定位間隔（高精度）", "秒", 2.5, 1.0, 30.0, decimal = true,
        note = "進入下面的「高頻範圍」後，使用高精度定位，每隔幾秒更新一次。越小越快、越耗電。"
    )
    val FAR_INTERVAL = Spec(
        "cfg_far_interval", "距離較遠時定位間隔（省電）", "秒", 15.0, 5.0, 120.0, decimal = true,
        note = "還沒靠近時用省電模式，每隔幾秒更新一次。"
    )
    val NEAR_RANGE = Spec(
        "cfg_near_range", "高頻定位範圍（距門口幾公尺內）", "公尺", 80.0, 10.0, 1000.0,
        note = "距離門口在此範圍內改用高精度＋短間隔。建議比圍欄半徑略大一點。"
    )
    val TIMEOUT_MIN = Spec(
        "cfg_timeout_min", "定位確認逾時", "分鐘", 5.0, 1.0, 30.0, decimal = true,
        note = "進入圍欄後最久持續定位幾分鐘；逾時沒開成門就停止，等下次進入範圍。"
    )

    val ALL = listOf(
        FENCE_RADIUS, ARRIVE_RADIUS, MAX_ACCURACY, CONFIRM_COUNT,
        NEAR_INTERVAL, FAR_INTERVAL, NEAR_RANGE, TIMEOUT_MIN
    )

    /** 解析使用者輸入：空白＝null（用預設）；不是數字或超出範圍＝丟 IllegalArgumentException。 */
    fun parseInput(spec: Spec, text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull() ?: throw IllegalArgumentException("「${spec.label}」要填數字")
        if (v < spec.min || v > spec.max) {
            throw IllegalArgumentException("「${spec.label}」要在 ${spec.rangeText} ${spec.unit}之間")
        }
        return v
    }

    private fun read(p: SharedPreferences, spec: Spec, fallback: Double = spec.default): Double {
        val v = p.getString(spec.key, null)?.trim()?.toDoubleOrNull() ?: return fallback
        return v.coerceIn(spec.min, spec.max)
    }

    fun fenceRadius(p: SharedPreferences): Float = read(p, FENCE_RADIUS).toFloat()

    fun arriveRadius(p: SharedPreferences): Float = read(p, ARRIVE_RADIUS).toFloat()

    fun maxAccuracy(p: SharedPreferences): Float = read(p, MAX_ACCURACY).toFloat()
    fun confirmCount(p: SharedPreferences): Int = read(p, CONFIRM_COUNT).toInt().coerceAtLeast(1)
    fun nearIntervalMs(p: SharedPreferences): Long = (read(p, NEAR_INTERVAL) * 1000).toLong()
    fun farIntervalMs(p: SharedPreferences): Long = (read(p, FAR_INTERVAL) * 1000).toLong()
    fun nearRange(p: SharedPreferences): Float = read(p, NEAR_RANGE).toFloat()
    fun timeoutMs(p: SharedPreferences): Long = (read(p, TIMEOUT_MIN) * 60_000).toLong()

    /** 目前生效的數值（字串），給設定頁預填。 */
    fun currentText(p: SharedPreferences, spec: Spec): String {
        val saved = p.getString(spec.key, null)?.trim()?.toDoubleOrNull()
        return spec.format((saved ?: spec.default).coerceIn(spec.min, spec.max))
    }

    fun m(v: Float): String = "${v.toInt()} m"
}
