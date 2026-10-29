import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createApp, loadConfig, TokenStore, haversineM, doorParams } from '../server.js'
import { AppConfig } from '../appconfig.js'
import { ArrivalLog, buildStats } from '../stats.js'

const silent = { info() {}, warn() {}, error() {} }
const BASE_ENV = {
  EWELINK_APP_ID: 'app', EWELINK_APP_SECRET: 'sec', REDIRECT_URL: 'https://x.duckdns.org/ewelink/callback',
  SHARED_SECRET: 'pw', DOOR_DEVICE_ID: '1000abc', DOOR_LAT: '24.988026', DOOR_LON: '121.450173',
}

function fakeClient(opts = {}) {
  const calls = []
  const c = {
    at: '', rt: '', region: '', calls,
    setUrl(r) { this.endpoint = r },
    oauth: {
      createLoginUrl: ({ state }) => `https://login.example/?state=${state}`,
      getToken: async (o) => { calls.push(['getToken', o]); return opts.token ?? { error: 0, data: { accessToken: 'AT1', refreshToken: 'RT1', region: 'as', user: { apikey: 'k' } } } },
    },
    user: {
      refreshToken: async ({ rt }) => { calls.push(['refresh', rt]); return opts.refresh ?? { error: 0, data: { at: 'AT2', rt: 'RT2' } } },
    },
    device: {
      setThingStatus: async (o) => {
        calls.push(['set', o, c.at])
        const r = opts.setQueue?.shift()
        return r ?? { error: 0, data: {} }
      },
      getAllThings: async () => ({ error: 0, data: { thingList: [{ itemType: 1, itemData: { deviceid: '1000abc', name: '大門', online: true, extra: { uiid: 4 }, params: { switches: [{ switch: 'off', outlet: 0 }] } } }] } }),
    },
  }
  return c
}

