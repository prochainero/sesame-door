// 抵達紀錄與統計：累積數十次之後，就能看出問題到底出在
// 「圍欄太小」「手機定位飄移」還是「定位更新頻率不足」。
import fs from 'node:fs'
import path from 'node:path'

export class ArrivalLog {
  constructor(file) {
    this.file = file
  }

  append(rec) {
    const row = { ...rec, iso: new Date(rec.ts).toISOString() }
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true })
      fs.appendFileSync(this.file, JSON.stringify(row) + '\n', { mode: 0o600 })
      // These files may contain exact home coordinates; enforce owner-only permissions on existing files too.
      try { fs.chmodSync(this.file, 0o600) } catch {}
    } catch (e) {
      console.error('寫入抵達紀錄失敗：', e.message)
    }
  }

  tail(limit = 5000) {
    // Read backward in chunks: telemetry logs grow over time, so never read the entire file into RAM.
    const wanted = Math.max(1, Math.floor(Number(limit) || 5000))
    let fd
    try {
      fd = fs.openSync(this.file, 'r')
      let position = fs.fstatSync(fd).size
      const chunks = []
      let newlineCount = 0
      const chunkSize = 64 * 1024
      while (position > 0 && newlineCount <= wanted + 1) {
        const length = Math.min(chunkSize, position)
        const buffer = Buffer.allocUnsafe(length)
        const read = fs.readSync(fd, buffer, 0, length, position - length)
        if (read <= 0) break
        const part = buffer.subarray(0, read)
        chunks.unshift(part)
        for (let i = 0; i < part.length; i++) if (part[i] === 10) newlineCount++
        position -= read
      }
      const text = Buffer.concat(chunks).toString('utf8')
      const out = []
      for (const line of text.split('\n').filter(Boolean).slice(-wanted)) {
        try { out.push(JSON.parse(line)) } catch { /* 略過壞掉的行 */ }
      }
      return out
    } catch {
      return []
    } finally {
      if (fd !== undefined) {
        try { fs.closeSync(fd) } catch {}
      }
    }
  }
}

const r1 = (n) => (n === null || n === undefined || !Number.isFinite(n) ? null : Math.round(n * 10) / 10)
const nums = (rows, k) => rows.map((r) => r[k]).filter((v) => typeof v === 'number' && Number.isFinite(v))
function pct(arr, p) {
  if (!arr.length) return null
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))]
}
const summary = (rows, k) => {
  const a = nums(rows, k)
  return { n: a.length, p50: r1(pct(a, 50)), p90: r1(pct(a, 90)), max: r1(a.length ? Math.max(...a) : null) }
}

