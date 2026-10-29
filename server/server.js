// 芝麻開門伺服器
//  - 手機 App 確認抵達後 POST /arrive（帶 X-Secret、位置）→ 這裡再核對一次 → 呼叫 eWeLink 雲端 API 開門
//  - 手動開門（App 圖示／語音）：POST /open
//  - 一次性授權：開 /ewelink/login?key=密碼，登入 eWeLink 後回到 /ewelink/callback，token 存檔並自動更新
// 只依賴一個套件：ewelink-api-next（簽章與 API 格式交給它，不自己手刻）
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { pathToFileURL } from 'node:url'
import eWeLink from 'ewelink-api-next'
import { AppConfig } from './appconfig.js'
import { ArrivalLog, buildStats, formatStatsText } from './stats.js'

// ───────────────────────── 設定 ─────────────────────────
export function loadConfig(env = process.env) {
  const need = (k) => {
    if (!env[k] || !String(env[k]).trim()) throw new Error(`缺少環境變數 ${k}（請檢查 .env）`)
    return String(env[k]).trim()
  }
  const num = (k, d) => {
    const v = env[k]
    if (v === undefined || String(v).trim() === '') return d
    const n = Number(v)
    if (!Number.isFinite(n)) throw new Error(`${k} 必須是數字`)
    return n
  }
  const outletRaw = (env.DOOR_OUTLET ?? '').toString().trim()
  return {
    host: env.HOST || '127.0.0.1',
    port: num('PORT', 3000),
    appId: need('EWELINK_APP_ID'),
    appSecret: need('EWELINK_APP_SECRET'),
    region: (env.EWELINK_REGION || 'as').trim(),
    redirectUrl: need('REDIRECT_URL'),
    sharedSecret: need('SHARED_SECRET'),
    appTokenSecret: (env.APP_TOKEN_SECRET || '').trim(),
    requireGoogleAuth: String(env.REQUIRE_GOOGLE_AUTH || 'false').toLowerCase() === 'true',
    deviceId: (env.DOOR_DEVICE_ID || '').trim(),          // 還沒查到可先留空，用 /ewelink/devices 查
    outlet: outletRaw === '' ? null : Number(outletRaw),    // 多路設備的通道號（從 0 開始）；單路留空
    doorLat: env.DOOR_LAT ? Number(env.DOOR_LAT) : null,
    doorLon: env.DOOR_LON ? Number(env.DOOR_LON) : null,
    arriveRadiusM: num('ARRIVE_RADIUS_M', 40),              // 伺服器端的保險：比手機端寬鬆即可
    maxAccuracyM: num('MAX_ACCURACY_M', 30),
    cooldownSec: num('COOLDOWN_SEC', 60),
    maxFixAgeSec: num('MAX_FIX_AGE_SEC', 20),               // 定位資料超過幾秒就視為過舊（App 有送 fixTime 才檢查）
    maxOpensPerHour: num('MAX_OPENS_PER_HOUR', 6),          // 每小時最多實際開門幾次
    lockoutFails: num('LOCKOUT_FAILS', 5),                  // 同一個 IP 密碼錯幾次就鎖定
    lockoutMin: num('LOCKOUT_MIN', 15),                     // 鎖定幾分鐘
    logFile: env.LOG_FILE || './data/arrivals.jsonl',       // 開門／抵達決策紀錄
    telemetryFile: env.TELEMETRY_FILE || './data/telemetry.jsonl',
    timeZone: env.APP_TIME_ZONE || 'Asia/Taipei',
    configFile: env.CONFIG_FILE || './data/config.json',    // 給 App 同步的參數（給 /config 用）
    tokenFile: env.TOKEN_FILE || './data/tokens.json',
    refreshAfterDays: num('REFRESH_AFTER_DAYS', 10),       // access token 約 30 天有效，提早更新
  }
}

// ───────────────────────── 小工具 ─────────────────────────
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const finiteOrUndef = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