async function setup({ env = {}, client = fakeClient(), tokens = null, clock = { t: 1_000_000_000_000 } } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sesame-'))
  const file = path.join(dir, 'tokens.json')
  if (tokens) fs.writeFileSync(file, JSON.stringify(tokens))
  const cfg = loadConfig({ ...BASE_ENV, TOKEN_FILE: file, LOG_FILE: path.join(dir, 'arrivals.jsonl'), CONFIG_FILE: path.join(dir, 'config.json'), TELEMETRY_FILE: path.join(dir, 'telemetry.jsonl'), ...env })
  const store = new TokenStore(file)
  const app = createApp({ cfg, client, store, log: silent, now: () => clock.t })
  await new Promise((r) => app.server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${app.server.address().port}`
  const post = (p, body, secret = 'pw') => fetch(base + p, { method: 'POST', headers: { 'X-Secret': secret, 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) })
  return { ...app, base, post, client, clock, file, cfg, dir, close: () => new Promise((r) => app.server.close(r)) }
}
const authedTokens = (clock) => ({ at: 'AT0', rt: 'RT0', region: 'as', refreshedAt: clock.t })

test('config: 缺必要欄位會明確報錯；單路／多路參數', () => {
  assert.throws(() => loadConfig({}), /EWELINK_APP_ID/)
  assert.deepEqual(doorParams({ outlet: null }), { switch: 'on' })
  assert.deepEqual(doorParams({ outlet: 2 }), { switches: [{ switch: 'on', outlet: 2 }] })
  const d = haversineM(24.988026, 121.450173, 24.988026, 121.450173 + 0.0005)
  assert.ok(d > 45 && d < 55, `約 50m，實際 ${d}`)
})

test('/health 不需密碼且不外洩 token', async () => {
  const s = await setup()
  const j = await (await fetch(s.base + '/health')).json()
  assert.equal(j.authorized, false)
  assert.ok(!JSON.stringify(j).includes('AT'))
  await s.close()
})

test('授權流程：沒開視窗不能 callback；login 需密碼；成功後 token 存檔且為 0600', async () => {
  const s = await setup()
  assert.equal((await fetch(s.base + '/ewelink/callback?code=x&region=as')).status, 400)
  assert.equal((await fetch(s.base + '/ewelink/login?key=wrong', { redirect: 'manual' })).status, 401)
  const r = await fetch(s.base + '/ewelink/login?key=pw', { redirect: 'manual' })
  assert.equal(r.status, 302)
  const state = new URL(r.headers.get('location')).searchParams.get('state')
  assert.equal((await fetch(s.base + `/ewelink/callback?code=c1&region=as&state=WRONG`)).status, 400)
  const ok = await fetch(s.base + `/ewelink/callback?code=c1&region=as&state=${state}`)
  assert.equal(ok.status, 200)
  const saved = JSON.parse(fs.readFileSync(s.file, 'utf8'))
  assert.equal(saved.at, 'AT1'); assert.equal(saved.rt, 'RT1'); assert.equal(saved.region, 'as')
  if (process.platform !== 'win32') assert.equal(fs.statSync(s.file).mode & 0o777, 0o600)
  assert.equal(s.client.at, 'AT1')
  // 成功後視窗關閉，不能重放
  assert.equal((await fetch(s.base + `/ewelink/callback?code=c1&region=as&state=${state}`)).status, 400)
  await s.close()
})

test('授權：eWeLink 沒帶回 state 時，仍可在 10 分鐘視窗內完成', async () => {
  const s = await setup()
  await fetch(s.base + '/ewelink/login?key=pw', { redirect: 'manual' })
  assert.equal((await fetch(s.base + '/ewelink/callback?code=c1&region=as')).status, 200)
  await s.close()
})

test('授權失敗（code 無效）會顯示原因，不存 token', async () => {
  const s = await setup({ client: fakeClient({ token: { error: 405, msg: 'invalid code', data: {} } }) })
  await fetch(s.base + '/ewelink/login?key=pw', { redirect: 'manual' })
  const r = await fetch(s.base + '/ewelink/callback?code=bad&region=as')
  assert.equal(r.status, 400)
  assert.match(await r.text(), /405/)
  assert.equal(fs.existsSync(s.file), false)
  await s.close()
})

test('/arrive：密碼、格式、誤差、距離、成功、冷卻', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  const here = { lat: 24.988026, lon: 121.450173 }
  assert.equal((await s.post('/arrive', { ...here, accuracy: 5 }, 'nope')).status, 401)
  assert.equal((await s.post('/arrive', { lat: 'x' })).status, 400)
  let j = await (await s.post('/arrive', { ...here, accuracy: 80 })).json()
  assert.equal(j.opened, false); assert.match(j.reason, /誤差/)
  j = await (await s.post('/arrive', { lat: 24.9895, lon: 121.4515, accuracy: 5 })).json()
  assert.equal(j.opened, false); assert.match(j.reason, /距離/)
  assert.equal(s.client.calls.filter((c) => c[0] === 'set').length, 0, '被擋下時不可碰到 eWeLink')
  j = await (await s.post('/arrive', { ...here, accuracy: 6 })).json()
  assert.equal(j.opened, true)
  const set = s.client.calls.find((c) => c[0] === 'set')
  assert.deepEqual(set[1], { type: 1, id: '1000abc', params: { switch: 'on' } })
  j = await (await s.post('/arrive', { ...here, accuracy: 6 })).json()
  assert.equal(j.opened, true); assert.equal(j.deduped, true, '冷卻中的有效抵達回報視為已完成，讓 App 停止重送')
  assert.equal(s.client.calls.filter((c) => c[0] === 'set').length, 1, '冷卻中不可重複開門')
  j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /冷卻/, '手動開門在冷卻中仍會明確告知')
  clock.t += 61_000
  j = await (await s.post('/arrive', { ...here, accuracy: 6 })).json()
  assert.equal(j.opened, true)
  await s.close()
})

test('多路設備：送出指定通道', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, env: { DOOR_OUTLET: '1' }, tokens: authedTokens(clock) })
  await s.post('/open')
  assert.deepEqual(s.client.calls.find((c) => c[0] === 'set')[1].params, { switches: [{ switch: 'on', outlet: 1 }] })
  await s.close()
})

test('/open：不核對位置，但要密碼；沒設 DOOR_DEVICE_ID 會講清楚', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock), env: { DOOR_DEVICE_ID: '' } })
  assert.equal((await s.post('/open', {}, 'nope')).status, 401)
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /DOOR_DEVICE_ID/)
  await s.close()
})

test('token 超過 10 天：開門前先自動 refresh，且用新 token 呼叫、新 rt 存檔', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: { ...authedTokens(clock), refreshedAt: clock.t - 11 * 86400000 } })
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, true)
  assert.deepEqual(s.client.calls.map((c) => c[0]), ['refresh', 'set'])
  assert.equal(s.client.calls[1][2], 'AT2', '開門要用新的 access token')
  assert.equal(JSON.parse(fs.readFileSync(s.file, 'utf8')).rt, 'RT2')
  await s.close()
})

test('eWeLink 回 token 過期：自動 refresh 後重試一次並成功', async () => {
  const clock = { t: 1_000_000_000_000 }
  const client = fakeClient({ setQueue: [{ error: 402, msg: 'token expired' }] })
  const s = await setup({ clock, client, tokens: authedTokens(clock) })
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, true)
  assert.deepEqual(client.calls.map((c) => c[0]), ['set', 'refresh', 'set'])
  await s.close()
})

test('設備離線等非 token 錯誤：不亂換 token，原因回報給 App', async () => {
  const clock = { t: 1_000_000_000_000 }
  const client = fakeClient({ setQueue: [{ error: 4002, msg: 'device offline' }] })
  const s = await setup({ clock, client, tokens: authedTokens(clock) })
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /4002/)
  assert.deepEqual(client.calls.map((c) => c[0]), ['set'])
  await s.close()
})

test('refresh 失敗（例如超過兩個月）：回報需重新授權，不當機', async () => {
  const clock = { t: 1_000_000_000_000 }
  const client = fakeClient({ refresh: { error: 406, msg: 'rt invalid' } })
  const s = await setup({ clock, client, tokens: { ...authedTokens(clock), refreshedAt: clock.t - 20 * 86400000 } })
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /重新授權|refresh/)
  await s.close()
})

test('尚未授權時開門：明確提示', async () => {
  const s = await setup()
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /授權/)
  await s.close()
})

test('/ewelink/devices：列出設備 ID', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  assert.equal((await fetch(s.base + '/ewelink/devices')).status, 401)
  const j = await (await fetch(s.base + '/ewelink/devices?key=pw')).json()
  assert.equal(j.devices[0].deviceid, '1000abc'); assert.equal(j.devices[0].name, '大門')
  await s.close()
})

test('連續猜錯密碼 5 次後鎖定該 IP 15 分鐘（連正確密碼也暫時擋）', async () => {
  const s = await setup()
  for (let i = 0; i < 5; i++) assert.equal((await s.post('/open', {}, 'guess' + i)).status, 401)
  assert.equal((await s.post('/open', {}, 'pw')).status, 429)
  s.clock.t += 14 * 60_000
  assert.equal((await s.post('/open', {}, 'pw')).status, 429, '14 分鐘還在鎖定')
  s.clock.t += 2 * 60_000
  assert.notEqual((await s.post('/open', {}, 'pw')).status, 429)
  await s.close()
})

test('鎖定是依 IP：亂猜的 IP 被鎖，另一個 IP（經 Caddy 的 X-Forwarded-For）不受影響', async () => {
  const s = await setup()
  const as = (ip, secret) => fetch(s.base + '/open', { method: 'POST', headers: { 'X-Secret': secret, 'X-Forwarded-For': ip } })
  for (let i = 0; i < 5; i++) await as('203.0.113.9', 'bad' + i)
  assert.equal((await as('203.0.113.9', 'pw')).status, 429)
  assert.notEqual((await as('198.51.100.7', 'pw')).status, 429, '我的手機（另一個 IP）不能被別人的亂猜連累')
  await s.close()
})

test('/arrive：模擬定位、過舊、時間在未來都會被擋，且不碰 eWeLink', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  const here = { lat: 24.988026, lon: 121.450173, accuracy: 5 }
  let j = await (await s.post('/arrive', { ...here, mock: true })).json()
  assert.equal(j.opened, false); assert.match(j.reason, /模擬/)
  j = await (await s.post('/arrive', { ...here, fixTime: clock.t - 60_000 })).json()
  assert.equal(j.opened, false); assert.match(j.reason, /過舊/)
  j = await (await s.post('/arrive', { ...here, fixTime: clock.t + 120_000 })).json()
  assert.equal(j.opened, false); assert.match(j.reason, /未來|時間/)
  assert.equal(s.client.calls.filter((c) => c[0] === 'set').length, 0)
  j = await (await s.post('/arrive', { ...here, fixTime: clock.t - 3_000 })).json()
  assert.equal(j.opened, true, '新鮮的定位可以通過')
  await s.close()
})

test('舊版 App（沒有 fixTime／mock 等欄位）仍然可以用', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  const j = await (await s.post('/arrive', { lat: 24.988026, lon: 121.450173, accuracy: 6 })).json()
  assert.equal(j.opened, true)
  await s.close()
})

test('每小時開門次數上限（MAX_OPENS_PER_HOUR）', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock), env: { MAX_OPENS_PER_HOUR: '2', COOLDOWN_SEC: '1' } })
  for (let i = 0; i < 2; i++) { assert.equal((await (await s.post('/open')).json()).opened, true); clock.t += 5_000 }
  const j = await (await s.post('/open')).json()
  assert.equal(j.opened, false); assert.match(j.reason, /上限/)
  clock.t += 3600_000
  assert.equal((await (await s.post('/open')).json()).opened, true, '過了一小時恢復')
  await s.close()
})

test('每次抵達都寫進紀錄（含診斷欄位）；密碼錯誤也記，但不含密碼', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  const here = { lat: 24.988026, lon: 121.450173 }
  await s.post('/arrive', { ...here, accuracy: 80, fixes: 3, sinceEnterS: 20, streak: 0 })
  await s.post('/arrive', { ...here, accuracy: 6, fixes: 5, sinceEnterS: 31, streak: 2, intervalMs: 2500, cfg: { fence: 55 } })
  await s.post('/open', {}, 'wrong-password-xyz')
  const rows = s.arrivalLog.tail()
  assert.deepEqual(rows.map((r) => r.decision), ['rejected', 'opened', 'rejected'])
  assert.equal(rows[0].reason, 'accuracy'); assert.equal(rows[0].fixes, 3)
  assert.equal(rows[1].sinceEnterS, 31); assert.equal(rows[1].intervalMs, 2500); assert.deepEqual(rows[1].cfg, { fence: 55 })
  assert.equal(rows[2].reason, 'bad_secret')
  assert.ok(!fs.readFileSync(path.join(s.dir, 'arrivals.jsonl'), 'utf8').includes('wrong-password-xyz'))
  await s.close()
})

test('/stats：需密碼；JSON 與純文字；有診斷建議', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  assert.equal((await fetch(s.base + '/stats')).status, 401)
  const empty = await (await fetch(s.base + '/stats?key=pw')).json()
  assert.equal(empty.arrivals, 0); assert.match(empty.tips[0], /還沒有/)
  const here = { lat: 24.988026, lon: 121.450173 }
  for (let i = 0; i < 12; i++) await s.post('/arrive', { ...here, accuracy: 60 })   // 12 次都因誤差被拒
  await s.post('/arrive', { ...here, accuracy: 5 })
  const j = await (await fetch(s.base + '/stats?key=pw')).json()
  assert.equal(j.arrivals, 13); assert.equal(j.rejectReasons.accuracy, 12); assert.equal(j.byDecision.opened, 1)
  assert.ok(j.tips.some((t) => /定位誤差/.test(t)), '誤差被拒占多數時要提示手機定位飄移')
  const t = await (await fetch(s.base + '/stats?key=pw&format=text')).text()
  assert.match(t, /診斷建議/)
  await s.close()
})

test('ArrivalLog.tail：大檔只讀取最新紀錄並保留 UTF-8 文字', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sesame-tail-'))
  const file = path.join(dir, 'telemetry.jsonl')
  const lines = Array.from({ length: 1000 }, (_, i) => JSON.stringify({ ts: i + 1, eventId: `id-${i}`, message: `中文定位紀錄-${i}-` + 'x'.repeat(100) }))
  fs.writeFileSync(file, lines.join('\n') + '\n')
  const log = new ArrivalLog(file)
  const tail = log.tail(5)
  assert.equal(tail.length, 5)
  assert.equal(tail[0].eventId, 'id-995')
  assert.equal(tail[4].eventId, 'id-999')
  assert.match(tail[4].message, /中文定位紀錄-999/)
})

test('buildStats：偵測門口座標系統性偏移、定位筆數太少', () => {
  const rows = []
  for (let i = 0; i < 8; i++) rows.push({ ts: i, iso: 'x', source: 'arrive', decision: 'opened', lat: 24.988026 + 0.0001, lon: 121.450173, accuracy: 6, distance: 11, fixes: 2, sinceEnterS: 20 })
  const s = buildStats(rows, { doorLat: 24.988026, doorLon: 121.450173, arriveRadiusM: 40 })
  assert.ok(s.meanOffsetOnSuccess.northM > 10 && s.meanOffsetOnSuccess.northM < 12)
  assert.ok(s.tips.some((t) => /門口座標/.test(t)))
  assert.ok(s.tips.some((t) => /更新頻率不足/.test(t)))
})

test('/config：取得預設值、修改、驗證範圍、重啟後仍在、null 還原', async () => {
  const s = await setup()
  assert.equal((await fetch(s.base + '/config')).status, 401)
  let j = await (await fetch(s.base + '/config?key=pw')).json()
  assert.equal(j.params.cfg_fence_radius, 55); assert.equal(j.params.cfg_confirm_count, 2); assert.equal(j.params.cfg_near_interval, 2.5)
  const v0 = j.version
  let r = await s.post('/config', { cfg_fence_radius: 120, cfg_confirm_count: 3 })
  assert.equal(r.status, 200)
  j = await r.json(); assert.equal(j.params.cfg_fence_radius, 120); assert.notEqual(j.version, v0)
  r = await s.post('/config', { cfg_fence_radius: 5 })
  assert.equal(r.status, 400); assert.match((await r.json()).errors[0], /cfg_fence_radius/)
  r = await s.post('/config', { nope: 1 }); assert.equal(r.status, 400)
  r = await s.post('/config', { cfg_arrive_radius: 150 })
  assert.equal(r.status, 400, '抵達距離不可大於圍欄半徑（目前 120）')
  assert.equal(new AppConfig(path.join(s.dir, 'config.json')).params.cfg_confirm_count, 3, '寫入檔案，重啟後還在')
  r = await s.post('/config', { cfg_fence_radius: null, cfg_confirm_count: null })
  j = await r.json(); assert.equal(j.params.cfg_fence_radius, 55); assert.equal(j.params.cfg_confirm_count, 2)
  await s.close()
})

test('同時兩個抵達回報只會開一次門', async () => {
  const clock = { t: 1_000_000_000_000 }
  const s = await setup({ clock, tokens: authedTokens(clock) })
  const here = { lat: 24.988026, lon: 121.450173, accuracy: 5 }
  const [a, b] = await Promise.all([s.post('/arrive', here).then((r) => r.json()), s.post('/arrive', here).then((r) => r.json())])
  // 第二個請求可能撞到「處理中」(opened:false)，也可能晚一點到而被視為「剛開過」(opened:true, deduped)；兩種都對
  assert.equal([a, b].filter((x) => x.opened && !x.deduped).length, 1, '只有一個請求真的開門')
  assert.equal(s.client.calls.filter((c) => c[0] === 'set').length, 1, '只呼叫 eWeLink 一次')
  await s.close()
})

test('個人化 Telemetry：批次上傳、去重、三支手機分流與分析報表', async () => {
  const clock = { t: Date.now() }
  const s = await setup({ clock, tokens: authedTokens(clock), env: { COOLDOWN_SEC: '1' } })
  const events = [
    { eventId: 'evt-exit-A', ts: clock.t - 30 * 60_000, profileId: 'A', deviceId: 'phone-a', sessionId: 'trip-a', type: 'geofence_exit', message: 'exit', fields: { fenceRadiusM: 120 } },
    { eventId: 'evt-fix-A', ts: clock.t - 20 * 60_000, profileId: 'A', deviceId: 'phone-a', sessionId: 'trip-a', type: 'location_fix', message: 'fix', fields: { lat: 24.988, lon: 121.450, accuracyM: 8, distanceM: 20, speedMps: 1.1 } },
    { eventId: 'evt-exit-B', ts: clock.t - 45 * 60_000, profileId: 'B', deviceId: 'phone-b', sessionId: 'trip-b', type: 'geofence_exit', message: 'exit', fields: { fenceRadiusM: 120 } },
    { eventId: 'evt-fix-B', ts: clock.t - 40 * 60_000, profileId: 'B', deviceId: 'phone-b', sessionId: 'trip-b', type: 'location_fix', message: 'fix', fields: { lat: 24.988, lon: 121.450, accuracyM: 18, distanceM: 28, speedMps: 2.5 } },
  ]
  assert.equal((await s.post('/telemetry/batch', { events }, 'wrong')).status, 401)
  const upload = await (await s.post('/telemetry/batch', { events })).json()
  assert.equal(upload.ok, true); assert.equal(upload.accepted, 4); assert.equal(upload.duplicates, 0)
  const repeated = await (await s.post('/telemetry/batch', { events })).json()
  assert.equal(repeated.accepted, 0); assert.equal(repeated.duplicates, 4)
  assert.equal(s.telemetryLog.tail().length, 4, '重送的 eventId 不應重複寫入')

  const here = { lat: 24.988026, lon: 121.450173, accuracy: 6 }
  let r = await s.post('/arrive', { ...here, profileId: 'A', deviceId: 'phone-a', sessionId: 'trip-a' })
  assert.equal((await r.json()).opened, true)
  clock.t += 2000
  r = await s.post('/arrive', { ...here, profileId: 'B', deviceId: 'phone-b', sessionId: 'trip-b' })
  assert.equal((await r.json()).opened, true)

  const report = await (await fetch(s.base + '/analytics?key=pw')).json()
  assert.equal(report.profileCount, 2)
  assert.equal(report.sharedBaseline.locationSamples, 2)
  const a = report.profiles.find((p) => p.profileId === 'A')
  const b = report.profiles.find((p) => p.profileId === 'B')
  assert.ok(a && b)
  assert.equal(a.locationQuality.accuracyM.p50, 8)
  assert.equal(b.locationQuality.accuracyM.p50, 18)
  assert.equal(a.successfulArrivalSamples, 1)
  assert.equal(b.successfulArrivalSamples, 1)
  assert.ok(a.predictionForToday && a.predictionForToday.method === 'weekday_median_historical_window')
  assert.equal(a.timeOutsideHomeFenceBeforeReturnMinutes.samples, 1)
  assert.equal(b.timeOutsideHomeFenceBeforeReturnMinutes.samples, 1)

  const onlyA = await (await fetch(s.base + '/analytics?key=pw&profileId=A&format=text')).text()
  assert.match(onlyA, /【A】/); assert.doesNotMatch(onlyA, /【B】/)
  const raw = await (await fetch(s.base + '/telemetry?key=pw&profileId=B&format=text')).text()
  assert.match(raw, /evt-fix-B|location_fix/)
  await s.close()
})
