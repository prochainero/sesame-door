# 芝麻開門 v13：Google 白名單登入整合

本包以 `sesame-v13-personal-logs.zip` 的 v13 原始碼為基礎，保留原本定位、地理圍欄、個人紀錄、語音／桌面圖示入口、eWeLink Intent、伺服器模式及 GitHub Actions 建置流程。

## 本次新增

- Android 設定頁新增 Google 登入／登出狀態。
- OAuth 使用系統瀏覽器完成，透過 `sesame://auth` 回到 App；瀏覽器只取得一次性登入碼，App 再向後端交換 7 天有效的 Bearer token。
- 未登入／登入過期時，App 的圖示／語音／背景開門入口會拒絕開門；伺服器模式的 `/open`、`/arrive`、`/config` GET、`/telemetry/batch` 可由 Node 伺服器強制要求有效 token。
- Flask 後端保留 `/health`、Google OAuth callback、白名單管理 API；新資料表使用 `app_allowed_emails` 和 `app_login_codes`，不刪除既有 SQLite 資料表。
- Node 後端只有在 `REQUIRE_GOOGLE_AUTH=true` 時，才對上述 App API 強制 Google token；其他 eWeLink 管理端點不會因此被鎖住。

## 重要：先備份，不要直接一次切換

我無法直接登入你的 VM 執行操作。請在自己的電腦解壓縮 ZIP，依以下順序部署。不要把 `.env`、Google Client Secret 或 `APP_TOKEN_SECRET` 貼到聊天或提交 GitHub。

### 1. 確認 VM 上的服務位置

```bash
sudo systemctl cat door-sesame.service
sudo systemctl cat sesame.service
```

查看 `WorkingDirectory` 與 `EnvironmentFile`，確認 Flask 後端在 `/opt/door-sesame`，並找出 Node 芝麻開門服務實際目錄。不要修改 Chatnote 或 Caddy 的 Chatnote 區塊。

### 2. 產生共用 token 簽章密鑰

只產生一次：

```bash
openssl rand -hex 32
```

把產生的值放進 **Flask 的** `/opt/door-sesame/.env`：

```dotenv
APP_TOKEN_SECRET=剛才產生的64位十六進位字串
```

同一個值也放進 **Node 芝麻開門服務的** `.env`：

```dotenv
APP_TOKEN_SECRET=同一個64位十六進位字串
REQUIRE_GOOGLE_AUTH=false
```

先維持 `REQUIRE_GOOGLE_AUTH=false`，讓新 App 可以先安裝、登入、測試。不要更改原有 `SHARED_SECRET`，也不要在聊天中公開任何密鑰。確認 Flask `.env` 權限仍為 `root:doorsesame`、`640`；Node `.env` 保留原本服務使用者的安全權限。

### 3. 上傳本包並更新 Flask 後端

將 ZIP 上傳至 VM（例如 `/tmp/sesame-google-auth.zip`），解壓縮後，假設將 ZIP 解壓縮到 `/tmp/sesame-google-auth`；專案根目錄會是 `/tmp/sesame-google-auth/sesame-v13-google-auth/`：

```bash
cd /tmp/sesame-google-auth/sesame-v13-google-auth
sudo env PROJECT_DIR=/tmp/sesame-google-auth/sesame-v13-google-auth bash /tmp/sesame-google-auth/sesame-v13-google-auth/auth-backend/deploy-auth-backend.sh
```

腳本會先檢查 `.env`，備份 `/opt/door-sesame/app.py`，更新程式、安裝必要 Python 套件、檢查語法、重啟 `door-sesame.service` 並測試本機 `/health`。它不會改寫 `.env`、Caddyfile、Chatnote 或其他服務。

若 ZIP 解壓縮後目錄名稱不同，請把命令中的 `/tmp/sesame-google-auth` 改成實際解壓縮路徑。

### 4. 更新 Node 芝麻開門伺服器

先用 `systemctl cat sesame.service` 找到 Node 服務的 `WorkingDirectory`，進入該目錄並備份：