export function haversineM(lat1, lon1, lat2, lon2) {
  const R = 6371000
  const rad = (d) => (d * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLon = rad(lon2 - lon1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest()
  const hb = crypto.createHash('sha256').update(String(b)).digest()
  return crypto.timingSafeEqual(ha, hb)
}

function verifyAppJwt(token, secret, nowMs = Date.now()) {
  if (!secret || !token) return null
  const parts = String(token).split('.')
  if (parts.length !== 3) return null
  const input = `${parts[0]}.${parts[1]}`
  const expected = crypto.createHmac('sha256', secret).update(input).digest('base64url')
  if (!safeEqual(expected, parts[2])) return null
  try {
    const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'))
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
    if (header.alg !== 'HS256' || typeof payload.email !== 'string' || !payload.email.includes('@')) return null
    if (!Number.isFinite(payload.exp) || payload.exp <= Math.floor(nowMs / 1000)) return null
    if (payload.iss !== 'door-sesame-auth') return null
    return payload
  } catch { return null }
}

// ───────────────────────── token 存放 ─────────────────────────
export class TokenStore {
  constructor(file) {
    this.file = file
  }
  load() {
    try {
      return JSON.parse(fs.readFileSync(this.file, 'utf8'))
    } catch {
      return null
    }
  }
  save(obj) {
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    const tmp = this.file + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2), { mode: 0o600 })
    fs.renameSync(tmp, this.file) // 原子替換，不會寫到一半斷電變成壞檔
  }
}

// ───────────────────────── eWeLink 連線（含自動換 token、限速） ─────────────────────────
const MIN_CALL_GAP_MS = 700 // 官方限制：同一 IP 呼叫間隔需 ≥ 500ms，另外 5 分鐘內不超過 300 次

export class EwelinkSession {
  constructor({ client, store, cfg, log = console, now = () => Date.now() }) {
    this.client = client
    this.store = store
    this.cfg = cfg
    this.log = log
    this.now = now
    this.tokens = store.load()
    this.lastCall = 0
    this.chain = Promise.resolve() // 讓所有 eWeLink 呼叫排隊，避免同時打出去
    if (this.tokens) this._apply(this.tokens)
  }

  get authorized() {
    return !!(this.tokens && this.tokens.at && this.tokens.rt)
  }

  get tokenAgeDays() {
    return this.tokens?.refreshedAt ? (this.now() - this.tokens.refreshedAt) / 86400000 : null
  }

  _apply(t) {
    this.client.at = t.at
    this.client.rt = t.rt
    this.client.region = t.region
    this.client.setUrl(t.region)
  }

  /** OAuth 授權完成後呼叫：存下 token 並套用 */
  adopt({ at, rt, region, apikey }) {
    this.tokens = { at, rt, region, apikey, refreshedAt: this.now() }
    this.store.save(this.tokens)
    this._apply(this.tokens)
  }

  async refresh() {
    if (!this.tokens?.rt) throw new Error('尚未授權（沒有 refresh token）')
    const res = await this.client.user.refreshToken({ rt: this.tokens.rt })
    if (res?.error !== 0 || !res?.data?.at || !res?.data?.rt) {
      throw new Error(`refresh token 失敗：error=${res?.error} msg=${res?.msg}（若已超過約兩個月沒用，需重新授權）`)
    }
    this.tokens = { ...this.tokens, at: res.data.at, rt: res.data.rt, refreshedAt: this.now() }
    this.store.save(this.tokens)
    this._apply(this.tokens)
    this.log.info?.('已更新 eWeLink token')
  }

  async ensureFresh() {
    if (!this.authorized) throw new Error('尚未授權，請先開 /ewelink/login')
    const age = this.tokenAgeDays
    if (age === null || age > this.cfg.refreshAfterDays) await this.refresh()
  }

