# v13 個人化 Log 修改摘要

以你上傳的 sesame-v12.zip 為基礎修改，保留既有地理圍欄、伺服器模式與 eWeLink 控制流程。

## 新增
- App 設定頁「個人代號」：三支手機分別設定 A、B、C；每次安裝產生固定 deviceId；一趟回家用 sessionId 串接。
- App 私有 JSONL 佇列：重要事件及定位確認樣本先本機保存，HTTPS 可用時批次補傳；上傳失敗保留資料並限制重試頻率。
- 結構化事件：地理圍欄進出／錯誤、定位請求、定位距離與誤差、速度與方向、採用的定位頻率、連續符合筆數、伺服器延遲、開門結果等。
- VM 新增 `POST /telemetry/batch`、`GET /telemetry`、`GET /analytics`；以 eventId 去重，JSONL 記錄檔權限設為 owner-only。
- 個別統計及共用基準：每個 profile 的歷史抵達時間窗、星期別規律、定位誤差分布；所有手機的定位誤差另有共用統計。
- 版本升為 `versionCode=13`、`versionName=v13-personal-logs`。

## 限制與安全
- 此版只在地理圍欄及附近定位確認時收集位置，不是全天 GPS 追蹤；歷史時間窗不是即時路線 ETA。
- `profileId` 是報表分組標籤，不是獨立認證。三支手機若共用 SHARED_SECRET，仍應只讓可信任手機使用。
- 精確定位會寫入 VM，請保護伺服器與備份；不要公開 `/telemetry`、`.env` 或 token 檔案。
- 開門安全判斷保持獨立；歷史預測不會授權開門或自動放寬距離／誤差限制。

## 驗證結果
- `node --check server/server.js`：通過。
- `node --check server/stats.js`：通過。
- `npm test`：26 項測試通過；eWeLink 呼叫以測試替身／模擬回應驗證，未連真實帳號或門鎖。
- 本環境沒有 Android SDK/Gradle，因此未在此處編譯 APK；專案保留 GitHub Actions 的 Build APK workflow，可用私有 repository 編譯。

## 部署注意
更新 VM 時先備份並保留既有 `server/.env` 與 `server/data/`（特別是 eWeLink token、arrivals.jsonl、config.json）。只更新程式檔，再在 VM 執行 `npm install`、`npm test`，確認後重啟服務。不要用 ZIP 內的範例檔覆蓋正式 `.env`。
