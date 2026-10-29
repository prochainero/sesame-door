# 芝麻開門伺服器

手機 App 確認抵達後，回報位置到這裡；伺服器再核對一次（密碼、模擬定位、定位誤差、定位是否過舊、距離、冷卻、每小時次數），
通過才呼叫 eWeLink 雲端 API 開門。eWeLink 的 AppSecret 與 token 只存在這台伺服器，不會進到手機 App。

**VM 的角色**：只在手機送來請求時才運算，不輪詢手機位置，負載極低。它**無法提高手機 GPS 的實際精度**，
能做的是過濾不可靠的定位、記錄每次抵達的數據、以及集中存放參數。定位頻率仍由手機執行。

| 路徑 | 用途 |
|---|---|
| `GET /ewelink/login?key=密碼` | 一次性授權：登入 eWeLink 帳號 |
| `GET /ewelink/callback` | eWeLink 授權完成後導回（Redirect URL 就是這個） |
| `GET /ewelink/devices?key=密碼` | 列出帳號下的設備與 ID |
| `POST /arrive` | 手機 App 抵達回報（Header `X-Secret`；Body 必填 `{lat, lon, accuracy}`，新版 App 另附 `fixTime`、`mock`、`streak`、`fixes`、`sinceEnterS`… 供檢查與分析） |
| `POST /open` | 手動開門（App 圖示／語音；只驗密碼＋冷卻＋每小時次數） |
| `GET /config` | 取得手機端定位參數（圍欄半徑、抵達距離、連續筆數、定位間隔…）。App 只在你按「從伺服器同步」時才來取 |
| `POST /config` | 修改上述參數（有範圍驗證，`null` ＝ 還原預設） |
| `GET /stats?key=密碼` | 抵達紀錄統計與診斷建議；可用 `profileId`／`deviceId` 篩選 |
| `POST /telemetry/batch` | 上傳 App 批次事件與定位樣本；只記錄、不會開門 |
| `GET /telemetry?key=密碼&profileId=A` | 檢視近期原始紀錄（含定位資料，請妥善保護） |
| `GET /analytics?key=密碼` | 每位使用者分開統計回家時段、定位品質與事件；`&format=text` 為純文字 |
| `GET /health` | 狀態（是否已授權、token 幾天前更新） |

## 部署步驟（Ubuntu／Debian）

### 0. 先確認 80／443 沒被別的程式占用
```bash
sudo ss -ltnp | grep -E ':80 |:443 '
```
沒有輸出就繼續；有輸出代表已有別的伺服器在用，先處理再往下。

### 1. 安裝 Node.js 20 與 Caddy
Ubuntu 內建的 Node 版本通常太舊，請用 NodeSource：
```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
node -v   # 需要 v18 以上

sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
```

### 2. 放程式、安裝套件
把本資料夾放到 `/home/ubuntu/sesame-server`（路徑不同的話，記得改 `sesame.service`）：
```bash
cd /home/ubuntu/sesame-server
npm install --omit=dev
cp .env.example .env
nano .env     # 填 AppID、AppSecret、REDIRECT_URL、SHARED_SECRET；DOOR_DEVICE_ID 先空著
chmod 600 .env
```
`SHARED_SECRET` 請用長的隨機字串，可以用 `openssl rand -hex 24` 產生，之後貼到手機 App 的「密碼」欄位。

### 3. 開機自動啟動
```bash
sudo cp sesame.service /etc/systemd/system/sesame.service
sudo systemctl daemon-reload
sudo systemctl enable --now sesame
sudo systemctl status sesame      # 應該是 active (running)
curl localhost:3000/health
```

### 4. Caddy（https）
編輯 `Caddyfile`，把名字換成你的 DuckDNS 子網域，再：
```bash
sudo cp Caddyfile /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://你的名字.duckdns.org/health     # 看到 {"ok":true,...} 代表 https 通了
```
第一次需要幾秒鐘申請憑證；失敗請看 `sudo journalctl -u caddy -n 50`（常見原因：DuckDNS 的 IP 不對、80/443 沒開）。

### 5. eWeLink 開發者後台
到 dev.ewelink.cc 開啟你的應用程式，確認 **Redirect URL** 是
`https://你的名字.duckdns.org/ewelink/callback`，和 `.env` 的 `REDIRECT_URL` 一字不差（含 https、結尾不要多斜線）。

### 6. 授權一次
用手機或電腦瀏覽器開：
```
https://你的名字.duckdns.org/ewelink/login?key=你的SHARED_SECRET
```
登入你的 eWeLink 帳號並同意授權，看到「✅ 授權成功」就完成。token 會存在 `data/tokens.json`，之後自動更新。

### 7. 查設備 ID
```
https://你的名字.duckdns.org/ewelink/devices?key=你的SHARED_SECRET
```
找到開門那顆控制器的 `deviceid`，填進 `.env` 的 `DOOR_DEVICE_ID`。
如果它是多路設備（例如四路），再把要開的那一路填進 `DOOR_OUTLET`（從 0 開始數）。然後：
```bash
sudo systemctl restart sesame
```
**重要**：該路要先在 eWeLink 手機 App 裡設成「點動（Inching）」，否則送 `on` 之後門會一直導通不放開。

### 8. 手動測試開門（人先站在門旁邊！）
```bash
curl -X POST https://你的名字.duckdns.org/open -H "X-Secret: 你的SHARED_SECRET"
```
回傳 `{"opened":true,...}` 而且門真的動了，伺服器這邊就完成。

**注意（品牌授權）**：在 eWeLink 開發者後台建立的應用，通常只對 Sonoff／coolkit 等品牌的設備免費授權；
其他品牌的設備可能列不出來，或控制時回 `30017`，需要廠商同意。若 `/ewelink/devices` 是空的，先確認這點。