  /** 排隊＋限速＋token 過期時自動換一次再重試一次 */
  call(fn) {
    const run = async () => {
      await this.ensureFresh()
      const gap = this.now() - this.lastCall
      if (gap < MIN_CALL_GAP_MS) await sleep(MIN_CALL_GAP_MS - gap)
      this.lastCall = this.now()
      let res = await fn()
      if (res?.error !== 0 && looksLikeAuthError(res)) {
        this.log.warn?.(`eWeLink 回應像是 token 問題（error=${res?.error} msg=${res?.msg}），換 token 後重試一次`)
        await this.refresh()
        await sleep(MIN_CALL_GAP_MS)
        this.lastCall = this.now()
        res = await fn()
      }
      return res
    }
    const p = this.chain.then(run, run)
    this.chain = p.catch(() => {})
    return p
  }
}

function looksLikeAuthError(res) {
  if (res?.status === 401) return true
  if ([401, 402].includes(res?.error)) return true
  return /token|auth|expire|unauthor/i.test(String(res?.msg ?? ''))
}

// ───────────────────────── 開門 ─────────────────────────
export function doorParams(cfg) {
  // 單路：{switch:'on'}；多路：指定通道。該路要先在 eWeLink App 設成「點動」，送 on 才會只導通一下。
  return cfg.outlet === null ? { switch: 'on' } : { switches: [{ switch: 'on', outlet: cfg.outlet }] }
}

export class Door {
  constructor({ session, client, cfg, log = console, now = () => Date.now() }) {
    this.session = session
    this.client = client
    this.cfg = cfg
    this.log = log
    this.now = now
    this.lastOpen = 0
    this.openTimes = []
    this.busy = false
  }

  opensLastHour() {
    this.openTimes = this.openTimes.filter((t) => this.now() - t < 3600_000)
    return this.openTimes.length
  }

  cooldownLeftSec() {
    return Math.max(0, Math.ceil((this.lastOpen + this.cfg.cooldownSec * 1000 - this.now()) / 1000))
  }

  async open(why) {
    if (!this.cfg.deviceId) return { opened: false, code: 'no_device', reason: '伺服器還沒設定 DOOR_DEVICE_ID（先用 /ewelink/devices 查）' }
    if (this.busy) return { opened: false, code: 'busy', reason: '上一次開門還在處理中' }
    const left = this.cooldownLeftSec()
    if (left > 0) return { opened: false, code: 'cooldown', reason: `冷卻中，${left} 秒後才能再開` }
    if (this.opensLastHour() >= this.cfg.maxOpensPerHour) {
      return { opened: false, code: 'rate_limit', reason: `一小時內已開門 ${this.opensLastHour()} 次，達到上限 ${this.cfg.maxOpensPerHour} 次` }
    }
    this.busy = true
    try {
      const res = await this.session.call(() =>
        this.client.device.setThingStatus({ type: 1, id: this.cfg.deviceId, params: doorParams(this.cfg) })
      )
      if (res?.error === 0) {
        this.lastOpen = this.now()
        this.openTimes.push(this.lastOpen)
        this.log.info?.(`開門成功（${why}）`)
        return { opened: true, code: 'ok', reason: why }
      }
      this.log.warn?.(`eWeLink 開門失敗 error=${res?.error} msg=${res?.msg}`)
      return { opened: false, code: 'ewelink_failed', reason: `eWeLink 回應失敗：error=${res?.error} ${res?.msg ?? ''}`.trim() }
    } catch (e) {
      this.log.error?.('開門例外：', e)
      return { opened: false, code: 'exception', reason: `開門時發生錯誤：${e.message}` }
    } finally {
      this.busy = false
    }
  }
}

