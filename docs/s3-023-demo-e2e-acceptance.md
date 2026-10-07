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
- 2026-10-08 受控來源失敗：以同一隔離 MySQL／Redis 加開暫時 API（18081），NTPC base URL 指向容器內不可連線的 `127.0.0.1:9`，Google Routes 外部呼叫關閉、query TTL 設 15 秒。失敗前路邊／路外活躍 snapshot 為 11／12；刷新兩者回 `FAILED`，失敗後 active snapshot 仍為 11／12、來源狀態 `FAILED`、失敗計數各 1。查詢仍引用 11／12，但因資料時間已過期顯示 `STALE`，不暴露舊位數。相同 queryId 到期後 GET 為 HTTP 410 `QUERY_EXPIRED`。暫時 API 已移除，原 DEMO 四個容器保持運行。這是 API／DB 端到端證據，不冒稱前端畫面已驗收。
- 定位拒絕／逾時有固定的繁中手動起點恢復提示，單元測試已覆蓋。實際瀏覽器中，定位逾時後按「測試座標」會清除錯誤並恢復起點；定位等待期間選測試座標後，晚到回呼不會覆蓋手動選擇。該 in-app browser 未顯示可操作的「封鎖」權限提示，因此權限拒絕碼仍只有單元證據，不能算拒絕畫面驗收。切換測試頁籤時兩頁 `document.visibilityState` 都是 `visible`，也未把該操作列為隱藏頁驗收。
- 2026-10-08 受控品質拒絕：另開暫時 API（18081）只連本機 `tools/ntpc_unmapped_stub.mjs`（18082），各來源只回 1 筆格式有效但沒有 verified mapping 的假資料，不呼叫官方 API。刷新兩來源均回 `FAILED`，路邊／路外各新增一筆 `REJECTED / FAIL` 候選及 `REJECTED` ingestion run，原因皆為 `No observations could be mapped to known parking places.`；active snapshot 保持 11／12，既有兩筆 `PUBLISHED / PARTIAL_PASS` 仍在。這驗證品質拒絕與來源連線失敗分開記錄；假來源與暫時 API 測後移除。
- 故障後正常恢復：等候原本 5 分鐘冷卻後執行 `tools/demo_flow_smoke.ps1`，兩來源首次刷新 `REFRESHED`、再次刷新 `COOLDOWN`；active snapshot 更新為路邊 15／路外 16，來源狀態皆回 `IDLE`、連續失敗數 0。新快照仍為 `PARTIAL_PASS`（路邊 1,857、路外 96 筆），先前 `REJECTED` 紀錄仍在。DEMO 四個容器持續運行。

## 重現方式

1. 在本機 `s3022demo` 保留 MySQL／Redis 資料卷及已審查 mapping；根目錄 `.env` 與 `frontend/.env` 分別提供後端及 browser-restricted Google key，檔案不入 Git。
2. 執行 `$env:GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED='true'`，再執行 `docker compose --env-file .env --env-file frontend/.env -p s3022demo up -d --build`。請勿在公開或共用網路沿用此本機 Compose 的開放埠／占位設定。
3. 開啟 `http://localhost:5173`，以搜尋地點或手動座標查詢新莊體育館；核對 500m、ETA、來源資料時間、部分覆蓋、空機率；按「更新車位」兩次核對冷卻。API 在 `http://localhost:18080`；可執行 `./tools/demo_flow_smoke.ps1` 做最小請求 smoke。
4. 單元測試：`cd backend; ./mvnw test` 與 `cd frontend; npm test; npm run build`。後端測試亦可用現有 Java 21 Docker image 執行。

故障驗收須在隔離資料庫進行，不對正式服務覆蓋來源設定。暫時 API 可用 `docker compose -p s3022demo run -d --no-deps -p 18081:8080 -e GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=false -e NTPC_OPEN_DATA_BASE_URL=http://127.0.0.1:9 -e PARKING_QUERY_STATE_TTL=15s api` 重現連線失敗與查詢過期。品質拒絕先執行 `node tools/ntpc_unmapped_stub.mjs`，將暫時 API 的 `NTPC_OPEN_DATA_BASE_URL` 改為 `http://host.docker.internal:18082`；等待既有 5 分鐘來源冷卻後，僅對 18081 建立查詢並刷新。前後比對 `source_state.active_snapshot_id`、`availability_snapshot.snapshot_status`、`ingestion_run.run_status`，最後移除暫時 API／stub。勿改動主 API 的官方來源 URL，勿把測試錯誤狀態描述成生產事故。

## 尚未通過／交接

- 補存桌面、手機、定位權限拒絕、ETA 失敗、空目錄、查詢過期、品質拒絕等畫面截圖；對照 UI 事件、API 回應及來源 run ID。不要把 API／DB 測試代替真實瀏覽器證據。
- 位置權限拒絕不得要求擷取使用者真實座標來驗收；使用者已授權在本機測試瀏覽器封鎖定位，但此 in-app browser 未提供可操作的封鎖提示。已驗證逾時及手動恢復；拒絕分支需換可控權限的測試瀏覽器再驗，不宣稱已封鎖或讀取真實位置。
- 來源連線失敗、品質拒絕、queryId 過期及故障後恢復的 API／DB 證據已補；前端失敗／過期畫面與隱藏頁停止自動刷新仍需可切換真實 `visibilityState` 的瀏覽器檢查。正常刷新已恢復隔離 DEMO 的 `IDLE` 與新 PUBLISHED，不需人工清除測試失敗狀態。
- S3-024 才處理遠端 HTTPS、同源代理、限流、備份還原與用量；S3-025 才是發布驗收。模型正式機率與完整覆蓋不在本版已完成範圍。
