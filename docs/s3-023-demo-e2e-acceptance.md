# S3-023 DEMO 端到端驗收紀錄

更新日期：2026-10-08。範圍：本機隔離 Docker project `s3022demo`，非遠端發布驗收。S3-023 尚未完全通過，Issue 保持開啟。

## 已確認的證據

- 已審查子集：路邊 1,857、路外 96 筆 verified mapping；S3-017 尚未驗收完整新莊覆蓋。查詢半徑 500m；空目錄顯示 `NO_CATALOG_RESULTS`，不將未知當零。
- 本機瀏覽器實測 Google Places 選取「新莊體育館」、手動座標、Google Routes ETA `READY`、地圖圖磚、路邊／路外目前資訊與資料時間。預測仍為 `INSUFFICIENT_DATA`，機率及模型版本皆為 `null`。手動更新後 Google ETA 未重算，連續點擊回 `COOLDOWN`，顯示下次可更新時間。
- 桌面 1280×720、手機 464×536 實際渲染檢查：地圖圖磚與標記載入，無橫向溢出；修正桌面起點／出發時間重疊及手機地圖高度。畫面曾以瀏覽器工具檢查，但本輪尚未將截圖保存為可交付檔案，不列為截圖驗收通過。
- 目的地超出支援範圍得到明確 422；已審查目錄外顯示空目錄；以測試起點 `(0,0)` 觸發 Google ETA `FAILED` 時，目前車位仍獨立顯示、沒有虛構抵達機率。瀏覽器位置權限拒絕、查詢過期與品質拒絕的完整 UI 操作尚未逐項通過。
- 驗收期間真實刷新：路邊 run 9、路外 run 10，各 1 次官方 HTTP；兩者皆發布，路邊 30,941 筆正規化、1,857 筆發布、29,025 筆未映射、59 筆相同映射 ID 重複列；路外 1,000 筆正規化、96 筆發布、904 筆未映射。兩者品質為 `PARTIAL_PASS`，不是全新莊完整覆蓋。路邊發布約 5 秒（上一輪約 48 秒），單次量測不作延遲承諾。衝突重複列的單元測試確認拒絕候選且保留既有 `PUBLISHED`。
- 分頁設定改為 `pageSize=40000,maxPages=3`，以先前官方路邊 30,941 筆／約 11.27 MB 的完整量測為依據；若來源後續超過門檻，仍應拒絕不完整刷新並重新量測，不能視為永久上限或服務承諾。正式用量與來源限制待持續監測。
- 後端 97 個測試，失敗／錯誤均 0；前端 `npm test`、`npm run build` 通過。API smoke 腳本 `tools/demo_flow_smoke.ps1` 實測 ETA `READY`、500m、`PARTIAL`、路邊 `UNKNOWN`、路外 `READY`、首次兩來源 `REFRESHED`、重複兩來源 `COOLDOWN`，預測 `INSUFFICIENT_DATA`。腳本只做一個 Google Routes 查詢、同一 queryId 的兩次更新與一次讀取；在冷卻期外，首次更新仍會各打一次官方 HTTP。

## 重現方式

1. 在本機 `s3022demo` 保留 MySQL／Redis 資料卷及已審查 mapping；根目錄 `.env` 與 `frontend/.env` 分別提供後端及 browser-restricted Google key，檔案不入 Git。
2. 執行 `$env:GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED='true'`，再執行 `docker compose --env-file .env --env-file frontend/.env -p s3022demo up -d --build`。請勿在公開或共用網路沿用此本機 Compose 的開放埠／占位設定。
3. 開啟 `http://localhost:5173`，以搜尋地點或手動座標查詢新莊體育館；核對 500m、ETA、來源資料時間、部分覆蓋、空機率；按「更新車位」兩次核對冷卻。API 在 `http://localhost:18080`；可執行 `./tools/demo_flow_smoke.ps1` 做最小請求 smoke。
4. 單元測試：`cd backend; ./mvnw test` 與 `cd frontend; npm test; npm run build`。後端測試亦可用現有 Java 21 Docker image 執行。

## 尚未通過／交接

- 補存桌面、手機、定位權限拒絕、ETA 失敗、空目錄、查詢過期、品質拒絕等畫面截圖；對照 UI 事件、API 回應及來源 run ID。不要把單元測試代替真實瀏覽器證據。
- 位置權限拒絕不得要求擷取使用者真實座標來驗收；可用受控瀏覽器權限測試，並核對手動起點恢復路徑。此輪未授權瀏覽器位置權限。
- 用隔離環境再驗證來源失敗／品質拒絕時活躍 `PUBLISHED` snapshot 不變、刷新 `FAILED` 與前端提示；單元測試已有保護，但端到端尚缺。查詢過期及隱藏頁停止自動刷新也需瀏覽器檢查。
- S3-024 才處理遠端 HTTPS、同源代理、限流、備份還原與用量；S3-025 才是發布驗收。模型正式機率與完整覆蓋不在本版已完成範圍。