// ───────────────────────── HTTP ─────────────────────────
export function createApp({ cfg, client, store, log = console, now = () => Date.now() }) {
  const session = new EwelinkSession({ client, store, cfg, log, now })
  const door = new Door({ session, client, cfg, log, now })
  const appConfig = new AppConfig(cfg.configFile)
  const arrivalLog = new ArrivalLog(cfg.logFile)
  const telemetryLog = new ArrivalLog(cfg.telemetryFile)
  const seenTelemetryIds = new Set(telemetryLog.tail(10000).map((r) => r.eventId).filter(Boolean))
  const pendingStates = new Map() // /ewelink/login 發出的 state → 到期時間
  let loginWindowUntil = 0
  const fails = new Map() // ip → { count, lastAt, lockedUntil }

  const json = (res, code, obj) => {
    const body = JSON.stringify(obj)
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(body)
  }
  const html = (res, code, body) => {
    res.writeHead(code, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="font-family:sans-serif;padding:24px;line-height:1.6">${body}</body>`)
  }
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

  // 在 Caddy 後面時，真正的來源 IP 在 X-Forwarded-For；只有「直接連進來的是本機（Caddy）」才信這個標頭，避免被偽造
  function clientIp(req) {
    const remote = req.socket.remoteAddress || ''
    const fromLocalProxy = remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1'
    const xff = req.headers['x-forwarded-for']
    return fromLocalProxy && xff ? String(xff).split(',')[0].trim() : remote
  }

  const safeLabel = (value, fallback = 'unknown', max = 64) => {
    if (typeof value !== 'string') return fallback
    const v = value.trim().replace(/[\u0000-\u001f\u007f]/g, '').slice(0, max)
    return v || fallback
  }
  const safeTelemetryFields = (value) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
    const allowed = new Set(['lat','lon','accuracyM','distanceM','speedMps','bearingDeg','fixAgeMs','intervalMs','priority','streak','requiredStreak','fixCount','sessionElapsedS','mock','fenceRadiusM','arriveRadiusM','maxAccuracyM','confirmCount','nearIntervalMs','farIntervalMs','nearRangeM','timeoutMin','movementEstimate','etaMinutes','predictionConfidence','httpStatus','reason','responseMs','configVersion','activityType','activityConfidence','sinceEnterS','fixes','source','serverDecision'])
    const out = {}
    for (const [k, v] of Object.entries(value).slice(0, 40)) {
      if (!allowed.has(k)) continue
      if (typeof v === 'number' && Number.isFinite(v)) out[k] = v
      else if (typeof v === 'boolean' || v === null) out[k] = v
      else if (typeof v === 'string') out[k] = v.slice(0, 120)
    }
    return Object.keys(out).length ? out : undefined
  }

  // 依 IP 計算密碼錯誤次數：某個 IP 亂猜只會鎖那個 IP，不會把你自己的手機也鎖在外面
  function authed(req, url, requireGoogle = false) {
    const ip = clientIp(req)
    const t = now()
    const bearer = String(req.headers.authorization || '').match(/^Bearer\s+(.+)$/i)?.[1] || ''
    const claims = verifyAppJwt(bearer, cfg.appTokenSecret, t)
    if (claims) { req.appUser = claims; fails.delete(ip); return 'ok' }
    const f = fails.get(ip)
    if (f && f.lockedUntil > t) return 'locked'
    if (cfg.requireGoogleAuth && requireGoogle) {
      arrivalLog.append({ ts: t, source: 'auth', decision: 'rejected', reason: 'google_auth_required', ip })
      return 'bad'
    }
    const given = req.headers['x-secret'] || url.searchParams.get('key') || ''
    if (given && safeEqual(given, cfg.sharedSecret)) {
      fails.delete(ip)
      return 'ok'
    }
    const lockMs = cfg.lockoutMin * 60_000
    const cur = f && t - f.lastAt < lockMs ? f : { count: 0, lastAt: t, lockedUntil: 0 }
    cur.count += 1
    cur.lastAt = t
    if (cur.count >= cfg.lockoutFails) {
      cur.lockedUntil = t + lockMs
      cur.count = 0
      log.warn?.(`來自 ${ip} 的密碼錯誤達 ${cfg.lockoutFails} 次，鎖定 ${cfg.lockoutMin} 分鐘`)
    }
    fails.set(ip, cur)
    if (fails.size > 1000) for (const [k, v] of fails) if (v.lockedUntil < t && t - v.lastAt > lockMs) fails.delete(k)
    arrivalLog.append({ ts: t, source: 'auth', decision: 'rejected', reason: 'bad_secret', ip })
    return 'bad'
  }

  function readBody(req, limit = 4096) {
    return new Promise((resolve, reject) => {
      let size = 0
      const chunks = []
      req.on('data', (c) => {
        size += c.length
        if (size > limit) {
          reject(new Error('body too large'))
          req.destroy()
        } else chunks.push(c)
      })
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
      req.on('error', reject)
    })
  }

  const handler = async (req, res) => {
    const url = new URL(req.url, 'http://localhost')
    const route = `${req.method} ${url.pathname}`
    try {
      if (route === 'GET /health') {
        return json(res, 200, { ok: true, authorized: session.authorized, tokenAgeDays: session.tokenAgeDays, deviceConfigured: !!cfg.deviceId })
      }

      // ── 授權（只要做一次）──
      if (route === 'GET /ewelink/login') {
        const a = authed(req, url)
        if (a !== 'ok') return html(res, a === 'locked' ? 429 : 401, '未授權。請用 <code>/ewelink/login?key=你的 SHARED_SECRET</code> 開啟。')
        const state = crypto.randomBytes(12).toString('hex')
        pendingStates.set(state, now() + 10 * 60_000)
        loginWindowUntil = now() + 10 * 60_000
        const loginUrl = client.oauth.createLoginUrl({ redirectUrl: cfg.redirectUrl, state })
        res.writeHead(302, { Location: loginUrl })
        return res.end()
      }

      if (route === 'GET /ewelink/callback') {
        if (now() > loginWindowUntil) return html(res, 400, '授權視窗已過期（或不是從 /ewelink/login 開始的）。請重新從 <code>/ewelink/login</code> 開始。')
        const code = url.searchParams.get('code')
        const region = url.searchParams.get('region') || cfg.region
        const state = url.searchParams.get('state')
        if (!code) return html(res, 400, '缺少授權碼 code。')
        // eWeLink 若有帶回 state 就必須是我們發出的；沒帶回則只靠 10 分鐘的授權視窗把關
        if (state && !(pendingStates.get(state) > now())) return html(res, 400, 'state 不符，已拒絕。請重新從 <code>/ewelink/login</code> 開始。')
        const r = await client.oauth.getToken({ region, redirectUrl: cfg.redirectUrl, code })
        if (r?.error !== 0 || !r?.data?.accessToken || !r?.data?.refreshToken) {
          log.warn?.(`取得 token 失敗 error=${r?.error} msg=${r?.msg}`)
          return html(res, 400, `取得 token 失敗：error=${esc(r?.error)} ${esc(r?.msg ?? '')}<br>授權碼只能用一次，請重新從 <code>/ewelink/login</code> 開始。`)
        }
        session.adopt({ at: r.data.accessToken, rt: r.data.refreshToken, region: r.data.region || region, apikey: r.data.user?.apikey })
        pendingStates.clear()
        loginWindowUntil = 0
        log.info?.(`授權成功，region=${session.tokens.region}`)
        return html(res, 200, `✅ 授權成功（region：${esc(session.tokens.region)}）。<br>下一步：開 <code>/ewelink/devices?key=…</code> 找出你的設備 ID，填進 .env 的 DOOR_DEVICE_ID。`)
      }

      // ── 查設備 ID ──
      if (route === 'GET /ewelink/devices') {
        const a = authed(req, url)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        const r = await session.call(() => client.device.getAllThings({ lang: 'en' }))
        if (r?.error !== 0) return json(res, 502, { error: r?.error, msg: r?.msg })
        const list = (r.data?.thingList ?? []).map((t) => {
          const d = Array.isArray(t.itemData) ? t.itemData[0] : t.itemData
          return {
            itemType: t.itemType,
            deviceid: d?.deviceid ?? d?.id,
            name: d?.name,
            online: d?.online,
            uiid: d?.extra?.uiid,
            switch: d?.params?.switch,
            switches: d?.params?.switches,
          }
        })
        return json(res, 200, { total: list.length, devices: list })
      }

      // ── 手機 App：抵達回報 ──
      if (route === 'POST /arrive') {
        const a = authed(req, url, true)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { opened: false, reason: a === 'locked' ? '密碼錯誤次數過多，已暫時鎖定，請稍後再試' : '密碼錯誤' })
        let body
        try {
          body = JSON.parse((await readBody(req)) || '{}')
        } catch {
          return json(res, 400, { opened: false, reason: '內容不是有效的 JSON' })
        }
        const { lat, lon, accuracy } = body
        if (![lat, lon, accuracy].every((v) => typeof v === 'number' && Number.isFinite(v))) {
          return json(res, 400, { opened: false, reason: '需要數字欄位 lat、lon、accuracy' })
        }
        // 手機另外附的診斷欄位（都是選填）：只用來紀錄與分析，不影響舊版 App
        const rec = {
          ts: now(), source: body.source === 'simulate' ? 'simulate' : 'arrive', ip: clientIp(req),
          profileId: safeLabel(body.profileId), deviceId: safeLabel(body.deviceId), sessionId: safeLabel(body.sessionId, '', 80),
          lat, lon, accuracy,
          streak: finiteOrUndef(body.streak), fixes: finiteOrUndef(body.fixes),
          sinceEnterS: finiteOrUndef(body.sinceEnterS), intervalMs: finiteOrUndef(body.intervalMs),
          speedMps: finiteOrUndef(body.speedMps), bearingDeg: finiteOrUndef(body.bearingDeg),
          mock: body.mock === true,
          cfg: body.cfg && typeof body.cfg === 'object' && !Array.isArray(body.cfg) ? body.cfg : undefined,
        }
        const reject = (reason, text) => {
          arrivalLog.append({ ...rec, decision: 'rejected', reason })
          return json(res, 200, { opened: false, reason: text })
        }
        if (cfg.doorLat === null || cfg.doorLon === null) {
          return reject('no_door_coords', '伺服器沒有設定 DOOR_LAT／DOOR_LON，無法核對距離')
        }
        rec.distance = haversineM(lat, lon, cfg.doorLat, cfg.doorLon)
        if (rec.mock) return reject('mock_location', '偵測到模擬定位，已拒絕')
        if (accuracy > cfg.maxAccuracyM) {
          return reject('accuracy', `定位誤差 ${Math.round(accuracy)} m 超過伺服器上限 ${cfg.maxAccuracyM} m`)
        }
        if (typeof body.fixTime === 'number' && Number.isFinite(body.fixTime)) {
          const ageS = (now() - body.fixTime) / 1000
          rec.fixAgeS = Math.round(ageS * 10) / 10
          if (ageS > cfg.maxFixAgeSec) return reject('stale', `定位資料過舊（${Math.round(ageS)} 秒前，上限 ${cfg.maxFixAgeSec} 秒）`)
          if (ageS < -30) return reject('clock_skew', '定位時間在未來，請檢查手機的日期時間是否正確')
        }
        if (rec.distance > cfg.arriveRadiusM) {
          return reject('distance', `距離 ${Math.round(rec.distance)} m，超過伺服器上限 ${cfg.arriveRadiusM} m`)
        }
        // 位置都合理，但剛剛才開過門：當作「已完成」回給 App，讓它停止重送、結束定位
        if (door.cooldownLeftSec() > 0) {
          arrivalLog.append({ ...rec, decision: 'deduped', reason: 'cooldown' })
          return json(res, 200, { opened: true, deduped: true, reason: '剛剛已開過門（冷卻中）' })
        }
        const t0 = now()
        const r = await door.open(`抵達（距離 ${Math.round(rec.distance)} m，誤差 ${Math.round(accuracy)} m）`)
        const failed = r.code === 'ewelink_failed' || r.code === 'exception'
        arrivalLog.append({ ...rec, decision: r.opened ? 'opened' : failed ? 'error' : 'rejected', reason: r.opened ? undefined : r.code, detail: r.opened ? undefined : r.reason, openMs: now() - t0 })
        return json(res, 200, r)
      }

      // ── 手動開門（App 圖示／語音）；不核對位置，只靠密碼＋冷卻＋每小時次數 ──
      if (route === 'POST /open') {
        const a = authed(req, url, true)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { opened: false, reason: a === 'locked' ? '密碼錯誤次數過多，已暫時鎖定，請稍後再試' : '密碼錯誤' })
        const t0 = now()
        const r = await door.open('手動開門')
        const failed = r.code === 'ewelink_failed' || r.code === 'exception'
        arrivalLog.append({ ts: t0, source: 'manual', ip: clientIp(req),
          profileId: safeLabel(req.headers['x-profile-id']), deviceId: safeLabel(req.headers['x-device-id']),
          decision: r.opened ? 'opened' : failed ? 'error' : 'rejected', reason: r.opened ? undefined : r.code, detail: r.opened ? undefined : r.reason, openMs: now() - t0 })
        return json(res, 200, r)
      }

      // ── 參數同步：App 啟動／重新布防時取一次；不要輪詢 ──
      if (route === 'GET /config') {
        const a = authed(req, url, true)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        return json(res, 200, { version: appConfig.version, params: appConfig.params })
      }
      if (route === 'POST /config') {
        const a = authed(req, url)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        let body
        try {
          body = JSON.parse((await readBody(req)) || '{}')
        } catch {
          return json(res, 400, { ok: false, errors: ['內容不是有效的 JSON'] })
        }
        const r = appConfig.update(body)
        if (!r.ok) return json(res, 400, r)
        return json(res, 200, { ok: true, version: appConfig.version, params: appConfig.params })
      }

      // ── 手機批次上傳事件／定位樣本（只記錄，不會觸發開門）──
      if (route === 'POST /telemetry/batch') {
        const a = authed(req, url, true)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { ok: false, error: 'unauthorized' })
        let body
        try { body = JSON.parse((await readBody(req, 131072)) || '{}') }
        catch { return json(res, 400, { ok: false, error: 'invalid_json_or_body_too_large' }) }
        if (!Array.isArray(body.events) || body.events.length < 1 || body.events.length > 100) {
          return json(res, 400, { ok: false, error: 'events_must_be_array_of_1_to_100' })
        }
        let accepted = 0, duplicates = 0, invalid = 0
        for (const e of body.events) {
          if (!e || typeof e !== 'object' || Array.isArray(e)) { invalid++; continue }
          const eventId = safeLabel(e.eventId, '', 80)
          if (!eventId) { invalid++; continue }
          if (seenTelemetryIds.has(eventId)) { duplicates++; continue }
          const eventTs = typeof e.ts === 'number' && Number.isFinite(e.ts) && e.ts > 0 ? e.ts : now()
          const row = {
            ts: eventTs,
            source: 'telemetry',
            eventId,
            profileId: safeLabel(e.profileId),
            deviceId: safeLabel(e.deviceId),
            sessionId: safeLabel(e.sessionId, '', 80),
            type: safeLabel(e.type, 'unknown', 48),
            message: typeof e.message === 'string' ? e.message.replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 200) : '',
            fields: safeTelemetryFields(e.fields),
            receivedAt: now(),
          }
          telemetryLog.append(row)
          seenTelemetryIds.add(eventId)
          accepted++
        }
        if (seenTelemetryIds.size > 20000) {
          seenTelemetryIds.clear()
          for (const r of telemetryLog.tail(10000)) if (r.eventId) seenTelemetryIds.add(r.eventId)
        }
        return json(res, 200, { ok: true, accepted, duplicates, invalid })
      }

      // ── 三支手機分開的歷史統計：個人回家時段、定位品質及事件總覽 ──
      if (route === 'GET /analytics') {
        const a = authed(req, url)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        const { buildPersonalAnalytics, formatPersonalAnalyticsText } = await import('./stats.js')
        const profileFilter = url.searchParams.get('profileId')
        const deviceFilter = url.searchParams.get('deviceId')
        const tele = telemetryLog.tail(20000).filter((r) =>
          (!profileFilter || r.profileId === profileFilter) && (!deviceFilter || r.deviceId === deviceFilter))
        const arrivals = arrivalLog.tail(10000).filter((r) =>
          (!profileFilter || r.profileId === profileFilter) && (!deviceFilter || r.deviceId === deviceFilter))
        const report = buildPersonalAnalytics(tele, arrivals, { timeZone: cfg.timeZone })
        if (url.searchParams.get('format') === 'text') {
          res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
          return res.end(formatPersonalAnalyticsText(report))
        }
        return json(res, 200, report)
      }

      // ── 檢視近期原始事件（需密碼；內含敏感定位資料）──
      if (route === 'GET /telemetry') {
        const a = authed(req, url)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        const profileFilter = url.searchParams.get('profileId')
        const deviceFilter = url.searchParams.get('deviceId')
        const requestedLimit = Number(url.searchParams.get('limit') || 100)
        const limit = Number.isFinite(requestedLimit) ? Math.max(1, Math.min(500, Math.floor(requestedLimit))) : 100
        const rows = telemetryLog.tail(20000).filter((r) =>
          (!profileFilter || r.profileId === profileFilter) && (!deviceFilter || r.deviceId === deviceFilter))
        const recent = rows.slice(-limit).reverse()
        if (url.searchParams.get('format') === 'text') {
          const lines = recent.map((r) => `${new Date(r.ts).toISOString()} | ${r.profileId}/${String(r.deviceId).slice(0, 8)} | ${r.type} | session=${r.sessionId || '-'} | event=${r.eventId || '-'} | ${r.message}${r.fields ? ' | ' + JSON.stringify(r.fields) : ''}`)
          res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
          return res.end(lines.join('\n') || '尚無事件紀錄')
        }
        return json(res, 200, { totalMatched: rows.length, returned: recent.length, events: recent })
      }

      // ── 抵達紀錄統計與診斷建議 ──
      if (route === 'GET /stats') {
        const a = authed(req, url)
        if (a !== 'ok') return json(res, a === 'locked' ? 429 : 401, { error: 'unauthorized' })
        const profileFilter = url.searchParams.get('profileId')
        const deviceFilter = url.searchParams.get('deviceId')
        const filtered = arrivalLog.tail(5000).filter((r) =>
          (!profileFilter || r.profileId === profileFilter) && (!deviceFilter || r.deviceId === deviceFilter))
        const stats = buildStats(filtered, { doorLat: cfg.doorLat, doorLon: cfg.doorLon, arriveRadiusM: cfg.arriveRadiusM, maxAccuracyM: cfg.maxAccuracyM })
        stats.profileFilter = profileFilter || null
        stats.deviceFilter = deviceFilter || null
        if (url.searchParams.get('format') === 'text') {
          res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
          return res.end(formatStatsText(stats))
        }
        return json(res, 200, stats)
      }

      return json(res, 404, { error: 'not found' })
    } catch (e) {
      log.error?.('處理請求時發生錯誤：', e)
      if (!res.headersSent) json(res, 500, { opened: false, reason: '伺服器內部錯誤' })
    }
  }

  const server = http.createServer(handler)
  return { server, session, door, appConfig, arrivalLog, telemetryLog }
}

// ───────────────────────── 啟動 ─────────────────────────
function main() {
  const cfg = loadConfig()
  const client = new eWeLink.WebAPI({ appId: cfg.appId, appSecret: cfg.appSecret, region: cfg.region })
  const store = new TokenStore(cfg.tokenFile)
  const { server, session } = createApp({ cfg, client, store })
  server.listen(cfg.port, cfg.host, () => {
    console.log(`芝麻開門伺服器已啟動 http://${cfg.host}:${cfg.port}（已授權：${session.authorized}）`)
  })
  // 每天檢查一次：token 超過 REFRESH_AFTER_DAYS 就更新。這樣即使很久沒開門，refresh token 也不會過期。
  const daily = async () => {
    if (!session.authorized) return
    try {
      await session.call(async () => ({ error: 0 })) // 只為了觸發 ensureFresh
    } catch (e) {
      console.error('每日 token 檢查失敗：', e.message)
    }
  }
  setInterval(daily, 24 * 3600 * 1000).unref()
  setTimeout(daily, 5000).unref()
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
