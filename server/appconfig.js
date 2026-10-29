// 集中管理「手機端」的定位參數：App 啟動或重新布防時可以來取，不必每改一個數字就重新編譯。
// 注意：定位頻率仍由手機執行，這裡只是存放數值；App 不應該每幾秒就來問一次。
// key 與手機設定頁「進階參數」的內部名稱一致（見 App 的 DoorConfig.kt）。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

export const APP_PARAM_SPECS = {
  cfg_fence_radius: { def: 55, min: 20, max: 2000, label: '地理圍欄半徑（公尺）' },
  cfg_arrive_radius: { def: 15, min: 3, max: 200, label: '抵達距離（公尺）' },
  cfg_max_accuracy: { def: 15, min: 3, max: 100, label: '最大允許定位誤差（公尺）' },
  cfg_confirm_count: { def: 2, min: 1, max: 10, label: '連續符合筆數' },
  cfg_near_interval: { def: 2.5, min: 1, max: 30, label: '靠近時定位間隔（秒）' },
  cfg_far_interval: { def: 15, min: 5, max: 120, label: '距離較遠時定位間隔（秒）' },
  cfg_near_range: { def: 80, min: 10, max: 1000, label: '高頻定位範圍（公尺）' },
  cfg_timeout_min: { def: 5, min: 1, max: 30, label: '定位確認逾時（分鐘）' },
}

export class AppConfig {
  constructor(file) {
    this.file = file
    this.overrides = this._load()
  }

  _load() {
    try {
      const j = JSON.parse(fs.readFileSync(this.file, 'utf8'))
      const out = {}
      for (const [k, v] of Object.entries(j?.params ?? {})) {
        if (APP_PARAM_SPECS[k] && Number.isFinite(v)) out[k] = v
      }
      return out
    } catch {
      return {}
    }
  }

  get params() {
    const out = {}
    for (const [k, s] of Object.entries(APP_PARAM_SPECS)) out[k] = this.overrides[k] ?? s.def
    return out
  }

  /** 內容有變就會換版本字串，App 可以用它判斷要不要更新 */
  get version() {
    return crypto.createHash('sha1').update(JSON.stringify(this.params)).digest('hex').slice(0, 8)
  }

  /**
   * patch：{ key: 數字 } 設定；{ key: null } 還原成預設。
   * 全部驗證通過才會寫入，有任何一項錯就整批不套用。
   */
  update(patch) {
    const errors = []
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return { ok: false, errors: ['需要一個 JSON 物件，例如 {"cfg_fence_radius":100}'] }
    const next = { ...this.overrides }
    for (const [k, v] of Object.entries(patch)) {
      const s = APP_PARAM_SPECS[k]
      if (!s) { errors.push(`未知參數 ${k}`); continue }
      if (v === null) { delete next[k]; continue }
      if (typeof v !== 'number' || !Number.isFinite(v) || v < s.min || v > s.max) {
        errors.push(`${k}（${s.label}）要是 ${s.min}～${s.max} 之間的數字`)
        continue
      }
      next[k] = v
    }
    const merged = { ...Object.fromEntries(Object.entries(APP_PARAM_SPECS).map(([k, s]) => [k, s.def])), ...next }
    if (merged.cfg_arrive_radius > merged.cfg_fence_radius) errors.push('cfg_arrive_radius（抵達距離）不能大於 cfg_fence_radius（圍欄半徑）')
    if (errors.length) return { ok: false, errors }
    this.overrides = next
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    const tmp = this.file + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify({ params: next }, null, 2), { mode: 0o600 })
    fs.renameSync(tmp, this.file)
    return { ok: true }
  }
}