/** records：ArrivalLog.tail() 的結果；opts：{ doorLat, doorLon, arriveRadiusM, maxAccuracyM } */
export function buildStats(records, opts = {}) {
  const all = records.filter((r) => r.source !== 'auth')
  const arrivals = all.filter((r) => r.source === 'arrive' || r.source === 'simulate')
  const byDecision = {}
  const rejectReasons = {}
  for (const r of all) {
    byDecision[r.decision] = (byDecision[r.decision] || 0) + 1
    if (r.decision !== 'opened' && r.decision !== 'deduped' && r.reason) rejectReasons[r.reason] = (rejectReasons[r.reason] || 0) + 1
  }
  const ok = arrivals.filter((r) => r.decision === 'opened' || r.decision === 'deduped')
  const bad = arrivals.filter((r) => r.decision === 'rejected')

  // 成功開門時，定位點相對門口座標的平均偏移：可看出門口座標是否有系統性偏差
  let meanOffsetOnSuccess = null
  const pts = ok.filter((r) => Number.isFinite(r.lat) && Number.isFinite(r.lon))
  if (pts.length >= 5 && Number.isFinite(opts.doorLat) && Number.isFinite(opts.doorLon)) {
    const mLat = pts.reduce((a, r) => a + r.lat, 0) / pts.length
    const mLon = pts.reduce((a, r) => a + r.lon, 0) / pts.length
    meanOffsetOnSuccess = {
      n: pts.length,
      northM: r1((mLat - opts.doorLat) * 111320),
      eastM: r1((mLon - opts.doorLon) * 111320 * Math.cos((opts.doorLat * Math.PI) / 180)),
    }
  }

  const tips = []
  const n = arrivals.length
  const cnt = (k) => bad.filter((r) => r.reason === k).length
  if (n === 0) {
    tips.push('還沒有抵達紀錄。先實際走幾趟回家，累積約 20～30 筆再來看。')
  } else {
    if (n >= 10 && cnt('accuracy') / n >= 0.3) tips.push(`有 ${Math.round((cnt('accuracy') / n) * 100)}% 的抵達請求因定位誤差過大被拒：多半是手機定位飄移。可檢查手機的「定位精確度／Wi-Fi 與藍牙掃描」是否開啟，或放寬 MAX_ACCURACY_M。`)
    if (n >= 10 && cnt('distance') / n >= 0.3) {
      const d = nums(bad.filter((r) => r.reason === 'distance'), 'distance')
      tips.push(`有 ${Math.round((cnt('distance') / n) * 100)}% 因距離太遠被拒（中位數 ${r1(pct(d, 50))} m）：若接近伺服器上限 ${opts.arriveRadiusM ?? '?'} m，可能是門口座標有偏差，或手機端「抵達距離」設得比伺服器寬。`)
    }
    if (cnt('stale') >= 2) tips.push('多次出現「定位資料過舊」：手機可能卡在舊定位。請檢查省電限制，或調短手機端「靠近時定位間隔」。')
    if (cnt('mock_location') >= 1) tips.push('有請求帶著「模擬定位」標記，請確認手機沒有開模擬位置工具。')
    const since = nums(arrivals, 'sinceEnterS')
    if (since.length >= 5 && pct(since, 50) > 90) tips.push(`從進入圍欄到回報抵達的中位數是 ${Math.round(pct(since, 50))} 秒，偏久：可縮短定位間隔，或放大圍欄半徑讓手機更早開始精確定位。`)
    const fixes = nums(arrivals, 'fixes')
    if (fixes.length >= 5 && pct(fixes, 50) < 3) tips.push('抵達前拿到的定位筆數很少，可能是定位更新頻率不足：把「靠近時定位間隔」調小，或把「高頻定位範圍」調大。')
    if (meanOffsetOnSuccess && Math.hypot(meanOffsetOnSuccess.northM, meanOffsetOnSuccess.eastM) > 8) {
      tips.push(`成功開門時的定位點平均偏離門口座標約 ${r1(Math.hypot(meanOffsetOnSuccess.northM, meanOffsetOnSuccess.eastM))} m（北 ${meanOffsetOnSuccess.northM} m、東 ${meanOffsetOnSuccess.eastM} m）。若你當時都站在門口，建議把門口座標往這個方向校正。`)
    }
    if (!tips.length) tips.push('目前沒有明顯異常。')
  }

  return {
    total: all.length,
    arrivals: n,
    byDecision,
    rejectReasons,
    openedFix: { distanceM: summary(ok, 'distance'), accuracyM: summary(ok, 'accuracy') },
    rejectedFix: { distanceM: summary(bad, 'distance'), accuracyM: summary(bad, 'accuracy') },
    sinceEnterS: summary(arrivals, 'sinceEnterS'),
    fixesBeforeArrival: summary(arrivals, 'fixes'),
    meanOffsetOnSuccess,
    tips,
    recent: all.slice(-15).reverse(),
  }
}

export function formatStatsText(s) {
  const L = []
  L.push(`紀錄共 ${s.total} 筆，其中抵達請求 ${s.arrivals} 筆`)
  L.push(`結果：${JSON.stringify(s.byDecision)}　拒絕原因：${JSON.stringify(s.rejectReasons)}`)
  const o = s.openedFix, b = s.rejectedFix
  L.push(`成功時  距離 p50/p90/max = ${o.distanceM.p50}/${o.distanceM.p90}/${o.distanceM.max} m；誤差 p50/p90/max = ${o.accuracyM.p50}/${o.accuracyM.p90}/${o.accuracyM.max} m`)
  L.push(`被拒時  距離 p50/p90/max = ${b.distanceM.p50}/${b.distanceM.p90}/${b.distanceM.max} m；誤差 p50/p90/max = ${b.accuracyM.p50}/${b.accuracyM.p90}/${b.accuracyM.max} m`)
  L.push('', '診斷建議：', ...s.tips.map((t) => '・' + t), '', '最近紀錄：')
  for (const r of s.recent) {
    const d = typeof r.distance === 'number' ? ` ${r1(r.distance)}m/誤差${r.accuracy}m` : ''
    L.push(`${r.iso} ${r.source} ${r.decision}${r.reason ? `(${r.reason})` : ''}${d}`)
  }
  return L.join('\n')
}

function getLocalParts(ts, timeZone = 'Asia/Taipei') {
  const d = new Date(ts)
  const weekday = new Intl.DateTimeFormat('en-US', { timeZone, weekday: 'short' }).format(d)
  const hm = new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(d)
  const [hour, minute] = hm.split(':').map(Number)
  return { weekday, minuteOfDay: hour * 60 + minute, time: hm }
}

function median(values) {
  if (!values.length) return null
  const a = [...values].sort((x, y) => x - y)
  const middle = Math.floor(a.length / 2)
  return a.length % 2 ? a[middle] : (a[middle - 1] + a[middle]) / 2
}