## 手機 App 設定
- 伺服器網址：`https://你的名字.duckdns.org/arrive`
- 密碼：和 `.env` 的 `SHARED_SECRET` 相同
- Intent URI：**留空**（有填就會優先走本機捷徑）
- 手機端的「抵達距離」「連續筆數」等在 App 的「進階參數」調整；伺服器端的 `ARRIVE_RADIUS_M`、`MAX_ACCURACY_M` 只是第二道保險，要比手機端寬鬆，不然會互相卡住。
- 想集中管理參數：用 `POST /config` 改好數字，再到 App「進階參數」按「從伺服器同步參數」，確認後按「儲存並啟動」。
  例如：`curl -X POST https://你的名字.duckdns.org/config -H "X-Secret: 密碼" -H "Content-Type: application/json" -d '{"cfg_fence_radius":120,"cfg_confirm_count":3}'`

## 用紀錄找出真正的誤差來源
每次抵達回報、手動開門、密碼錯誤都會寫進 `data/arrivals.jsonl`（不含密碼）。累積二、三十次之後：
```
https://你的名字.duckdns.org/stats?key=你的SHARED_SECRET&format=text
```
會看到成功／被拒的比例、被拒原因（誤差過大／距離太遠／定位過舊／模擬定位…）、距離與誤差的 p50／p90，
以及白話的診斷建議，例如「誤差被拒占多數 → 手機定位飄移」「抵達前定位筆數太少 → 定位頻率不足」「成功時平均偏離門口座標 → 座標需校正」。

## 伺服器端防誤開門
- 拒絕：模擬定位、定位誤差過大、定位資料過舊（超過 `MAX_FIX_AGE_SEC`）、手機時間異常、距離過遠。
- 冷卻：成功開門後 `COOLDOWN_SEC` 內，有效的抵達回報會回「已開過」（App 會因此停止重送）；手動開門則明確告知冷卻中。
- 每小時最多 `MAX_OPENS_PER_HOUR` 次。
- 密碼錯誤：依來源 IP 計算，`LOCKOUT_FAILS` 次後鎖定該 IP `LOCKOUT_MIN` 分鐘，不會把你自己的手機一起鎖住。

## 三支手機、個人化 Log 與回家規律（新增）

- 三支手機在 App 設定頁各填不同的「個人代號」，例如 `A`、`B`、`C`。每次安裝會產生固定的 `deviceId`；每趟回家使用 `sessionId` 串接事件。
- App 將重要事件先保存在自己的私有資料夾，網路可用時批次上傳至 `POST /telemetry/batch`；定位樣本包含時間、座標、誤差、距離、速度、方向、目前定位模式與使用的參數。平常待機不會持續取得 GPS；定位樣本主要來自進入圍欄後的確認階段。
- VM 將事件寫入 `data/telemetry.jsonl`，抵達／開門決策仍記錄在 `data/arrivals.jsonl`。事件 ID 用於去重，避免網路重試造成重複統計。
- 看個人化統計：`https://你的網域/analytics?key=SHARED_SECRET&format=text`；只看個人可加 `&profileId=A`。JSON 版本可供之後模型訓練。原始近期事件可用 `/telemetry?key=...&profileId=A&format=text`，注意其中含敏感定位資訊。
- 第一版提供可解釋的歷史統計：星期別成功抵達時間中位數、今天的歷史抵達時間窗、定位誤差分布、事件類型數，以及離開家附近圍欄到下次返家的總時間。這個總時間通常包含工作／外出時間，並非純交通時間。資料少於 3 次成功抵達時不會宣稱已學會規律；8 次以上才標示為較高信心。
- `predictionForToday` 是依今天星期別的歷史成功抵達時刻中位數產生的時間窗；它不是即時交通 ETA，也不表示系統知道你目前位於哪裡。App 目前只在進入住家圍欄後採集定位樣本，沒有整段通勤路線。回家時刻統計不會直接觸發或授權開門。先累積數週資料再據此調整參數；系統不會自行放寬距離／定位誤差安全門檻。
- **隱私與身分**：VM 保存精確定位座標，請限制伺服器檔案權限並定期備份／清理。三支手機可共用現行 `SHARED_SECRET`，但 `profileId` 是用來分組報表的標籤，不是獨立的身分驗證；若需要防止其中一支手機冒充別人，下一步應為每支手機設定獨立憑證。

## 維護
- 看紀錄：`sudo journalctl -u sesame -f`
- token：access token 約 30 天、refresh token 約 2 個月（社群專案文件的說法）。伺服器每天檢查，超過 10 天就更新，所以只要伺服器有開著就不會過期。
  如果伺服器停機超過兩個月，需要重新跑一次第 6 步。
- eWeLink 官方限制：同一 IP 呼叫間隔需 ≥ 500ms，5 分鐘內不超過 300 次。程式已排隊並加間隔，正常使用遠遠碰不到。

## 常見錯誤
| 現象 | 可能原因 |
|---|---|
| 授權時 `error 405 invalid code` | 授權碼只能用一次；或 Redirect URL 與後台不一致。重新從 `/ewelink/login` 開始 |
| 授權時畫面顯示「incorrect url」之類 | `.env` 的 `REDIRECT_URL` 與開發者後台不一致 |
| 開門回 `eWeLink 回應失敗：error=…` | 把 error 與 msg 抄下來；設備離線、通道號錯、參數格式不符（依設備 UIID 而異）都可能 |
| `412 invoke too much` | 太頻繁呼叫，等幾分鐘 |
| 門一直開著不放 | 該路沒有設成點動 |

## 開發／測試
```bash
npm install
npm test        # 用模擬的 eWeLink 測試授權、換 token、冷卻、密碼鎖定等流程
```