```bash
sudo cp -a server.js "server.js.backup-$(date +%Y%m%d-%H%M%S)"
```

先做 patch 預檢（把 `/tmp/sesame-google-auth/sesame-v13-google-auth` 改成實際專案根目錄）：

```bash
sudo patch --dry-run -p2 -d /你的Node服務目錄 < /tmp/sesame-google-auth/sesame-v13-google-auth/auth-backend/patches/node-google-auth.patch
```

只有預檢顯示可套用，才執行：

```bash
sudo patch -p2 -d /你的Node服務目錄 < /tmp/sesame-google-auth/sesame-v13-google-auth/auth-backend/patches/node-google-auth.patch
```

如果 patch 顯示不符合或有衝突，**不要硬套**；先停在這裡，因為 VM 上的 `server.js` 可能與 v13 專案版本不同。更新後可在該目錄執行 `node --check server.js`，再重啟 Node 服務：

```bash
sudo systemctl restart sesame.service
sudo systemctl --no-pager --full status sesame.service
```

### 5. 建置並安裝新版 Android App

把解壓縮後的 `sesame-v13/` 專案放到 GitHub repository，推送到 `main` 或在 GitHub Actions 手動執行 **Build APK**。工作流程會產生 `door-apk` artifact。安裝到三支手機，逐支用允許的 Google 帳號登入。

App 登入後，應在設定頁看到「已登入：email」。先確認健康檢查：

```bash
curl -i https://nn0117.duckdns.org/door-sesame/health
```

此網址應回傳 HTTP 200 和 `status: ok`。接著測試登入、手動開門與定位功能；先不要把 strict mode 打開。

### 6. 最後才強制白名單驗證

確認三支手機都已安裝新版本、登入成功，而且 Node `.env` 的 `APP_TOKEN_SECRET` 與 Flask 完全相同後，將 Node `.env` 設為：

```dotenv
REQUIRE_GOOGLE_AUTH=true
```

再重啟 Node 服務：

```bash
sudo systemctl restart sesame.service
```

啟用後，舊版 App 或沒有有效 Google token 的請求，對 `/open`、`/arrive`、`/config` GET、`/telemetry/batch` 會被拒絕；其他 eWeLink 管理端點仍沿用原本驗證方式。若有任何一支手機尚未更新，先不要啟用 strict mode。

## 白名單管理

- 初始白名單從 Flask `.env` 的 `ALLOWED_EMAILS` 載入，管理員是 `APP_ADMIN_EMAIL`。
- 新白名單資料存於 `/var/lib/door-sesame/allowlist.sqlite3` 的 `app_allowed_emails` 表。
- 管理 API：`GET/POST /door-sesame/api/admin/allowlist`、`DELETE /door-sesame/api/admin/allowlist/<email>`，需帶管理員 Bearer token。新增帳號後，該帳號才可登入；移除帳號後，Node 在每次驗證 token 時也會向 Flask 核對白名單？**注意：Node 僅驗簽章與到期時間，不會即時查 Flask 資料庫，因此已發出的 token 最長可用至 7 天到期。** 若需立即撤銷，需把 token 有效期縮短或另加線上 introspection。

## 安全與限制

- Google Client Secret 只留在 VM 的 `.env`；Android APK 不包含它。
- App token 使用 HS256 簽章，7 天到期。請把同一個 `APP_TOKEN_SECRET` 保密並只放在兩個後端 `.env`。
- 本版登入 token 暫不提供 refresh token；到期後回設定頁重新登入即可。
- Android 的本機 eWeLink Intent 路徑由 App 的登入閘門控制；伺服器模式另外由 Node 端驗 token。若手機被 root 或 App 私有資料被提取，本機閘門不能取代裝置安全性。
- 此工作環境沒有你的 VM，也沒有可用的 Android Gradle 建置環境，因此我已做 Python 語法檢查及 Node `node --check`，但尚未在這裡產生或實機驗證 APK。請用 ZIP 內既有 GitHub Actions 建置。