function fmtMinute(minute) {
  if (!Number.isFinite(minute)) return null
  const n = ((Math.round(minute) % 1440) + 1440) % 1440
  return `${String(Math.floor(n / 60)).padStart(2, '0')}:${String(n % 60).padStart(2, '0')}`
}

function fieldNums(rows, key) {
  return rows.map((r) => r?.fields?.[key]).filter((n) => typeof n === 'number' && Number.isFinite(n))
}

/** 個人化規律第一版：只做歷史統計，不會自動放寬開門安全條件。 */
export function buildPersonalAnalytics(telemetryRows = [], arrivalRows = [], opts = {}) {
  const timeZone = opts.timeZone || 'Asia/Taipei'
  const byProfile = new Map()
  const ensure = (profileId, deviceId) => {
    const key = profileId || 'unknown'
    if (!byProfile.has(key)) byProfile.set(key, { profileId: key, devices: new Set(), events: [], arrivals: [] })
    const profile = byProfile.get(key)
    if (deviceId && deviceId !== 'unknown') profile.devices.add(deviceId)
    return profile
  }
  for (const event of telemetryRows) ensure(event.profileId, event.deviceId).events.push(event)
  for (const row of arrivalRows) if (row.profileId) ensure(row.profileId, row.deviceId).arrivals.push(row)

  const profiles = [...byProfile.values()].map((profile) => {
    const eventCounts = {}
    for (const event of profile.events) {
      const type = event.type || 'unknown'
      eventCounts[type] = (eventCounts[type] || 0) + 1
    }
    const locationFixes = profile.events.filter((event) => event.type === 'location_fix')
    const successfulArrivals = profile.arrivals.filter((row) =>
      row.source === 'arrive' && ['opened', 'deduped'].includes(row.decision))

    const arrivalsByWeekday = {}
    const arrivalSamples = successfulArrivals.map((row) => {
      const local = getLocalParts(row.ts, timeZone)
      ;(arrivalsByWeekday[local.weekday] ||= []).push(local.minuteOfDay)
      return { ts: row.ts, sessionId: row.sessionId || '', minuteOfDay: local.minuteOfDay, weekday: local.weekday }
    })
    const routineByWeekday = Object.fromEntries(Object.entries(arrivalsByWeekday).map(([day, values]) => {
      const typical = median(values)
      return [day, {
        samples: values.length,
        typicalTime: fmtMinute(typical),
        windowStart: fmtMinute(typical === null ? null : typical - 45),
        windowEnd: fmtMinute(typical === null ? null : typical + 45),
        confidence: values.length >= 8 ? 'higher' : values.length >= 3 ? 'early_estimate' : 'insufficient',
      }]
    }))

    const exitBySession = new Map()
    for (const event of profile.events) {
      if (event.type === 'geofence_exit' && event.sessionId) exitBySession.set(event.sessionId, event.ts)
    }
    const tripMinutes = []
    for (const arrival of arrivalSamples) {
      const exit = exitBySession.get(arrival.sessionId)
      if (exit && arrival.ts >= exit) tripMinutes.push((arrival.ts - exit) / 60000)
    }
    const allMinutes = arrivalSamples.map((sample) => sample.minuteOfDay)
    const overallMedian = median(allMinutes)
    const today = getLocalParts(Number.isFinite(opts.now) ? opts.now : Date.now(), timeZone)
    const todayRoutine = routineByWeekday[today.weekday] || null
    const locationAccuracy = summary(locationFixes.map((event) => ({ v: event.fields?.accuracyM })), 'v')
    const locationSpeed = summary(locationFixes.map((event) => ({ v: event.fields?.speedMps })), 'v')
    const recent = profile.events.slice().sort((a, b) => (b.ts || 0) - (a.ts || 0)).slice(0, 15).map((event) => ({
      ts: event.ts,
      iso: event.iso || (Number.isFinite(event.ts) ? new Date(event.ts).toISOString() : null),
      type: event.type,
      message: event.message,
      sessionId: event.sessionId || null,
      fields: event.fields ? { ...event.fields } : undefined,
    }))

    return {
      profileId: profile.profileId,
      devices: [...profile.devices],
      eventCount: profile.events.length,
      eventCounts,
      locationSamples: locationFixes.length,
      locationQuality: { accuracyM: locationAccuracy, speedMps: locationSpeed },
      successfulArrivalSamples: arrivalSamples.length,
      routineByWeekday,
      typicalReturnTime: {
        samples: allMinutes.length,
        typicalTime: fmtMinute(overallMedian),
        confidence: allMinutes.length >= 8 ? 'higher' : allMinutes.length >= 3 ? 'early_estimate' : 'insufficient',
        method: 'median_of_successful_arrival_times_by_local_time',
        timeZone,
        note: allMinutes.length < 3
          ? '至少累積 3 次成功抵達才顯示初步規律；8 次以上較有參考價值。'
          : '這是歷史抵達時刻統計，不是即時交通 ETA，也不可直接作為開門條件。',
      },
      predictionForToday: {
        weekday: today.weekday,
        typicalTime: todayRoutine?.typicalTime || null,
        windowStart: todayRoutine?.windowStart || null,
        windowEnd: todayRoutine?.windowEnd || null,
        samples: todayRoutine?.samples || 0,
        confidence: todayRoutine?.confidence || 'insufficient',
        method: 'weekday_median_historical_window',
        note: '只預測今天常見的抵達時段；尚未使用即時路線、交通或全程位置追蹤，不能視為目前剩餘 ETA 或開門授權。',
      },
      timeOutsideHomeFenceBeforeReturnMinutes: {
        samples: tripMinutes.length,
        median: r1(median(tripMinutes)),
        p90: r1(pct(tripMinutes, 90)),
        note: '從離開家附近地理圍欄到下次回家抵達的總時間，通常包含上班／外出時間，不是純交通時間。',
      },
      recent,
    }
  }).sort((a, b) => a.profileId.localeCompare(b.profileId))

  const allLocationFixes = telemetryRows.filter((event) => event.type === 'location_fix')
  const sharedAccuracy = summary(allLocationFixes.map((event) => ({ v: event.fields?.accuracyM })), 'v')
  const sharedSpeed = summary(allLocationFixes.map((event) => ({ v: event.fields?.speedMps })), 'v')
  return {
    generatedAt: new Date().toISOString(),
    timeZone,
    profileCount: profiles.length,
    totalTelemetryEvents: telemetryRows.length,
    sharedBaseline: {
      locationSamples: allLocationFixes.length,
      accuracyM: sharedAccuracy,
      speedMps: sharedSpeed,
      note: '共用基準是所有已上傳定位樣本的整體統計；個別手機差異仍要看各 profile 的 locationQuality，不會直接把某支手機的誤差套用到另一支。',
    },
    note: 'Profile IDs are client-supplied reporting labels, not a cryptographic identity boundary when devices share one secret. Historical arrival times are advisory and never authorize opening.',
    profiles,
  }
}

export function formatPersonalAnalyticsText(report) {
  const lines = [
    `芝麻開門個人化分析（${report.timeZone}）`,
    `個人數：${report.profileCount}；事件總數：${report.totalTelemetryEvents}`,
    `共用定位基準：樣本 ${report.sharedBaseline.locationSamples}；誤差 p50/p90/max ${report.sharedBaseline.accuracyM.p50}/${report.sharedBaseline.accuracyM.p90}/${report.sharedBaseline.accuracyM.max} m`,
    '注意：回家時刻是歷史統計，不是即時交通 ETA；絕不可直接作為開門條件。',
  ]
  for (const p of report.profiles) {
    lines.push('', `【${p.profileId}】設備 ${p.devices.length} 支｜事件 ${p.eventCount}｜定位樣本 ${p.locationSamples}｜成功抵達樣本 ${p.successfulArrivalSamples}`)
    lines.push(`典型抵達時間：${p.typicalReturnTime.typicalTime || '資料不足'}（樣本 ${p.typicalReturnTime.samples}，信心：${p.typicalReturnTime.confidence}）`)
    lines.push(`定位誤差 p50/p90/max：${p.locationQuality.accuracyM.p50}/${p.locationQuality.accuracyM.p90}/${p.locationQuality.accuracyM.max} m`)
    const todayPrediction = p.predictionForToday
    lines.push(`今天（${todayPrediction.weekday}）歷史抵達時段：${todayPrediction.typicalTime || '資料不足'}${todayPrediction.windowStart && todayPrediction.windowEnd ? `（約 ${todayPrediction.windowStart}–${todayPrediction.windowEnd}）` : ''}；樣本 ${todayPrediction.samples}（${todayPrediction.confidence}）`)
    lines.push(`離開住家圍欄至下次返回中位數：${p.timeOutsideHomeFenceBeforeReturnMinutes.median ?? '資料不足'} 分鐘（配對 ${p.timeOutsideHomeFenceBeforeReturnMinutes.samples} 次；包含外出活動時間，不是單純通勤時間）`)
    lines.push('星期別抵達規律：')
    for (const [day, value] of Object.entries(p.routineByWeekday)) {
      lines.push(`・${day}: ${value.typicalTime}（${value.windowStart}–${value.windowEnd}），樣本 ${value.samples}（${value.confidence}）`)
    }
    lines.push(`事件分布：${JSON.stringify(p.eventCounts)}`)
  }
  return lines.join('\n')
}
