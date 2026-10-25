# 目前狀態

更新日期：2026-10-25。

## 2026-10-25 Sprint 3 離線 ML 80/10/10 首輪訓練與測試

- **資料與切分**：讀取本機 `data/private/collector-samples` 內 2026-09-24 至 2026-10-24 的 31 個 daily ZIP，包含 roadside 2,522,054 列與 offstreet 192,850 列；沒有呼叫官方 API。依時間順序用 25／3／3 個日期切分（train 80.6%、validation 9.7%、test 9.7%）；有效標籤數因 unknown 與配對拒絕而呈 train 約 77.7–78.5%、validation 約 10.4–10.9%、test 約 11.1–11.3%。as-of 與 arrival 必須在同一 split；跨界標籤排除。roadside 圈只用 train 結束前座標建立，週期特徵改為臺北時間。
- **模型與測試**：每類資料各訓練 30／60 分鐘 Logistic Regression，共 4 個本機候選 JSON。validation 依 Brier 選比較方法；四組中 offstreet 30／60m 與 roadside 30m 選 current-state persistence，roadside 60m 選 logistic。最後 test（10/22–10/24）roadside 60m Logistic Brier 0.1094，persistence 0.1169；路外 Logistic test Brier 0.0254／0.0289，均高於 persistence 的 0.0130／0.0178。其餘詳細分數、FPR、低可用比例 precision 見私有報告 `data/private/collector-samples/experiment-80-10-10-2026-10-25.md`。
- **資料限制與判讀**：全期 roadside raw 狀態有 25,113 次相鄰變化；offstreet unknown／負值／空值占 72.21%。有效樣本正例率差異大（路外約 90%、路邊 test 約 30%），需避免只看 accuracy。test 僅 3 日；單一區域、重疊候選圈與約 30 分鐘收集間隔限制泛化與短 ETA 解讀；roadside 狀態依已記錄規則離線 re-label。這是固定 lead 離線實驗，不是 ETA-aware 產品模型，也未通過 S3-020 准入。
- **產物與後續**：模型 JSON 位於已忽略的 `ml-training/models/experiment-80-10-10-2026-10-25/`，標記 `EXPERIMENT_ONLY`／`publishToOnlineSystem=false`；未接線上 `predictions`，線上仍為 `INSUFFICIENT_DATA`／null probability。重跑方式見 `ml-training/README.md`。下一步仍需確認來源觀測更新語意、擴大時間／地區資料，並在正式 ETA-aware 樣本與 S3-020 gate 下再次評估。

## 2026-10-25 Issue #94 同源規劃查詢 403 修復與驗證

- **診斷基準**：乾淨工作樹從 `main@ea7e930aff28c5fa2c32c7bd4a072b028594a43a` 切到遠端既有的 `codex/fix-parking-query-403`。修正前，本機 Docker Web `POST /api/v1/parking-queries` 無 `Origin` 回 `202` 並建立 `queryId`；相同 JSON 加 `Origin: http://127.0.0.1:18088` 回 `403 Invalid CORS request`。因此根因是 Spring MVC CORS 對代理後 request origin 的判定，不是 API 業務驗證或 Nginx 限流。
- **根因證據**：Nginx 原設定以 `$host` 傳 `Host`，丟掉 `:18088`；Spring 因此看見預設 HTTP port，無法將瀏覽器 Origin 視為同源。另一次單變因測試證明若 Nginx 把內部 HTTP 傳成 `X-Forwarded-Proto: http`，Spring `ForwardedHeaderFilter` 會正規化至預設 port 80；無 port Origin 進到 API 回 `404 QUERY_NOT_FOUND`，含 `:18088` 仍回 403。基準設定也會原樣轉送 client 提供的 forwarded host/proto，故修正同時收窄此信任邊界。
- **修正**：Nginx 保留原始 `Host` 含 port；平常 HTTP 不送 `X-Forwarded-Proto`，只有來源位於既有可信私有網段且上游值恰為 `https` 時才轉送該 scheme；若 Host 明確帶 port，才合成相同的 `X-Forwarded-Port`。清除 client-supplied forwarded host／port、prefix 與標準 `Forwarded`，`X-Forwarded-For` 改傳 Nginx 已解析的單一用戶端位址。Spring 使用 framework forwarded-header strategy。Vite 兩個明確 allowlist 不變，外部 Origin 仍拒絕，Nginx 5 次／分鐘、burst 2 與 429 JSON 未放寬。
- **HTTP 驗收**：修正後同一 Compose Web 無 Origin 與帶同源 Origin 的建立 POST 均回 `202`；Vite preflight 回 `200` 並列出明確 `Access-Control-Allow-Origin`；外部 Origin 及偽造 forwarded host/proto 均回 `403`；同源 refresh 通過 CORS 到達 API。以可信上游標頭模擬 `https://parking.example.com:8443`，Nginx 保留 Host port 並清除偽造 forwarded host／port，請求到達 API 回 `404 QUERY_NOT_FOUND`；這只驗證標頭處理，不代表 TLS 已端到端驗收。業務拒絕情境實際回 `400 INVALID_REQUEST`、`400 INVALID_COORDINATE`、`422 UNSUPPORTED_AREA`、`422 INVALID_DEPARTURE_TIME`。重置 Nginx 限流區後，前三筆非法來源 POST 回 `403`、第 4 筆回 `429 RATE_LIMITED`。
- **前端與隱私**：非 JSON 403、有穩定 error code 的 400／422／429、`QUERY_NOT_FOUND`／`QUERY_EXPIRED` 皆有繁體中文提示。指定時間空白、非法或已過去會在送出前拒絕，不再靜默改為 `NOW`；空白 label／座標也先擋下。查詢建立／刷新事件記錄 operation、HTTP status 與已知 error code；移除 Places 選取事件中的精確地點，sanitizer 過濾地址、座標、token、完整 body、key、IP 等敏感欄位。
- **驗證**：Nginx→Spring 可重跑腳本 `tools/parking_query_proxy_smoke.ps1` 修正前以同源 POST 403 失敗，修正後通過；後端完整 Maven suite 108 tests／0 failures／0 errors，前端 `npm test`、`npm run build`、Compose config quiet、API/Web image build 及 `git diff --check` 通過。Spring 測試 profile 關閉 Google／新北市外部呼叫，並以有效 query 驗證 refresh 回 `202`；Docker HTTP smoke 的 refresh 使用不存在 query，只確認同源請求通過 CORS 並到達 API，未呼叫新北市來源。既有 MySQL volume、schema、刷新冷卻、Routes 外部呼叫設定及資料契約未變。
- **尚未驗證**：沒有可用的實際 HTTPS 前置代理，故反向代理 TLS 終止至 Nginx 的端到端 HTTPS 僅由 Spring MVC forwarded-scheme 測試覆蓋；正式部署仍需確認代理保留原 Host 並來自設定的可信來源網段。見 `docker-deployment.md` 與 `open-questions.md`。

## 2026-10-25 本機 Compose 啟動與舊 MySQL volume 帳密復原

- **已確認原因**：初始 `docker compose ps` 沒有執行中的容器。使用目前 `.env` 啟動後，MySQL 本身已 ready，但健康檢查以目前 root 密碼登入既有 `taiwan-parking-forecast_mysql-data` 時收到 `Access denied`，因此 API／Web 被 `service_healthy` 依賴條件擋住。應用帳號目前密碼也與 volume 內帳密不符；依部署文件所載舊版本機初始化帳密可唯讀登入，確認是既有 volume 保留舊帳密，修改 `.env` 不會自動輪替 MySQL 內帳號。
- **資料保護與驗證**：輪替前將資料庫 dump 存於 repo 外且 ACL 限制的本機 Temp 資料夾；以新 Compose project／獨立 volume 還原後，7 張資料表名稱及逐表筆數相符，共 58 筆。原 volume 未刪除。之後將 `parking_app@'%'` 與 `root@'localhost'` 密碼同步至目前 `.env`，並移除舊 `root@'%'`。
- **目前結果**：`mysql`、`redis`、`api`、`web` 四個服務皆為 `healthy`；API 驗證 3 個 Flyway migration 並確認 schema 已是最新；`http://127.0.0.1:18088/api/health` 回 `UP`。隔離還原 volume 與 repo 外備份均保留；隔離 MySQL 容器停止後保留其 volume。
- **限制**：這是本機 Compose 啟動與備份可讀性驗證，不等同正式備份保存／災難復原演練；Google Places、Routes ETA 與停車預測狀態未因此驗收。Redis 未認證警告仍存在，Redis 沒有發布主機埠，且與本次 MySQL 啟動阻塞無關。

## 2026-10-24 進行中：起訖點 Places 與單一 Docker Compose 整合

- 此 Sprint 3 整合分支以 `origin/main` 為基礎，統一出發地與目的地 Places 搜尋。出發地預設為捷運新莊站 O18：官方站名／地址為新北市新莊區中正路 138 號；預設座標採 OSM station node 3812564402（25.03633, 121.45336）。Places host 與選取 callback 分開，使用者明確按下「規劃停車查詢」才建立新的後端查詢；前端不擴充 API payload，也不把未回應／過時的 Places 與定位結果當成有效選取。
- 出發地、目的地與出發時間變更會失效目前行程／ETA；Places 載入或 `fetchFields` 失敗、無 browser key、沒有有效 `location` 時需明確提示，不回退到公開座標欄位。定位失敗保留最後有效出發地。沒有受限 browser key 時，真實 Places 候選選取屬未驗收項目。
- 根目錄 `docker-compose.yml` 成為唯一 Compose 設定：`api`、`web`、`mysql`、`redis` 四服務；Web 預設只綁 `127.0.0.1:18088`，API／MySQL／Redis 不發布主機埠；Nginx 提供同源 `/api`。Routes 外部呼叫預設關閉，browser key 僅在前端 image build、Routes key 僅在 API runtime，MySQL 使用具 fail-fast 密碼檢查的 named volume。`.env.demo.example`、`docker-compose.demo.yml` 和舊 demo smoke 名稱已由中性設定／名稱取代。
- Docker 主機盤點後，依使用者授權停止 `taiwan-parking-forecast` 的四個容器；既有 `taiwan-parking-forecast_mysql-data` 完整保留。舊 `s3022demo_mysql-data` 的資料用途及有效備份／移轉狀態未能證實，故保留；未移除其他 project 的容器、網路、映像或 volume。
- GPT Relay 第一輪對 exact HEAD `6a3f07a7d2c8f1542eb74ef048211a2e5c721df9` 結論為 NOT PASS（1 項 P1、2 項 P2）：指出既有 MySQL volume 帳密需明確遷移、catalog smoke 需符合 Nginx 建立查詢限流，以及重設目的地時要作廢尚未完成的 Places 選取。已在工作樹修正三項並新增競態測試；更新部署文件並以新隔離 Compose project 模擬舊帳密 volume，MySQL 容器重建後新帳密可用，schema 初始化後四服務健康、同源 API 200，新增測試資料列可由新 root 讀回。隔離測試 volume 已確認 project label 後清除；實際兩個舊資料 volume 未變更。
- Catalog smoke 每次 POST 間隔至少 13 秒以遵守 5 次／分鐘與 burst=2 限流；修正後 PowerShell 解析通過。已知空資料庫因缺少已發布 snapshot／審核 mapping 而使資料斷言失敗；修正後未在具完整 snapshot 的環境重跑。第一輪三項修正已 commit／push 至 `codex/unify-place-search-compose`；第二輪對 SHA `d24c07fedc91caf3cdf96447e6a7ddf819ce441a` 回覆 NOT PASS，指出 MySQL 遠端 root 帳號未處理，以及舊 MySQL 停止時缺少可操作的備份啟動順序。
- 第二輪修正：正式 Compose 將 MySQL 新 volume 的 `MYSQL_ROOT_HOST` 固定為 `localhost`；部署文件要求舊 volume 先盤點、以目前有效舊帳密啟動 MySQL、完成備份與隔離還原後，再輪替 `parking_app`／`root@localhost` 並移除不需要的 `root@'%'` 及核對其他非 localhost root。隔離驗證確認新 volume 僅有 localhost root；舊 root 遠端帳號移除後，新 root socket 認證與應用帳號 TCP 認證通過、舊 root TCP 認證失敗；舊 MySQL 停止後用隔離的舊環境檔重啟、healthcheck 與備份測試資料均通過。完整四服務隔離 `up --build` 全部 healthy，同源 `/api/health` 為 UP、Web 僅綁 loopback，MySQL restart 後測試列仍存在；所有測試 project／volume 已按 project label 清理，實際既有 volume 未動。前端 `npm ci`、五組測試與 build、後端 102 tests、Compose config／api-web build、Git diff check 均通過。分支已推送至 `origin/codex/unify-place-search-compose`；GPT Relay 第三輪已核對精確 HEAD `1901deb1f030b2ef56c65eb23b6f027e181a1d57` 並回覆 PASS（0 項阻擋 Finding，可建立以 `main` 為 base 的待審 PR；不代表可 merge）。隔離空資料庫 smoke 因缺少已發布 snapshot 而未通過資料斷言；具完整 snapshot／審核 mapping 的 smoke、真實 Places 與 Routes、正式備份還原、遠端 HTTPS 及正式回退仍未驗收。
- 此紀錄隨 PR 驗證更新；目前依實際執行記錄分列 PASS、FAIL、BLOCKED 與 NOT RUN。隔離 image 使用空 Google browser key；本機既有 key 未確認為受限測試 key，因此 Places 互動未執行；停車模型機率仍為 null／`INSUFFICIENT_DATA`。

## 2026-10-24 進行中：即時車位地圖標記與 ParkBoss 版面參考

- 在 `feature/realtime-parking-map-markers`（base `5e11fc7db832c346dcae23975b0f488fad774a08`）新增只讀 `GET /api/v1/parking-queries/{queryId}/map-data`，使用原查詢的 500 公尺半徑、附近索引與已發布快照；只接受唯一且 VERIFIED 的來源 mapping，保留狀態、一般汽車資格、品質、新鮮度及來源時間。路線入口仍只對有位、資格已確認且資料品質／時效合格的標記開放；限制路邊 400、路外 100 個標記，省略筆數回報於回應。
- 依獨立 Code Review 修正：前端以系統取得時間執行五分鐘的本機展示時效，時間到期或分頁恢復時將標記降為過期並嘗試重新讀取；`/map-data` GET 失敗後使用短冷卻重試，與後端官方來源刷新協調／冷卻分離。目的地監聽改比較巢狀座標快照，避免同物件修改被漏判。路外標記保留場站中心作展示座標，另傳導航座標並維持入口優先；路邊導航座標不變。
- `findMapData()` 改為先查唯一 VERIFIED mapping，再依距離與停車場 ID 穩定排序及套用路邊 400／路外 100 上限；mapping 以每批最多 500 個 ID 查詢，發布快照只載入最後入選標記的觀測值。回應統計分開表示候選、唯一驗證候選、排除、上限省略及實際回傳數量。
- Vue 地圖改採 ParkBoss 風格的地圖優先資訊層級：左側浮動結果／篩選／圖例工具、覆蓋式篩選面板、可收合清單與按類型及狀態篩選；標記以穩定 ID 增量更新，讀取快照不清空標記或重設使用者地圖位置。手機版截圖發現資料類型標籤被壓成直排，已修正為水平排列並擴大篩選面板可視高度。
- 參考觀察：新北市官方頁以格位狀態色與逐格 popup 呈現車種、費率、限制及時間；官方資料集 metadata 提及 2 分鐘更新，不足以證明網頁輪詢頻率。ParkBoss 的主要互動是滿版地圖、左側浮動工具、狀態／類型篩選與清單模式；畫面內可見部分資料時間較舊，不能據此推定其 API 來源或刷新頻率。兩者的頁面與標記皆以可見 UI 觀察，REA MCP 在本工作環境不可用，故沒有網站程式碼／封包輪詢層的 REA 證據。
- `feature/realtime-parking-map-markers` 上的即時車位地圖修正已通過指定 GPT 對精確程式碼 HEAD `21f6f464fe4f805de0146b80e677e19d8091dab1` 的第三輪獨立 Code Review（PASS、0 項阻擋 Finding）。三輪確認並修正展示時效到期後 Popup／路邊摘要仍顯示舊車位、目的地變更後舊 POST／GET／Refresh 回應覆蓋畫面、Refresh 過期後 loading 卡住，以及同一 Query 的舊 GET 覆蓋較新 Refresh 結果；路外入口導航座標與 verified mapping 標記上限亦通過原始 Review。後端精準測試 22 項、前端 planner／map marker／map data lifecycle 測試、Vite production build 與 `git diff --check` 通過；前兩項 code Review 已核對遠端 SHA。真實 Google Maps 底圖、InfoWindow 瀏覽器互動及完整 DEMO E2E 仍未以本機 key／容器驗證；`map-data` GET 不觸發官方來源刷新或 Routes。此次文件記錄更新之後需再以新 exact HEAD 確認遠端 SHA 與審查狀態。
- `npm ci` 稽核回報 2 項 high severity；本次未升級或更動依賴。設計與待驗證事項分別記於 `decisions.md`、`open-questions.md`。

## 2026-10-24 進行中：S3-024 最小 DEMO 遠端部署

- 已從 GitHub fetch `feature/s3-024-minimal-demo-deploy`，在乾淨本機工作樹切換；任務分支目前包含文件交接提交 `012d24f`，base 為 `main@0ef5343`。已完整讀取 Issue #85、`docs/s3-024-codex-task.md` 與部署相關架構。
- 依 Issue 保持本機 `docker-compose.yml` 行為，新增獨立 demo Compose、同源 `/api` Nginx 代理、只綁 loopback 的 Web 埠、MySQL 持久 volume、查詢建立 IP 限流與部署環境機密設定；前端遠端 build 使用相對 `/api/v1/...`，local fallback 仍保留。
- 已更新 README、部署維運與 smoke 文件。驗證證據：本機與 demo Compose 均通過 `config --quiet`；範例環境檔只含假資料；解析 Compose 結果確認只有 web 發布 `127.0.0.1:18088`，API／MySQL／Redis 無主機埠，MySQL 掛 named volume。`npm run build` 與 demo web image build 通過；mock API 驗證 Nginx 原樣轉送 `/api/v1/parking-queries?probe=kept`，POST 建立路徑 200、200、200、429，429 JSON 為 `RATE_LIMITED` 且帶 `Retry-After: 60`；production bundle 沒有 `http://localhost:8080`。
- GitHub Actions run [#126](https://github.com/liujiawei-tw/taiwan-parking-forecast/actions/runs/40082785631) 對本次提交 `58389cd` 的 Backend Maven test、Frontend npm build、Docker image build 全部回報 failure；每個 job 約 2 秒結束，GitHub job API 未列出任何 step／runner，log endpoint 回 404。前一筆 task 文件提交 `012d24f` 與 base `0ef5343` 的 run #125／#124 也同樣在 2–3 秒內失敗，顯示這是既有 Actions 執行問題的強烈跡象；但無 step/log，具體原因仍無法驗證，應由 repository 管理者檢查 Actions／hosted runner 設定。未執行後端完整測試、政府來源 refresh、Routes 真 ETA 或遠端 smoke。
- `npm ci` 的套件稽核訊息回報 2 項 high severity vulnerabilities；本任務未改依賴，應由後續依賴安全工作確認影響與修補版本。
- 已確認的限制：目前沒有遠端主機、網域、TLS 反向代理、browser key 或 Routes server key 可供實測；因此遠端 HTTPS、真 ETA、外部瀏覽器 smoke、備份還原演練及實際版本回退都未驗收，不能稱為已部署。建議使用 Caddy 終止 TLS 是工作假設，部署者可沿用其他既有 HTTPS 反向代理。
- 分支交付遵守 Issue #85：只 Commit／Push 到 `feature/s3-024-minimal-demo-deploy`，不開 PR、不 Merge、不修改 main；GPT Review 後續依 exact SHA 進行。

## 2026-10-08 進行中：S3-023 本機端到端驗收

- 修正官方路邊全量刷新分頁上限與映射查詢效能；真實刷新兩來源各 1 HTTP，路邊 30,941 正規化／1,857 發布／59 重複映射列，路外 1,000 正規化／96 發布；皆為 PARTIAL_PASS，不宣稱完整新莊。衝突重複拒絕候選並保留既有 PUBLISHED；設定門檻依本次量測，非永久服務承諾。
- Google Places、Google Routes ETA、500m、手動更新冷卻、桌面與手機地圖、超出範圍、空目錄及 ETA 失敗已作本機驗證。預測維持空機率；97 個後端測試與前端測試／build 通過。Docker `s3022demo` 保持運行。
- 尚缺可交付截圖，以及定位拒絕、queryId 過期、來源失敗／品質拒絕與隱藏頁的完整瀏覽器證據；S3-023 尚未關閉。見 `s3-023-demo-e2e-acceptance.md`。
- 2026-10-08 補受控故障驗收：本機不可連線端點使兩來源 `FAILED`，短 TTL query 回 HTTP 410；本機未映射假來源使兩筆候選／run `REJECTED`，active snapshot 11／12 不變。定位逾時與手動恢復已在瀏覽器確認，權限拒絕只有前端單元證據；真正隱藏頁亦未通過瀏覽器驗收。冷卻後正常官方刷新成功，active snapshot 升為 15／16，來源恢復 `IDLE`，DEMO 可供人工檢查。

## 2026-10-07 最新完成：S3-022 審查子集匯入

- 共用一般汽車資格規則；新莊路邊 1,857 confirmed、164 unknown／需審查、103 排除；路外 96 confirmed、4 排除。全體 gate 仍拒絕完整匯入；新增 manifest 綁定的獨立子集 gate，不把未知寫成 verified。
- 隔離 Docker project s3022demo 匯入 1,953 筆 verified mapping（保留既有 10、新增 1,943），重跑新增 0。兩類官方來源均 PUBLISHED，500m 查詢、空目錄、邊界、未知與過期降級有可重現 smoke；94 個後端測試通過。交接見 s3-022-reviewed-catalog-demo.md。
- 修正主系統路邊空位判斷讀錯欄位：改用已驗證 parkingstatus=2／1，其他保留未知。來源重複 ID、路邊發布交易約 48 秒、77 個路外未知位數及矩形支援範圍已記錄；非服務承諾，非完整新莊覆蓋。
- 下一件 S3-023：實際 API 刷新設定／品質、Google ETA、前端桌面手機與失敗流程。S3-017 仍未完成；模型停在 S3-021 離線交接，沒有正式機率。Docker 保留供人工檢查。

## 2026-10-07 最新完成：S3-021 模型實驗收尾

- 新增離線 audit 工具與 s3-021-model-experiment-handoff.md；重現舊 baseline 的 20 列狀態／指標一致，10 個 ML workspace 測試通過。無外部資料請求、無模型產物／線上推論變更。
- 路邊 2,124 格、343 輪 raw parkingstatus 均不變，726,408 次相鄰比較零變化；50 圈均固定 label，10 正例／40 負例。1,225 對圈中 386 對重疊、15 對完全相同。滿分不足以證明時序預測。
- 已確認 train label 跨入 test 日期：路邊 30／60m 50／100 筆，路外 41／82 筆。UTC 時間特徵、全批最後 catalog 與 arrival ±15m 等限制交由 S3-019 處理；舊結果保留供重現，不作准入證據。
- 路外有 6,001 次 count／unknown 變化，但 logistic Brier 未優於 persistence。模型先停在離線實驗交接，predictions 維持空機率／模型版本與資料不足。
- 路邊完全不變的上游／collector 根因仍待查，不能據此推論現場沒有變化或主系統即時來源失效。DEMO 下一件 S3-022，先處理 S3-017 資格審查。

## 2026-10-07 規劃紀錄：DEMO 優先

本輪已閱讀 repo 與 GitHub，建立 S3-021～S3-027，調整既有 S3-017～S3-020、S3-009 的優先順序。只更新規劃，沒有產品實作、來源擷取、模型訓練或遠端部署；所有新增任務尚未驗收。

DEMO 目標：已審查展示範圍的目的地搜尋、Google Routes ETA、500 公尺路邊／路外目前資訊、資料時間、受控刷新與可解釋的預測降級。正式抵達機率仍未完成。

DEMO 前順序：S3-021 模型階段收尾 → S3-022 審查後目錄匯入 → S3-023 端到端驗收 → S3-024 遠端部署與維運 → S3-025 發布驗收。S3-017 資格分類／審查可與 S3-021 並行，是 S3-022 前置；完整覆蓋原驗收仍保留。現有 10 筆 verified mapping、174 筆 unknown eligibility 仍需處理，子集展示不得宣稱完整新莊覆蓋。

模型停點為 S3-021 的實驗證據與限制交接：已有 baseline／logistic regression 離線實驗，尚未產生合格正式模型。需查核 roadside label 時序、重疊圈與來源更新疑點。線上維持 probability=null、modelVersion=null、INSUFFICIENT_DATA。

DEMO 後順序：S3-018 匯出契約與可用資料 → S3-019 ETA-aware 樣本 → S3-020 gate → S3-026 候選訓練與獨立回測 → S3-009 產物／離線推論 → S3-027 線上推論。各件開工條件與 GitHub 連結見 sprint-3-continuation-plan.md。

部署已有 Dockerfile／Compose，但基礎設定仍使用 localhost API、暴露 DB／Redis 埠與本機占位密碼，Nginx 無 /api 代理；遠端驗收尚未完成。S3-024 需核對 override 並完成遠端設定與演練。


## 2026-10-04 最新進度：ETA-aware 模型規劃收斂

- 已開始並收斂 S3-016 資料來源覆蓋決策。新增 `docs/s3-016-data-source-coverage-decision.md`，確認新莊 MVP 即時路邊／路外以新北市官方 Open Data 為主；TDX 保留為全臺擴充與交叉驗證來源，不作目前新莊 MVP 主要即時來源。
- S3-016 的核心證據：新北市路邊可全量擷取後用 `areacode=65000050` 篩出 2,124 筆新莊格位；新北市路外可用 `AREA=新莊區` 篩 catalog，再 join 即時剩餘位。TDX 即時路邊可用單格位 ID 交叉驗證，但 TDX NewTaipei 路外基礎 endpoint 目前回空集合，TDX 歷史路外 City endpoint 不含 `NewTaipei`，TDX 歷史路邊 7 日 city-level raw data 成本遠超免費基礎會員額度且 ID filter 實測未能降低資料量。
- 已同步 `docs/data-source-strategy.md` 與 `docs/decisions.md`。本輪仍未修改產品程式、未寫入完整 catalog、未下載 TDX raw data、未訓練模型。
- 已完成 S3-017 一輪真實官方 catalog／mapping dry-run，新增 `docs/s3-017-catalog-mapping-dry-run.md`。量測結果：新莊 scoped rows 2,224、accepted candidates 2,037、missing source ID 0、duplicate source ID 0、invalid coordinates 0、unknown eligibility 174、既有 verified mappings 10、coverage 0.45%，recommendation 為 `REVIEW_ELIGIBILITY_RULES`。本輪 `confirm-write=false`，未寫入完整 catalog；目前仍不得宣稱新莊 catalog／mapping 完整。
- 2026-10-04 晚間已依 Sponsor 指示先停止測試與實作，改做後續規劃並更新 GitHub。新增 GitHub Issue [S3-016](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/77)、[S3-017](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/78)、[S3-018](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/79)、[S3-019](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/80)、[S3-020](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/81)，分別追蹤資料來源覆蓋、完整新莊 catalog／mapping 驗收、PUBLISHED 觀測歷史匯出、ETA-aware 訓練樣本產生器與保守模型評估 gate。
- `docs/sprint-3-continuation-plan.md` 已改為新的執行順序：S3-016 → S3-017 → S3-018 → S3-019 → S3-020 → S3-009。S3-009 仍是模型產物版本化與回滾，但需等 ETA-aware sample builder 與保守 gate 有證據後才適合開工。
- 這次更新是規劃與文件整理，未新增後端、前端或 ML 程式碼，也未重新下載 TDX raw data 或啟動模型訓練。
- Sponsor 明確補充正式產品要預測「從出發地到目的地時仍有車位的機率」，並提出四個模型考量：車程時間、時間週期、外部事件／環境，以及假陽性比假陰性更嚴重的不對稱懲罰。
- 已更新 `docs/model-training-architecture.md`：30／60 分鐘 lead baseline 定位為資料與流程壓力測試；正式方向改為 ETA-aware sample，讓 `arrivalAt = departureAt + etaDuration`。
- 短期模型路線改為先比較 ETA-aware persistence、同時段歷史 baseline、logistic regression；資料天數與區域增加後加入 gradient boosting；更長期再評估 survival／hazard model 處理長車程下的車位退縮曲線。
- 評估門檻補上 false positive rate、near-full subset precision、calibration 與 coverage／abstention。線上顯示「有位」應依 ETA 長短、資料年齡、near-full 與覆蓋狀態套用保守門檻；目前仍不訓練正式模型、不產生模型檔、不接入線上 `predictions`。
- 已同步 `docs/sprint-3-continuation-plan.md` 與 `docs/decisions.md`。下一步若要開工，建議新增 ETA-aware training sample builder／evaluation gate 的 Issue，而不是直接訓練模型。

## 2026-10-04 進度：TDX 新莊區即時資料低流量 spike

- Sponsor 更新 `.env` 後，TDX 官方 OIDC client credentials token 已可成功取得；驗證過程未輸出、提交或保存 secret/token。
- TDX 會員模式小量請求顯示 `Parking/OffStreet/CarPark/City/NewTaipei?$top=1` 與 `Parking/OffStreet/ParkingAvailability/City/NewTaipei?$top=1` 皆 HTTP 200，但目前回空集合；`City/NWT` 不是有效 path 參數。先前訪客模式 `City/Taipei?$top=1` 有資料，欄位含 `CarParkID`、`CarParkPosition`、`Address`、`City`、`CityCode`，未在該筆樣本看到 `Town`／`District`。
- 依 data.gov.tw 指向的 TDX on-street operation 名稱做少量候選路徑測試，尚未找到可用路徑，且第 6 次快速請求觸發 TDX 5 次／分鐘限制；後續需查到官方 OAS 精確 path 後再重試，不能靠猜測路徑。
- 已用新北市官方 Open Data API 驗證「先抓新北市，再本地篩新莊」可行：路邊全量 30,941 rows／約 11.27 MB，其中 `areacode=65000050` 新莊 2,124 rows；路外 catalog 1,000 rows／約 390 KB，其中 `AREA=新莊區` 100 rows，可用這些 ID join 即時 availability 1,000 rows／約 36 KB，得到新莊路外即時 100 rows。
- 初步結論：目前不能宣稱 TDX 能直接「只抓新莊區」即時停車資料；MVP 可行路線是使用新北市官方資料，以縣市層級擷取加本地 `areacode`／`AREA`／catalog mapping 篩新莊。若未來改用 TDX，仍需先確認 TDX on-street 精確 endpoint、NewTaipei 覆蓋狀態，以及是否支援可降低用量的 `$filter`。

## 2026-10-04 進度：TDX 歷史資料 2026-09-17..2026-09-23 metadata 驗證

- 已用 TDX 官方歷史停車 OAS 確認 endpoint：路邊格位為 `/api/historical/v1/Historical/Parking/OnStreet/ParkingSpotAvailability/City/NewTaipei`；路外 City availability endpoint 的 City enum 不含 `NewTaipei`。
- TDX `Meta=true` 顯示新北市路邊格位 2026-09-17 至 2026-09-23 全市資料為 35,480,193 rows／5,403,073,037 bytes；2026-09-23 單日為 5,711,400 rows／869,765,771 bytes。
- 依 `tdx-data-and-cost.md` 既有歷史服務資料量估算規則，2026-09-23 單日約 869.77 MB，約 43.49 點；7 日約 5,403.07 MB，約 270.15 點。這只是 metadata 與文件規則估算，不是會員中心實扣。
- 已用本機既有 TDX 歷史 CSV 驗證新北市 Open Data `id=153854` 可對上 TDX `ParkingSpotID=153854`；但對 TDX 歷史 API 試打 `$filter=ParkingSpotID eq '153854'` 時，API 回傳未篩選的前 5 筆，表示此 endpoint 目前不能依 ParkingSpotID 做 server-side 篩選來降低下載量。
- 本輪未下載完整 TDX raw data，因為下載全新北後本地篩新莊會明顯超過目前免費基礎會員 3 點/月額度，需 Sponsor 另行明確確認。已在 `data/private/collector-samples/tdx-newtaipei-xinzhuang-2026-09-17_2026-09-23-metadata.md` 保存 private metadata 報告；該目錄不進 Git。
- TDX 歷史路外 NewTaipei 查詢回 400，訊息列出可接受城市為 Taoyuan、Tainan、Kaohsiung、Keelung、YilanCounty、HualienCounty；因此 2026-09-17 至 2026-09-23 的新莊路外訓練資料不能由此 TDX 歷史 City endpoint 取得。

## 2026-10-04 進度：TDX 與新北市即時路邊同格位交叉比對

- 已用同一個新莊路邊格位 ID `153854` 比對 TDX 基礎即時 API 與新北市 Open Data API。TDX 正確 endpoint 為 `/api/basic/v1/Parking/OnStreet/ParkingSpotAvailability/City/NewTaipei`，並可用 `$filter=ParkingSpotID eq '153854'` 回單筆；這點與歷史 API 不同，歷史 API 的同欄位 `$filter` 會被忽略。
- TDX 即時回應：`ParkingSpotID=153854`、`ParkingSegmentID=R27`、`ServiceStatus=2`、`SpotStatus=254`、`DeviceStatus=1`、`ChargeStatus=1`、`DataCollectTime=2026-10-03T23:56:41+08:00`。
- 新北市 Open Data 同格位回應：`id=153854`、`roadid=R27`、`cellid=20`、`cellstatus=Y`、`parkingstatus=3`、座標 `25.038868,121.452126`、`areacode=65000050`。
- 初步判讀：兩邊 source ID 與 road/segment 可對上，且都不是 confirmed available；但 raw 狀態碼不同，不能把 TDX `SpotStatus=254` 與新北市 `parkingstatus=3` 當作完全相同碼表，只能在 canonical layer 保守歸為非 confirmed available／需保留來源原碼。

## 2026-10-04 進度：TDX 與新北市即時路外同停車場比對

- 已選新莊路外停車場 `050193`（捷運迴龍站停車場）做同 ID 比對。新北市 catalog 回 `AREA=新莊區`、`TOTALCAR=254`、`SERVICETIME=0~24時`，即時 availability 回 `AVAILABLECAR=55`。
- 同一 ID 對 TDX 基礎路外即時 endpoint `/api/basic/v1/Parking/OffStreet/ParkingAvailability/City/NewTaipei?$filter=CarParkID eq '050193'` 查詢，HTTP 200 但 `ParkingAvailabilities=[]`；TDX 路外靜態 `/api/basic/v1/Parking/OffStreet/CarPark/City/NewTaipei?$filter=CarParkID eq '050193'` 也 HTTP 200 但 `CarParks=[]`。
- 初步判讀：這不是「剩餘位數不一致」，而是 TDX 目前在 NewTaipei 路外 City endpoint 沒有提供可比對的靜態／即時資料；新莊路外 MVP 仍應以新北市官方 API 作為來源，TDX 僅保留為未來覆蓋擴充待驗證來源。

## 2026-10-03 最新進度：S3-015 查詢半徑改為 500 公尺

- Sponsor 最終指示將目的地周邊範圍改為 500 公尺。主系統 `ParkingQueryService` 固定查詢半徑已改為 500m；前端地圖圓形與 fallback 文案改讀後端 `area.radiusMeters`，未有查詢結果時預設顯示 500m。
- `ml-training` 設定 `roadsideRadiusMeters` 已改為 500，並重跑 2026-09-24 至 2026-10-02 共 9 天私有 dry-run／baseline report。Roadside candidate centers 仍為 50，500m 圓內格位數最少 33、平均 106.8、最多 194。
- 500m roadside label dry-run：30 分鐘 lead train 10,750／test 3,900 usable labels，正例 2,150／780；60 分鐘 lead train 10,900／test 3,850 usable labels，正例 2,180／770。
- 500m roadside baseline：30／60 分鐘 lead 的 `current_state_persistence` Brier 仍為 0.0025、log loss 0.0513；train/test positive rate 約 0.20。這比 300m 的約 0.08 高、比 1000m 的約 0.78 低；後續仍需檢查使用者是否接受 500m 距離成本、以及模型 calibration 是否有產品價值。
- 這仍不是正式模型訓練，沒有產生模型檔，也沒有接入線上 `predictions`。

## 2026-10-03 進度：S3-014 roadside baseline 離線回測

- 已在 `ml-training` 加入 roadside 候選目的地圓 baseline sample builder，feature side 只使用 `asOf` 當下 circle 內 confirmed available 比例與是否至少一格 confirmed available；label side 只使用 `arrivalAt` 附近是否至少一格 confirmed available。S3-015 後目前設定半徑已改為 500 公尺。
- 真實 9 天樣本重跑 S3-008 baseline report 後，roadside 30 分鐘 lead 有 train 10,750／test 3,900 labels，60 分鐘 lead 有 train 10,900／test 3,850 labels；兩者狀態皆為 `EXPERIMENT_ONLY`。
- Roadside 30／60 分鐘 lead 的 `current_state_persistence` Brier 為 0.0025、log loss 為 0.0513；`entity_positive_rate` 在此 9 天與 50 個 deterministic circles 上也達到 1.0000 accuracy。這是離線實驗證據，不是服務承諾；S3-014 原始 300m 正例比例約 8%，S3-015 改 500m 後約 20%，且樣本日期／區域仍太少，需補更多日期、區域泛化、calibration 與門檻審查。
- 這批工作沒有產生正式模型檔，也沒有接入線上 `predictions`。`is_available=false` 的語意仍是「不確認有一般可停空位」，不得寫成保證沒有空位。

## 2026-10-03 進度：S3-013 roadside 離線 re-label 與 dry-run 重跑

- 已在主系統 `ml-training` 加入 roadside 離線 re-label：對既有舊 collector ZIP 讀取 `parking_status_raw`，套用 `ntpc_roadside_confirmed_available_status_2_else_false_v1`，不改寫原始 ZIP/CSV，不提交 row-level label。
- 真實 9 天樣本重跑 S3-007 label dry-run 後，roadside offline re-label rows 為 728,532。30 分鐘 lead 有 train 10,750／test 3,900 usable labels，正例分別 860／312；60 分鐘 lead 有 train 10,900／test 3,850 usable labels，正例分別 872／308。

## 2026-10-03 進度：S3-012 collector roadside confirmed availability label

- 已在獨立 `parking-data-collector` repo 合併 PR #1，將 roadside `is_available` 改為保守二元 label：只有 `parkingstatus=2` 視為 confirmed available；`1/3/5/other` 皆為 `false`，但以 `availability_state` 與 `not_confirmed_reason` 保留 occupied、restricted、unknown 等語意。
- 新版 collector `label_rule` 為 `ntpc_roadside_confirmed_available_status_2_else_false_v1`。這裡的 `false` 表示「不確認有一般可停空位」，不得在模型報告或產品文案中說成保證沒有空位。
- collector 文件已同步更新 `README.md` 與 `docs/data-contract.md`，data contract 升至 v0.4；Apps Script 與 Java backup collector 均已更新，並新增 Java 單元測試鎖定 `1/2/3/5/0` label 行為。
- 主系統已在 S3-013 對既有 2026-09-24 至 2026-10-02 ZIP 做明確離線 re-label，並重跑 S3-007 label dry-run 與 S3-008 baseline。新版 Apps Script collector ZIP 仍可作後續驗證，但不再是解除舊樣本 provisional label 的唯一途徑。

## 2026-10-03 最新進度：S3-010 路邊狀態碼驗證

- 已開始 roadside label 問題收斂。新增 `tools/roadside_status_validation.py`，可讀取新北市官方 CSV 全檔 endpoint 與本機 collector ZIP，輸出 aggregate 狀態碼驗證報告；不保存官方 CSV 原文、不輸出 row-level collector 資料。
- 已用官方 CSV 全檔驗證 31,560 rows，新莊區 `areacode=65000050` 為 2,149 rows。新莊主要組合為 `parkingstatus=3/cellstatus=Y` 1,733、`3/N` 186、`1/Y` 145、`2/N` 49、`5/Y` 30、`5/N` 6。
- 已用 9 天 collector roadside 樣本驗證主要組合：`3/Y` 586,530、`3/N` 63,112、`1/Y` 49,735、`2/N` 16,807、`5/Y` 10,290、`5/N` 2,058。舊規則 `parkingstatus=0 -> true` 不適合這批資料，因為 9 天樣本沒有任何 `parkingstatus=0`。
- 已用本機 TDX 新北市路邊格位歷史 CSV 兩份交叉比對，共 8,422,511 rows；`SpotStatus=1` 5,744,482、`SpotStatus=2` 1,469,909、`SpotStatus=254` 1,208,120。TDX 樣本支持 `1/2` 作為使用中／空位語意，但不能直接證明新北市 `parkingstatus=5`。
- 新增 `docs/s3-010-roadside-status-validation.md`，建議下一步在 `parking-data-collector` 導入 versioned label rule：`1=OCCUPIED`、`2=AVAILABLE`、`3=RESTRICTED_OR_CLOSED`、`5=UNKNOWN_OR_SPECIAL`；`3/5` 不作一般訓練 label。
- 新增 `docs/ml-canonical-schema.md`，定義跨來源 ML 訓練 canonical observation、availability state、label rule version 與 source-specific mapping contract；未來不同地方政府 API 不應直接混用 raw status code。

## 2026-10-03 進度：S3-008 baseline 與第一版模型實驗

- 已開始 S3-008。新增 `ml-training/scripts/run_baseline_experiment.py` 與 `parking_ml.baseline_experiment`，可用 S3-007 相同時間切分與 label pairing 規則產生 aggregate baseline／簡單模型回測報告；不輸出 row-level label、不產生模型檔、不接線上 API。
- 真實 9 天離線報告已產生於 `data/private/collector-samples/baseline-experiment-2026-09-24_2026-10-02.md`，該路徑為 ignored，不提交 Git。公開摘要寫入 `docs/s3-008-baseline-experiment.md`。
- Offstreet 30 分鐘 lead：train 8,516 labels、test 3,189 labels；`current_state_persistence` Brier 0.0152、log loss 0.0928。Offstreet 60 分鐘 lead：train 8,638 labels、test 3,148 labels；`current_state_persistence` Brier 0.0182、log loss 0.1028。
- Roadside 已在 S3-013 透過離線 re-label 產生 usable labels，並在 S3-014 補上候選目的地圓 baseline 離線回測。S3-015 後目前半徑設定為 500 公尺；30／60 分鐘 lead 皆可評估，但仍只是 `EXPERIMENT_ONLY`；目前仍不得輸出線上機率，也尚未進 S3-009 模型產物版本化。

## 2026-10-03 最新進度：S3-006 collector 訓練資料品質盤點與匯入規格

- 已開始 S3-006。新增 `tools/collector_quality_inventory.py`，可讀取 `parking-data-collector` daily CSV／ZIP 匯出根目錄或單一 ZIP，輸出只含摘要的 Markdown 品質報告；工具不複製原始 CSV／ZIP、不輸出 row-level 資料。
- 已讀取 sibling repo `parking-data-collector` 的 README、`docs/data-contract.md` 與 Apps Script 欄位規則，確認 `collected_at_utc` 是收集輪次開始時間，`source_api_response_at_utc` 是 API response time 或 fallback，兩者都不是官方逐筆觀測時間。
- 已從 Google Drive `parking-training-data/exports` 取得 2026-09-24 至 2026-10-02 共 9 包 daily ZIP 做私有盤點；`2026-09-23` 因資料不齊先排除。原始 ZIP、CSV 與完整私有報告放在 `data/private/collector-samples/`，不提交 Git。
- 新增 `docs/s3-006-collector-quality-inventory.md`，記錄欄位語意、拒絕原因、匯入規格草案、9 天真實樣本摘要與 train/test 日期切分。盤點結果：roadside 728,532 rows、offstreet 50,516 rows，無缺日、parse error、duplicate rows 或 duplicate observations；roadside reliable label 仍為 0%，暫不開始 S3-008 模型訓練。S3-007 label generation dry run 採 train=`2026-09-24..2026-09-30`、test=`2026-10-01..2026-10-02`，不可 row-random split。
- 新增 `ml-training/` 離線模型訓練 workspace 骨架與 `docs/model-training-architecture.md`。目前只包含訓練設定範例、日期切分 helper、計畫預覽 script 與單元測試；不訓練模型、不發布模型產物、不接線上查詢。

## 2026-10-03 進度：S3-007 訓練標籤 dry-run

- 已開始 S3-007。新增 `ml-training/scripts/generate_label_dry_run.py`、collector ZIP reader 與 label dry-run pairing 程式，輸出 aggregate Markdown 報告，不輸出 row-level labels。
- Label 規則固定 `asOf` 只供 feature side 使用，`arrivalAt = asOf + leadTime` 附近觀測只作 label；目前 lead time 為 30／60 分鐘，label window 為 ±15 分鐘。
- 真實 9 天 dry-run：S3-015 後 roadside 半徑設定為 500m，50 個 candidate centers 內最少 33 格、平均 106.8 格、最多 194 格。roadside 30 分鐘 lead 可用 train 10,750、test 3,900；60 分鐘 lead 可用 train 10,900、test 3,850。offstreet 30 分鐘 lead 可用 train 8,516、test 3,189，60 分鐘 lead 可用 train 8,638、test 3,148。
- 新增 `docs/s3-007-label-generation-dry-run.md`，記錄 label definition、拒絕原因、dry-run 摘要與限制：目前仍不得輸出線上機率；roadside 下一步是專用 baseline／模型與門檻評估。

## 2026-10-02 最新進度：S3-005 官方來源端到端刷新寫入與發布量測

- 已開始 S3-005，新增 `RefreshE2eMeasurementCommandRunner` 與 `parking.refresh.measurement.command`，預設 `enabled=false`，只在明確開啟時執行一次性端到端量測。command runner 會直接呼叫官方 refresh service，並量測 HTTP、頁數、response bytes、normalize、品質量測、DB 發布交易、`PUBLISHED` snapshot 查詢讀取與總耗時。
- `RealtimeSourceRefreshService.RefreshExecutionResult` 已補上 `pageCount`、`responseBytes` 與 `RefreshStageTimings`，讓路邊／路外 refresh 結果可重現量測摘要。DB 的 `quality_summary` 維持品質摘要，不把未完成或易混淆的耗時資訊塞進品質欄位。
- 已用一次性 Docker Maven 容器跑真實新北市官方 Open Data E2E 量測，不啟動或停止既有 Docker Compose，不保存原始 JSON、完整 request／response body 或敏感設定。
- 路邊 `NTPC_ROADSIDE_SPOT`：1 HTTP request／1 page／11,270,904 bytes／30,941 normalized records；發布成功，7 筆已 mapping、30,934 筆 unmapped、0 failed observations；HTTP 4,692 ms、normalize 39 ms、measurement 1,577 ms、publish transaction 3,044 ms、total 9,353 ms；讀回 `PUBLISHED` snapshot 53 ms，query status 為 `UNKNOWN`。
- 路外 `NTPC_OFF_STREET_AVAILABILITY`：2 HTTP requests／2 pages／50,608 bytes／1,405 normalized records；發布成功，3 筆已 mapping、1,402 筆 unmapped、0 failed observations；HTTP 872 ms、normalize 8 ms、measurement 1,544 ms、publish transaction 577 ms、total 3,003 ms；讀回 `PUBLISHED` snapshot 40 ms，query status 為 `READY`。
- 新增 `docs/s3-005-refresh-e2e-measurement.md`，把兩輪量測指令、結果、拒絕／失敗分流、設定依據與限制寫清楚。缺頁拒絕由 `NtpcRealtimeSourceRefreshServiceTest` 覆蓋；品質拒絕不覆蓋既有 `PUBLISHED` snapshot 由 `AvailabilitySnapshotPublishingServiceTest` 覆蓋；重複、來源欄位異常與 mapping 覆蓋則承接 S2-HARDEN-003 的來源量測文件。
- 驗證：Docker Maven 精準測試 `NtpcRealtimeSourceRefreshServiceTest,SourceRefreshCoordinationServiceTest,AvailabilitySnapshotPublishingServiceTest` 通過 12 tests／0 failures／0 errors；完整後端 Docker Maven `mvn test` 通過 88 tests／0 failures／0 errors／0 skipped。

## 2026-10-01 最新進度：S3-004 Catalog 匯入管理命令

- 已開始 S3-004，補上 NTPC catalog dry-run 後的受控寫入路徑。新增 `CatalogImportService`，只有 dry-run recommendation 為 `READY_FOR_IMPORT_REVIEW` 時才建立 `parking_place` 與 `source_mapping`，且既有 source mapping 只會跳過，不會覆蓋或改寫。
- `NtpcCatalogDryRunService` 的結果現在保留正規化後的 `CatalogSourceRecord`，讓同一份 dry-run 報告可直接餵給後續匯入；新莊仍只是第一個 `scope`，未來新增地區應新增 scope 與 adapter，不複製新莊專用流程。
- 新增 `NtpcCatalogImportCommandRunner` 與 `parking.catalog.ntpc.command` 設定。預設 `enabled=false`、`confirm-write=false`，啟用命令後也會先輸出 dry-run 摘要；只有明確設定 `confirm-write=true` 且來源 traversal complete 時才嘗試寫入 DB。
- 目前完成的是「管理命令與保護條件」的程式基線，尚未執行真實官方 dry-run 審查，也尚未把完整新莊 catalog 寫入資料庫。不得宣稱新莊 catalog 已完整；覆蓋狀態仍以既有 7 筆路邊、3 筆路外 verified mapping 為準。
- 驗證：Docker Maven 精準測試 `CatalogImportServiceTest,NtpcCatalogDryRunServiceTest` 通過 5 tests／0 failures／0 errors。Windows `mvnw.cmd` 仍有既有 wrapper 啟動問題；本輪後端驗證使用 Docker Maven。

## 2026-10-01 進度：S3-003 預測降級契約

- S3-003 已補線上 `predictions` 的無模型降級契約：目前無正式模型時固定回 `INSUFFICIENT_DATA`、`modelVersion=null`、`probability=null`，並帶 `generatedAt`、`targetArrivalAt`、`inputSnapshotVersion`、`dataAgeSeconds` 與 `reasons`。
- 拒絕原因已拆分為模型未載入、回測未驗證、ETA 不可用、沒有目錄、catalog 覆蓋不足、沒有即時快照、快照過期、未知可用狀態、缺觀測與模型輸入未就緒。
- 目前車位、ETA、刷新與預測降級分開：模型不可用或資料不足不會覆蓋既有 `PUBLISHED` snapshot，也不會阻止 `roadside`／`offStreet` 顯示目前觀測。
- 已新增 `docs/s3-003-prediction-degradation.md`，並同步 API 契約、決策與待確認事項。正式模型產物、線上推論程序、模型版本儲存與自動回滾仍未完成。
- 本輪已通過 `git diff --check`、精準 Docker Maven 測試 `ParkingQueryControllerTest,ParkingQueryServiceTest,RedisParkingQueryStateStoreTest`，以及完整後端 Docker Maven 測試 85 tests／0 failures／0 errors／0 skipped。Windows `mvnw.cmd` 仍是既有不可用狀態，後端測試維持使用 Docker Maven。

## 2026-10-01 後續規劃：S3-004 至 S3-009

- 已建立 GitHub Issues S3-004 至 S3-009，規劃完整 catalog／mapping 匯入、官方來源端到端發布量測、collector 訓練資料品質盤點、訓練標籤產生、baseline／第一版模型實驗，以及模型產物版本化與離線推論。
- 已新增 `docs/sprint-3-continuation-plan.md`，記錄下一批 Issue 順序與模型訓練路線。
- `parking-data-collector` 只作離線訓練資料來源；不服務使用者查詢，也不取代主系統 PUBLISHED snapshot。模型訓練第一步是資料品質盤點，不直接訓練或接線上 API。

## 階段與角色

目前為 Sprint 3 預測前置與模型訓練資料治理。Sponsor 兼開發者是使用者；AI 擔任產品／架構／資料工程協作與工程審查夥伴。Sprint 2 主系統基線、官方來源小樣本、查詢契約、前端 Maps／Places 初版、可見頁受控刷新與 Sprint 3 進入條件已完成；Sprint 3 目前聚焦完整 catalog／mapping 治理、官方來源端到端量測、collector 訓練資料品質盤點、訓練標籤、baseline／第一版模型實驗與模型產物版本化。`parking-data-collector` 仍是獨立離線 ML 訓練資料來源，不服務使用者查詢，也不取代主系統 PUBLISHED snapshot。

## 已完成

- 確認 TDX 優先、地方政府 API 後續接入，資料需先統一化。
- 核查 TDX 官方停車基礎、進階與歷史服務的頁面定價，以及免費基礎會員點數／速率限制；記錄於 `tdx-data-and-cost.md`。
- Sponsor 確認帳戶每月可用 3 點、每天可投入約 5–10 小時；已購買的兩份歷史檔實扣新北市 0.56 點、桃園機場 0.12 點，合計 0.68 點。另已申請新北市 2024-04-01 至 2026-07-31 歷史資料，TDX 尚在產製；2026-08-01 至 08-31 的完整檔已產製，申請頁顯示 462.6 MB、預估 23.23 點，尚未確認購買。
- 查核 TDX 停車歷史資料清單及 API 的單次 7 日日期上限；制定小範圍 CSV 優先的取得計畫，詳見 `historical-data-plan.md`。Sponsor 已自行下載新北市與桃園機場歷史資料，並申請較長區間的新北市資料。
- Sponsor 已下載新北市路邊格位 2026-08-09 歷史資料，放在專用路邊原始資料夾；ZIP MD5 已核對。單日 CSV 有 2,938,401 列、31,328 個格位 ID，初步盤點見 `new-taipei-data-profile.md`。此筆實扣 0.56 點。
- 新增購買並歸檔新北市路邊格位 2026-08-27 ZIP（頁面預估 0.97 點）及路邊路段 2026-08-25～31 ZIP（頁面預估 1.02 點）；兩筆實扣待核對；ZIP 已解壓並完成初步 ID 篩選。格位 8/27 以目前新莊 ID 對上 359,910 列；路段七日對上 341,473 列，但車種剩餘位欄位全零，須查明語意。詳見 `new-taipei-historical-inventory.md`。TDX 入門指引確認路邊路段與路外停車場是不同資料類型。
- Sponsor 確認第一版同時做路邊車格與路外停車場預測，各先做一個試點；兩條資料與預測流程分開驗證。
- Sponsor 已取得桃園國際機場路外停車剩餘位歷史資料（2026-09-01 至 09-22）。已整理至路外專用原始資料夾，初步盤點 22 個 CSV、44,254 列、5 個停車場 ID；資料存在車位類型與分區重複計數風險。此筆實扣 0.12 點；詳見 `airport-data-profile.md`。
- 建立文件優先的本機 Git 專案與決策、需求、待確認事項。
- 發現新北市官方開放資料平臺提供路邊格位與公有路外停車場即時資料，可評估以低頻、目標區域擷取來自建未來歷史；來源對應與介接限制待核對，詳見 `data-source-strategy.md`。
- Sponsor 選定新莊區作第一個試點。獨立 `parking-data-collector` 已轉為 GitHub Actions 每 30 分鐘排程擷取、每日 CSV 存 Google Drive，並每日匯出 ZIP；Sponsor 確認排程已運作。30 分鐘依 2026-09-24 唯讀檢查的 collector `collect.yml` 與 README；實際成功執行率待核對。舊本機 H2/API 程序可能仍在執行，但不代表目前 collector 分支的架構；主專案即時 API 尚待設計。首次資料驗證路邊 2,124 格、路外 149 場。
- Sponsor 早期曾討論路外剩餘汽車位數、路邊區域可用格數與 10／30／60 分鐘時距；後續正式產品目標已改為 Google ETA 當次抵達時刻的有位機率，固定分鐘數僅可作離線回測分組。路邊範圍已定為目的地直線 300 公尺；詳見最新決策。

- 已建立 `ml-source-contract.md` 草案。交通部標準確認 TDX 格位狀態碼與路段車種碼；新北市官方頁確認路外 `-9` 代表暫無即時資料，但路邊 `parkingstatus`／`cellstatus` 數值碼表仍未在資料集頁找到。TDX 歷史清單無新北市路外項目，路外改用 collector 累積的新北市政府即時觀測。ML 暫停於資料契約，下一階段可並行規劃後端查詢與即時資料流。

## 2026-09-23～24 最新討論：主系統下一階段

- Sponsor 同意先規劃目的地查詢、即時資料更新與顯示 API；ML 暫停在 `ml-source-contract.md` 的來源統一規格與訓練前檢查，collector 繼續累積資料。這表示同意進入設計討論，不代表主應用程式或模型已開始編碼。
- 第一個可展示增量提案：新莊目的地附近同時列出路邊與路外選項，顯示最新有效觀測、來源時間與有效／過期／未知狀態；預測待資料標籤及跨日品質確認後接入。
- 架構提案：用戶即時查詢由主系統獨立取得與保存最新觀測，資料過期時可在最低間隔及預算下受控刷新；獨立訓練 collector 只供模型訓練，產出每日 CSV 到 Google Drive，不作為用戶查詢來源或主系統執行元件。兩個系統對同一外部平台的流量只在營運層面分開量測與評估，主系統刷新不得依賴訓練排程。
- 查詢 API 暫以目的地座標、搜尋範圍、預計抵達時間為輸入；預設目的地／地圖選點是展示介面候選，文字地址搜尋需要另選地理編碼來源。Redis 先視查詢負載與多實例協調需求決定；主系統的受控共用刷新及每來源最低呼叫間隔，才是控制用戶查詢上游呼叫數的核心。上述皆為待確認設計提案，詳見 `architecture.md`。

- Sponsor 追問為何查詢只讀本地，以及如何同時提高新鮮度並降低 API 呼叫。修正方案：一般查詢讀共用最新觀測，資料過期時可在單一擷取者、最低刷新間隔與預算限制下觸發一次共用刷新；回應標示來源觀測／系統取得時間及過期狀態。Redis 可協助多實例共用鎖與快照，但不是新鮮度保證。此為設計提案，門檻待實測；詳見 `architecture.md`。

- Sponsor 選定用戶查詢資料在系統成功取得後超過 **5 分鐘**先標示『過期』，屬 MVP 待實測門檻。路邊／路外來源頁分別標示每 2／3 分鐘更新。已發現 collector README 把 `collected_at_utc` 寫成『抓完批次時間』，但程式於 `collectOnce()` 呼叫 API 前建立時間戳，實際為收集輪次開始時間；這不能作為官方更新時間。主系統需另記全部頁面成功發布時間。
- 已建立 `realtime-query-design.md` 草案，將刷新觸發間隔與 5 分鐘過期顯示門檻分開，規劃多用戶共用刷新、完整快照發布及實際 HTTP 請求數統計。此處早期以訓練程式推算的請求量已非其目前設定，也不應作主系統容量估算；離線收集器的歷史與現況另記 `training-collector-operations.md`。主系統請求量須獨立量測。

## 下一個可執行步驟

**優先：**先把主系統 MVP 的查詢輸入、回應欄位、資料過期規則、路邊範圍定義與主系統獨立的即時擷取／受控刷新策略寫成可驗收的 Sprint 1 設計。訓練 collector 的資料不作為用戶即時查詢來源。

1. 檢查 GitHub Actions 收集成功率、Google Drive 每日 CSV 的缺口與新莊區筆數；核對 `parkingstatus`／`cellstatus` 語意及路外負值資料狀態。
2. 等待新北市 2024-04-01 至 2026-07-31 申請完成；補記該筆申請頁顯示的預估點數、購買狀態、實扣點數、檔案大小與 MD5。完整 8 月資料預估 23.23 點，先不把它納入免費額度方案。
3. 以 Google ETA 當次抵達時刻對齊路邊與路外預測目標；離線檢查不同時距的模型品質，不以固定分鐘數替代 Google ETA。資料欄位與狀態碼仍須核對。
4. 依官方 Swagger 列出 MVP 候選即時停車端點，並核對新北市開放資料欄位和來源 ID 能否映射到 TDX。
5. 用實際點數與回應大小做月度擷取預算，決定靜態資料下載與動態資料擷取策略。
6. 確認 MVP 的查詢介面與驗收條件，才開始 Sprint 1 編碼。

## 不可混淆

TDX 網頁公開定價、Sponsor 回報的 3 點／月、兩筆實扣點數與完整 8 月檔的預估 23.23 點是已知資訊；長區間申請的點數、完整 8 月檔是否已購買、目前實際剩餘點數和即時端點回應大小尚未查證。最初兩份已下載的歷史資料已有初步品質盤點；新增兩份新北市 ZIP 已解壓並初步盤點，頁面預估點數不等於實扣點數；新莊歷史路段 ID 與車種剩餘位欄位仍需核對。官方欄位語意尚未完整核對。Sprint 計畫不等於功能已完成。

- 2026-09-24 釐清 collector 的 51,840 次／30 天是以每輪約 6 個 HTTP 請求推算的理想連續運作量，約每分鐘 1.2 次，不是實測量，也不能單憑月總量判定違反「高頻」門檻。新北市開發指引要求大量、高頻介接先聯繫平台，但未列數值界線。Sprint 1 暫提主系統採需求觸發的共用刷新、靜態清單較低頻更新；實際間隔與是否預熱待量測。主系統與 collector 的合併請求量需一起評估，詳見 `realtime-query-design.md`。

## 2026-09-24 新提案：依需求熱度刷新

Sponsor 提議非熱門區域 60 分鐘刷新、熱門區域 5／3 分鐘刷新。架構評估：需求分層可行，但 60 分鐘資料仍按既定 5 分鐘門檻顯示過期；若無首查延遲要求，無人查詢時不刷新更省上游呼叫。現有路邊來源可按 areacode 篩新莊；路外動態目前抓全市，不能直接假設只刷新熱門地區即可節省該來源成本。候選先用需求觸發、多人共用刷新，5 分鐘最低間隔起步；3 分鐘熱點加速及 60 分鐘預熱需用實測與合併請求預算決定。見 `realtime-query-design.md`；尚未定案，未進行 coding。

- 2026-09-24 Sponsor 回覆 OK，同意依查詢需求調整主系統刷新頻率的方向；動態最低間隔、是否做 60 分鐘預熱、熱度門檻及首次查詢等待策略仍為待驗證參數，未授權開始主系統 coding。決策界線見 `decisions.md`。

## 2026-09-24 主系統架構討論

已整理 Sprint 1 模組化單體設計草案：Spring Boot 查詢入口、停車目錄、來源介接／正規化、受控刷新、最新觀測查詢、預測介面及用量監測；MySQL／JPA 先保存目錄與最新完整快照，訓練歷史仍由獨立 collector 管理。第一個可展示增量建議用新莊預設目的地或座標，顯示路邊與路外最新觀測及品質狀態；模型、文字地址搜尋與多實例部署待後續設計。詳見 `architecture.md`；本節為提案，尚未開始 coding。

## 2026-09-24 目的地輸入決策

Sponsor 選定第一版由使用者輸入地址搜尋。需求已增加地址匹配候選、無結果／歧義／區外處理；架構新增可替換的地址定位邊界，確認座標後再查停車資料，重整車位不重做地址定位。TGOS、Google 與國土測繪中心的官方服務已做初步查核，實際供應者及額度／授權尚未定案。見 `requirements.md`、`decisions.md`、`architecture.md`。未開始 coding。

## 2026-09-24 TGOS 優先評估

Sponsor 同意先用 TGOS 作地址定位候選。已準備公開機構門牌、不完整地址、無效地址與區外地址的測試矩陣，以及申請／授權、座標系統、匹配品質、延遲和額度的檢查項目，見 `geocoding-evaluation.md`。目前沒有 TGOS 核准金鑰可用於 API 實測；正式供應者尚未決定。仍未開始 coding。

## 2026-09-24 目的地搜尋方向修正

Sponsor 修正：使用者搜尋體驗應類似 Google Maps，可能不使用 TGOS。先前「只輸入地址」及「TGOS 優先測試」已改為歷史決策／暫緩計畫；目前要評估候選提示、地址／地標／店名搜尋、選取後取得座標，以及是否包含互動地圖。Google Places Autocomplete 是功能候選，不等於供應者已決定。見 `requirements.md`、`decisions.md`、`architecture.md`；沒有開始 coding。

## 2026-09-24 ETA 來源決策

Sponsor 指定預計抵達時間由 Google 路線服務提供，不讓使用者自行輸入。已修正需求、專案總覽和架構，建立 `route-eta-design.md` 比較目的地 ETA 與逐停車選項 ETA、路線矩陣成本及失敗處理。出發點、是否立即出發、交通感知模式和逐選項計算範圍待確認。主系統沒有開始 coding。

- 2026-09-24 Sponsor 確認路線起點同時支援裝置目前位置與手動輸入；拒絕定位時可走手動出發地。已更新 `requirements.md`、`route-eta-design.md`、`decisions.md`。Google Routes 仍未介接，沒有開始 coding。

## 2026-09-24 Sprint 1 剩餘需求盤點

Sponsor 要求先不要 coding，並詢問主系統還有哪些需求要討論。已對照現有文件，修正過時的「手動輸入抵達時間」、「路線服務留到後續」、「TGOS／僅地址搜尋」等舊項目；`open-questions.md` 新增 Sprint 1 優先收斂清單，`roadmap.md` 改為目前的搜尋、Google ETA、即時停車與預測增量順序，`AGENTS.md` 階段同步修正。近期優先決策是：第一個可展示成果範圍、ETA 對目的地或停車選項的粒度與出發時刻、路邊區域／格位適用規則、政府與 Google 服務的失敗體驗及預算、面試展示部署方式。前端展示、模型訓練及 TDX 全臺擴充仍須按資料品質和工時分階段驗證。尚未開始主系統 coding。

## 2026-09-24 Sponsor 修正產品流程與展示範圍

Sponsor 明確指定非預設立即出發：規劃未來出發，Google Routes 算到搜尋目的地的 ETA；目的地周圍 300 公尺的路邊／路外停車依實際收錄類型一起顯示。先前「選定停車選項」是指點選某個場站或路邊結果，不是選汽車／機車；但本產品目前不做逐停車選項 ETA 或內建導航。使用者選「立即出發」時開啟外部 Google Maps 或 Apple 地圖。Google 在主系統只用於地點搜尋與 ETA，車位資料仍來自政府。資料不足需依缺的是選項、即時觀測、ETA 或模型預測分別顯示。已更新需求、路線設計及決策，無 coding。

## 2026-09-24 產品流程與展示範圍修正

Sponsor 確認目的地周圍 300 公尺，路邊／路外汽車停車資料有哪一類就呈現哪一類；機車暫不納入。出發時間預填當下，可手動改為未來日期時間，Google Routes 計算到搜尋目的地的 ETA，作為附近停車預測的共同參考時間。不做逐停車選項 ETA 或內建導航；預填當下不自動跳轉，只有明確開始導航才開 Google Maps 或 Apple 地圖。Google 用於地點搜尋與 ETA，車位資料仍由政府來源提供。已更新需求、路線設計與決策，沒有 coding。

- 2026-09-24 新建 result-states.md，將無收錄停車選項、有效 0 位、無即時觀測、過期、ETA 失敗及模型資料不足分開呈現；此為 UX 草案，尚需用原型檢查文案。300 公尺暫以直線距離作候選算法，步行距離和新莊邊界覆蓋待確認。主系統仍未 coding。

## 2026-09-24 資料時效與結果文案

Sponsor 確認五種結果分別顯示：無即時資料、預測資料不足、ETA 暫時無法估算、有效 0 位、範圍內沒有收錄；並追問超過 5 分鐘標過期是否能被使用者接受。結論是 5 分鐘維持第一版測試門檻，不能假定使用者普遍接受；草案改用「正在更新」或「暫時無法更新」，附最後成功取得時間，讓舊車位數僅供參考。若官方觀測時間可取得，須與系統取得時間分開。下一步用原型檢查使用者是否能分辨 0 位、未知及較舊資料，再定案文案與門檻。詳見 `result-states.md`；無 coding。

## 2026-09-24 Sponsor 確認 20 天交付與查詢流程

Sponsor 指定 20 天內完成搜尋、Google ETA、目前車位及路邊／路外兩類抵達時預測，先以本機面試展示，公開網址僅作日後遠端測試選項。路邊主要顯示目的地圓形 300 公尺內至少一格可停的抵達時機率，次要顯示目前來源回報有位且適用汽車的格位位置；路外顯示範圍內各停車場抵達時有位的預測。畫面依附近實際收錄類型顯示，不要求單一目的地同時有路邊和路外。兩類能力都要能驗證，資料不足時不可編造機率。

首次查詢沒有快照時，主系統須受控地抓取新北市官方即時來源；手動更新先檢查共用快照，過期且符合最低間隔／預算才向上游抓取。多人共用同輪刷新，失敗保留舊完整快照並標示取得時間。等待上限、刷新冷卻與預測最低資料量待量測。已更新 `requirements.md`、`decisions.md`、`realtime-query-design.md`、`roadmap.md`；尚未 coding。

## 2026-09-24 前後端架構盤點與進度視覺化

Sponsor 要求另有一份人類易讀的 HTML 進度圖，能辨識已完成、設計中、未決與未開始的工作。已建立靜態 `project-status.html`，顯示 20 天目標甘特圖、工作線狀態及待完成細節；這是人工維護的計畫／進度快照，不是程式實際完成比例。另建立 `frontend-backend-review.md` 盤點前端與後端尚未定案的契約。前端使用者流程有方向但框架、地圖與互動原型未定；後端模組方向有了，但查詢 API、地理查詢、快照原子發布、刷新數值與推論介面未定。獨立訓練 collector 已在運作，主系統仍未 coding。

## 2026-09-24 搜尋後畫面決策與進度頁檢查

Sponsor 指定搜尋目的地後，同一頁同時顯示地圖與結果清單，並以類似 Google Maps 的搜尋候選體驗為目標。已更新需求、決策、前後端盤點與進度 HTML；地圖底圖供應者、前端框架、手機布局、標記／清單連動及 Google 搜尋服務選型仍待確認。進度 HTML 的本機 `file:` 網址被目前瀏覽器工具安全政策阻擋；不得以其他瀏覽器路徑繞過。已進行靜態結構與連結檢查，畫面外觀仍待 Sponsor 在本機開啟後提供截圖供檢視。沒有主系統 coding。

## 2026-09-24 前端雙布局提案

新增 `ui-layout-proposal.md`：電腦版建議左清單／右地圖，手機版建議上地圖／下清單，兩版同頁同時保留地圖與結果。提案區分路邊 300 公尺區域機率、路邊當前有位格位明細與路外單場結果，並規劃地圖／清單選取連動、標記密度、時效狀態及地圖平移不觸發新查詢。Sponsor 尚未確認具體尺寸與互動，地圖供應者和前端技術未選；主系統未 coding。

## 2026-09-24 電腦版地圖優先與格位外部導航討論

Sponsor 提議電腦版改成地圖優先、標記顯示簡要資訊、點選後才展開詳細清單；手機版上地圖／下清單方向獲同意。另提議對目前回報有位且確認適用汽車的格位提供外部導航。已在 `ui-layout-proposal.md` 記錄折衷方案：地圖優先仍保留可見的 300 公尺區域預測摘要與「查看全部結果」入口；格位導航應通往其座標的外部地圖路線，明示觀測時間及無保留位保證。Google／Apple 官方地圖連結可支援路線；格位資格、座標精度、過期處理與具體介面尚未定案。沒有 coding。

## 2026-09-24 電腦版地圖優先方向接受與草圖比較

Sponsor 同意電腦版地圖優先、固定可見的路邊區域機率摘要與「查看全部結果」入口，並同意用草圖比較常駐清單與地圖優先兩種布局。新增 `ui-wireframe-comparison.md` 的 A／B 低保真圖與三個操作情境：不點標記看機率、比較兩場、查看當前有位格位路線。手機版上地圖／下清單方向維持。電腦版具體尺寸、詳情面板、標記分群與導航按鈕仍待原型驗證；沒有主系統 coding。

- Sponsor 看過 A／B 草圖比較後回覆「可以」；先解讀為同意以 B 地圖優先作電腦版第一版布局方向，手機版沿用上地圖／下清單。這不代表畫面已實作或做過使用者測試。下一步設計標記簡介、詳情面板、全部結果入口與格位外部路線的內容層級。

## 2026-09-24 格位小卡外部路線入口決策

Sponsor 同意在路邊格位標記的小資訊卡直接顯示取得時間與「查看路線」；不必先打開完整結果面板。已同步更新 `decisions.md`、`requirements.md`、`ui-layout-proposal.md` 和進度 HTML。此入口只適用目前回報有位、確認為一般汽車可停、座標可信且資料未過期的格位；外部 Google／Apple 地圖負責路線與導航，本產品不保留車位。過期或資格未核實時的中性路線入口、格位座標精度與道路側仍待查。主系統尚未 coding。

## 2026-09-24 過期查詢優先刷新，而非展示舊空位

Sponsor 追問：既然超過 5 分鐘視為過期，為何不直接抓官方新資料，讓使用者有效找到車位？修正說明：前次只談了抓取失敗時的舊資料降級，未先說清楚刷新優先順序。設計提案改為查詢見過期時，在來源最低間隔、共用鎖與預算允許下立即觸發共用刷新並短暫等待；成功顯示完整新快照。失敗／逾時／暫不能再呼叫時，不把舊「有位」格位繼續作為目前可停或導航推薦；舊快照可留作恢復、地點資訊與最後取得時間。來源頁標示路邊每 2 分鐘、路外每 3 分鐘更新，不保證每格每場都剛變動；即使重新抓到相同值，也不能冒稱來源新觀測。等待上限、刷新冷卻、舊數值是否出現在次要詳情及模型缺即時觀測時的可靠性仍待驗證。見 `realtime-query-design.md` 與 `result-states.md`；沒有 coding。

## 2026-09-24 前端需求基線足以轉入後端契約討論

Sponsor 詢問前端還有何處不清楚、是否可開始後端。盤點結論：前端的使用者流程、地圖優先布局方向、路邊／路外結果語意、格位路線入口和資料不足狀態，已足以定後端查詢語意。前端框架、地圖供應者／Google Places 接法、手機實際尺寸、標記密度、面板連動及無障礙原型仍待實作前驗證，但不阻礙後端契約設計。新建 `backend-contract-proposal.md`，先列瀏覽器、Google Routes、政府來源、模型和主系統的責任，以及主查詢輸入／輸出草案；Google 地點搜尋與路線憑證分工屬候選，不視為已決定。進度 HTML 同步改為 Sprint 1 後端契約討論中。主系統仍未 coding。

- Sponsor 隨即追問前端架構與技術是否已決定。答覆：尚未；只有產品互動與畫面方向已定。新建 `frontend-technology-options.md` 比較 Spring MVC／Thymeleaf 加少量 JavaScript，與 Vue 3／Vite 加 Spring Boot REST 的成本；推薦後者作地圖互動頁的候選，但仍等 Sponsor 選擇。地圖底圖、Places 實際接法與費用亦未定。後端契約討論可繼續，正式前後端分工需與前端選型對齊；沒有 coding。

- Sponsor 已同意 Vue 3＋Vite 作第一版前端，並追問後端是否用 Spring Boot Web。設計採 Spring Boot Web／Spring MVC 提供一般 REST API，Vue 管理地圖及結果互動；第一版無持續串流需求，不先引入 WebFlux。已更新 `decisions.md`、`architecture.md`、`open-questions.md`；地圖服務、Google Places 接法、API 欄位、上游刷新等待值和費用仍待確認。沒有 coding。

- Sponsor 同意開始討論後端查詢契約。已在 `backend-contract-proposal.md` 加入首輪提案：分開「規劃行程」與「只更新車位」兩種動作，避免手動刷新重做 Google 搜尋／ETA；主查詢送選定起訖座標與帶時區出發時間，由後端回 ETA、300 公尺範圍、路邊／路外觀測與預測及各自品質狀態。來源分別降級，過期先受控共用刷新；具體路由、欄位、等待上限與是否需要非同步二次查詢未定。僅文件討論，沒有 coding。

- 首查等待方案新增提案：Vue 立即呈現目的地與載入狀態；有效快照直接顯示，缺值或過期則啟動來源共用刷新。約 10 秒前景等待加本系統狀態輪詢是待實測的候選，不能由 collector 單一 HTTP 30 秒逾時推定整輪耗時。collector 目前排程已改為每 30 分鐘，早期 5 分鐘／51,840 次每月估算已非目前設定；以 6 請求／輪與 30 天理想運作重算約 8,640 次，仍需實際紀錄驗證。沒有 coding。

- Sponsor 已選首次查詢先顯示 ETA 與已收錄的位置，車位完成後自動補上。已同步 `decisions.md`、`requirements.md` 與 `backend-contract-proposal.md`：Vue 只查主系統的同一輪刷新進度，不因自動更新重做 Google ETA 或政府呼叫；路邊／路外與 ETA 各自完成、失敗及顯示，無可用目錄時不捏造格位位置。約 10 秒、輪詢頻率與停止條件仍待實測；主系統沒有 coding。

- 已在 `backend-contract-proposal.md` 補上漸進式查詢 API 語意提案：建立短期查詢狀態回傳目的地／本地可信位置，ETA 與兩種停車來源獨立完成；前端只讀進度，自動補上結果；手動更新走受控共用刷新。短期 `queryId`、約 2 秒本地輪詢均為候選，需先量測整輪擷取耗時、失敗率及 20 天 MVP 的實作成本，尚未定案。沒有 coding。

- 新增查詢狀態儲存提案：單實例本機展示時，個別 `queryId`、ETA／預測進度放短期記憶體；共用停車目錄與完整快照、來源擷取冷卻與用量紀錄留在 MySQL。重啟後舊 `queryId` 明確失效、在途刷新不發布半套；舊快照依原取得時間判斷過期，不因重啟重新計時。Redis 待多實例需求再評估。這是候選架構，Sponsor 尚未選定；沒有 coding。

- Sponsor 接受單實例短期記憶體查詢狀態與 MySQL 共用快照分工，並提出可能以 Docker 包裝。已在 `decisions.md` 記錄重啟語意：Spring Boot 容器重啟後舊 `queryId` 失效，MySQL 若使用持久化儲存則保留目錄與完整快照，仍按原取得時間判斷 5 分鐘時效。`backend-contract-proposal.md` 補上 Docker Compose 的兩容器本機展示候選（Spring 可提供 Vue 正式建置檔；MySQL 使用具名 volume）；具體打包方式尚未定案，沒有 coding。

- Sponsor 更正部署要求：Docker 是必要交付，未來主系統會部署到另一台伺服器，不應再只按本機展示設計。已更新 `decisions.md`、`requirements.md`、`roadmap.md`、`backend-contract-proposal.md`、`open-questions.md`：候選為單伺服器 Compose 的應用＋MySQL 持久化容器，遠端另需 HTTPS 入口、備份與金鑰限制；實際主機、網域、單／多實例和上線時點待確認。先前「可能使用 Docker」「公開部署僅後續」的舊敘述已由此要求取代。沒有 coding。

- 進入 Sprint 1 主系統資料模型討論，新增 `data-model.md` 草案。分開停車目錄／來源映射、每來源完整快照與觀測、擷取執行／冷卻狀態及個別行程預測；同一來源各頁通過驗證後才切換啟用版本，失敗或容器重啟不發布半套資料。路邊 300 公尺摘要是查詢時彙整，不當成靜態停車場。資料表、索引、批次寫入與備份仍待驗證，沒有 coding。

- 新增 `snapshot-validation.md` 快照發布草案：整輪 HTTP／分頁不完整便不發布；個別格位／場站未知值保留原始碼並標未知，有效 0 仍為 0；對應異常與覆蓋下降需先隔離／量測，路邊與路外各自發布版本及取得時間。可發布資料集不代表每個 300 公尺範圍都可預測；範圍內品質門檻另外驗證。具體比例以實測設定，未 coding。

- Sponsor 再次強調 `parking-data-collector` **純供 ML 訓練資料**，與使用者即時查詢完全分離。已將 collector 排程、分頁與 HTTP 估算移至 `training-collector-operations.md`；`realtime-query-design.md`、`snapshot-validation.md`、`data-model.md`、`backend-contract-proposal.md` 改以主系統獨立官方介接與量測為依據。共同來源的總流量僅是營運限制，不代表兩系統交換執行時資料、程式或快照。沒有 coding。

- 繼續 Sprint 1 的主系統附近查詢設計，新增 `nearby-search.md` 草案。已定的產品範圍是目的地周圍直線距離 300 公尺；候選實作先用 MySQL 矩形預篩，再做精確距離檢查，最後分開接上路邊／路外各自完整快照。文件明訂座標轉換與品質、跨新莊邊界的覆蓋警示、路外入口導航限制，以及固定區域模型不能直接宣稱可預測任意 300 公尺圓。資料庫索引、查詢延遲、邊界資料和模型覆蓋門檻待實測；沒有 coding。

- 接續討論主系統停車位置目錄與一般汽車適用性，新增 `parking-catalog-design.md` 草案。新北市路邊即時資料含位置與格位描述，路外官方另有靜態場站資訊供 `ID` 對接即時剩餘位；目錄與動態觀測分開管理。路邊 `name`／開放日與時段／備註須核對，不能把「回報空位」直接當一般汽車合法可停；條件式格位只有規則可可靠解析才按目標時點納入。完整來源擷取失敗或單筆暫時缺席不直接刪除目錄。實際代碼值、ID 穩定性、靜動態對應率、更新週期及停用門檻仍待查，沒有 coding。

- Sponsor 詢問後端架構尚缺哪些部分；已新增 `backend-architecture-gaps.md` 盤點與優先順序。主系統底座已選，但來源欄位與汽車資格、主系統刷新／額度數值、具體 API 契約、Google Routes 設定與預算、模型推論契約、Docker 遠端拓樸仍須收斂。建議下一輪先討論主系統即時資料的擷取與刷新控制，再定前後端 API；Redis／空間索引等優化留待主系統實測。尚未 coding。

- Sponsor 指定第一版技術棧加入 Redis，並要求未來接地方政府、TDX 或業者時，各來源資料先統一欄位再輸出前端。已新增 `backend-stack-and-normalization.md`，確定來源 adapter → 驗證／標準化 → MySQL 共同目錄、來源對應與完整觀測快照 → Redis 短期 `queryId`／經驗證結果快取 → Spring Boot 統一 API 的分工。Redis 取代先前單實例將 `queryId` 放 Spring 記憶體的決策；MySQL 仍保存權威快照、來源冷卻與用量。同步更新 `decisions.md`、`architecture.md`、`data-model.md`、`roadmap.md`、`backend-contract-proposal.md`、`backend-architecture-gaps.md`、`open-questions.md`。具體來源映射、快取粒度／TTL、Redis 重啟與多實例鎖待驗證；沒有 coding。

- Sponsor 補充自己習慣 Maven；已將 Maven 記為 Spring Boot 主系統的建置與相依管理工具，仍在 IntelliJ IDEA 開發。接下來的 Sprint 1 討論優先收斂主系統官方即時來源的刷新／額度數值、統一資料契約的實際欄位與前後端 API；Google 服務選型／預算、模型推論契約與遠端 Docker 細節仍待定。沒有 coding。

- 開始收斂第 1 項「主系統官方即時資料刷新」，已在 `realtime-query-design.md` 補充來源級狀態機、多人共用一輪、首次無快照／過期／失敗／手動更新的畫面與後端動作。Sponsor 已選的 5 分鐘仍是**顯示過期試驗門檻**；主系統無需求不預熱、每來源 5 分鐘最短間隔、約 10 秒前景等待只是待量測候選。官方頁標示路邊約 2 分鐘、路外約 3 分鐘發布，不能保證逐筆感測時間。每 5 分鐘連續需求時兩來源最多 576 邏輯輪／日，實際 HTTP 數還須乘分頁和重試。熱區 3 分鐘是否真的減少成本，取決於官方端點能否有效按區域取得資料；待主系統實測。沒有 coding。

- Sponsor 同意繼續主系統每來源單輪頁數、回應大小與耗時驗證。已查官方開發指引：可完整下載 CSV／ZIP，也可使用 JSON／XML 分頁和欄位篩選；但本環境對兩個官方 API 的少量唯讀探測均在 HTTPS 憑證驗證階段失敗，沒有取得 HTTP 回應，故不能宣稱已量到一輪耗時、頁數或大小。新增 `realtime-source-measurement.md` 記錄限制與可在正常 HTTPS 環境重做的量測計畫；未下載全量檔、未停用憑證驗證、沒有 coding。

- 轉入 Sprint 1 前後端 API 契約討論，新增 `api-contract-v1-draft.md`：候選三種操作為建立停車查詢、只讀查詢進度、手動更新車位；把工作進度、範圍覆蓋、目前觀測新鮮度、來源刷新和抵達時模型預測分層回傳。保留已決定的先顯示 ETA／可信位置、車位完成後補上與手動更新不重做 Google ETA。`queryId` 路由、欄位、錯誤碼、TTL、輪詢與等待數字仍是待審提案，不代表已定案或已實作。同步修正需求文件中多來源統一化應從第一版建立基礎、Docker 包含 Redis，以及結果狀態中的 300 公尺直線距離舊敘述。沒有 coding。

- 接續討論查詢失敗與重整頁面，已在 `api-contract-v1-draft.md` 補充同分頁用短期 `queryId` 只讀恢復、單一來源／Google ETA 失敗分區塊降級、查詢鍵過期後明確重新查詢，以及 Spring Boot 重啟中斷背景工作時不得讓 Redis 的 `pending` 永遠存在。重整頁面不默默重做 Google ETA 或官方抓取；重新查詢和只更新車位是不同操作。查詢期限、工作最後期限、輪詢上限與未來行程隔久返回時的體驗仍待 Sponsor／實測收斂；沒有 coding。

- 進一步區分三種期限：ETA／來源背景工作的最後期限、Vue 只讀進度輪詢節奏，以及 Redis `queryId` 的絕對有效期。`api-contract-v1-draft.md` 記錄 ETA 15 秒、來源完整抓取 60 秒、前 10 秒每 2 秒後改每 5 秒、`queryId` 2 小時等**尚未定案的試作初值**；來源實際 p95 受本環境 HTTPS 量測阻礙，不能把這些數字當服務承諾。非同步建立查詢先回已知位置與進度，不固定前景等 10 秒。另列持續開啟結果頁跨過 5 分鐘時應自動受控刷新或只由使用者手動更新的產品選擇；沒有 coding。

- Sponsor 選定持續開啟結果頁的行為：頁面可見且路邊／路外來源完整快照超過 5 分鐘試驗門檻時，各來源自動嘗試一次受控共用刷新；頁面隱藏時暫停，重回可見先只讀最新狀態。多人共用後端同一輪，冷卻、預算阻擋或失敗時不自動密集重試；保留手動更新，但刷新車位不重算 Google ETA。已同步 `decisions.md`、`requirements.md`、`api-contract-v1-draft.md` 與 `open-questions.md`。具體節奏及用量數值待量測；沒有 coding。

- 接續討論主系統回應欄位及空結果語意：提案將行程、路邊區域摘要與格位明細、路外單場結果、來源刷新狀態分層；每類另有目錄狀態、300 公尺覆蓋及已收錄合格位置數。目錄與範圍已核對後的 0 筆，僅表示「目前收錄資料中無符合條件選項」；跨新莊邊界、試點外、目錄失敗、即時觀測失敗都要分開顯示。已在 `api-contract-v1-draft.md` 與 `result-states.md` 記為**待 Sponsor 確認的提案**；試點外目的地的 HTTP 表達與跨界覆蓋判定未定。沒有 coding。

- Sponsor 同意試點外目的地的產品行為：保留已選地點與改搜入口，顯示「目前尚未支援此地區」，不呼叫 Google Routes ETA 或官方車位來源，也不把空結果當作附近沒有車位。已同步 `decisions.md`、`requirements.md`、`api-contract-v1-draft.md`；上一段的覆蓋分類其餘內容仍是提案。接下來討論新莊邊界 300 公尺圓只涵蓋一部分時，路邊區域預測與路外單場結果如何處理。沒有 coding。

- Sponsor 同意跨界部分覆蓋規則：目的地在新莊內仍算 ETA、顯示已收錄停車選項；路邊完整 300 公尺區域機率不輸出，已收錄且合格的路外場站仍可顯示各自單場預測，並標示範圍／清單缺口。已更新 `decisions.md`、`requirements.md`、`api-contract-v1-draft.md` 與 `result-states.md`。下一個 API 提案把覆蓋拆成每類完整／部分／未知，並與目錄、即時觀測、模型狀態分開；狀態名稱及可信邊界資料仍待確認。沒有 coding。

- Sponsor 接受覆蓋未知時的保守顯示：路邊完整 300 公尺範圍機率暫不輸出，仍可呈現已核實的位置；路外合格場站的單場預測可保留，但清單覆蓋情況另標。已同步 `decisions.md`、`requirements.md`、`api-contract-v1-draft.md` 與 `result-states.md`；英文欄位值與覆蓋依據未核定。下一步繼續收斂有效 0、未知與過期的目前車位 API 語意。沒有 coding。

- Sponsor 同意過期時主要 API 的目前可用數為空值，舊數值只可連同原資料時間放在次要參考資訊；有效 0、未知、過期須以不同狀態辨識。來源有可信觀測時間時，重新擷取舊內容不等於新觀測。已更新 `decisions.md`、`requirements.md`、`api-contract-v1-draft.md` 與 `result-states.md`。下一步討論目前觀測過期時，抵達時預測是否仍有合格依據；沒有 coding。

- Sponsor 接受第一版保守預測資格：模型需要最近車位觀測而資料過期、受控刷新又失敗時，該類抵達時機率為空並標「預測資料不足」及原因；路邊／路外各自判斷。未經回測的不依賴目前觀測之歷史規律模型不作自動備援。已同步 `decisions.md`、`requirements.md` 與 `api-contract-v1-draft.md`；模型容許觀測年齡、特徵與回測指標仍待定。接下來討論離線訓練產物如何接到主系統推論，沒有 coding。

- 新增 `prediction-inference-contract.md` 作**待 Sponsor 確認的推論輸入草案**：共同使用 `asOf`、`arrivalAt` 與預測時距；路邊需與訓練一致的 300 公尺格位集合、完整覆蓋、有效觀測數與目前空格數；路外按場站 ID、最新有效汽車剩餘位及營業／車種條件判斷。輸出含可空機率、狀態、原因、模型版本、目標時間和快照版本。使用者起點與搜尋文字不預設進模型。任意 300 公尺圓能否從現有固定範圍訓練資料泛化，是核心待驗證風險；模型種類、門檻及 Java／Python 執行方式均未定。沒有 coding。

- Sponsor 確認路邊訓練標籤必須與使用者目的地的直線 300 公尺圓、汽車格位資格及空位語意對齊；固定路段或整個新莊的機率不可冒充任意目的地圓形機率。已更新 `decisions.md`、`ml-source-contract.md` 與 `prediction-inference-contract.md`，並新增 `roadside-training-sample-plan.md` 作**待討論的樣本生成草案**：分布式訓練圓心、`t0`／`t1` 配對、缺失標籤排除、時間與空間分開驗證。collector 排程 30 分鐘不代表每輪成功，也不足以直接驗證 10 分鐘時距；先量測實際資料。沒有 coding。

- Sponsor 接受多圓心、真實 `t1` 標籤與時間／街區驗證的路邊樣本方法；已記 `decisions.md` 並更新 `roadside-training-sample-plan.md`。唯讀盤點確認主專案本機有 8/09 與 8/27 兩天的 TDX 格位歷史 CSV；七日檔是路段資料，不能直接代替格位標籤。collector 排程每 30 分鐘，但本機 repo 沒有每日 CSV，GitHub Actions 將資料上傳 Google Drive；未驗證實際成功間隔與缺值。需要最近連續數日的 collector 路邊／路外匯出，才能量測時間配對與標籤品質；沒有 coding。

- Sponsor 回報獨立訓練 collector 目前因資料取得方式有問題，產出的資料不完整；具體原因與缺漏比例尚未核對。依 Sponsor 指示，暫緩 collector 資料盤點、樣本生成與模型驗證，不把「排程存在」等同於可用訓練集。20 天內兩類可信預測仍是原目標，但資料依賴已成風險，不能宣稱達成。Sprint 1 改先收斂不依賴訓練 CSV 的主系統即時來源標準化、完整快照發布、前後端 API 與 Docker 部署契約；主系統尚未 coding。同步更新 `roadmap.md`、`open-questions.md`、`roadside-training-sample-plan.md` 與 `project-status.html`。

- Sponsor 同意主系統快照發布原則：缺頁或無法確認整輪取齊時不發布新版本；少數個別壞資料保留原值與品質原因、標未知／無效，只有整體品質達標才可發布，不把缺值當 0。路邊與路外各自發布，MySQL 切換啟用版本後才處理 Redis 快取；上版依原時間判斷過期。已更新 `decisions.md`、`snapshot-validation.md` 與 `open-questions.md`；可容許無效比例與異常縮水門檻待來源實測。collector 問題繼續暫緩，主系統沒有 coding。

- Sponsor 新增正式方向：未來主系統運行時的 MySQL 要保留可追溯、可供離線模型訓練的已發布標準化觀測歷史，不只覆寫最新值；線上仍只讀每來源啟用快照，Redis 不作訓練真相。歷史須有原值、來源與內部 ID、時間、品質及規則版本，失敗候選與模型補值不作標籤。已更新 `decisions.md`、`requirements.md`、`data-model.md`、`architecture.md`、`backend-stack-and-normalization.md`、`roadmap.md` 與 `open-questions.md`。保留／歸檔成本、來源使用條件及需求觸發造成的取樣偏差待決；獨立 collector 問題仍暫緩，沒有 coding。

- Sponsor 同意主系統的使用者需求刷新與低頻定時取樣共用同一受控流程，同時明確指定資料庫及來源呼叫**先服務使用者、訓練用途次要**。已更新 `decisions.md`、`requirements.md`、`data-model.md`、`realtime-query-design.md`、`roadmap.md` 與 `open-questions.md`：背景取樣受相同冷卻、鎖和預算限制，負載或預算不足可略過並記錄缺口；成功發布的版本留歷史，大量訓練匯出另行安排。背景間隔、用量上限、歷史容量及服務負載門檻待量測；主系統沒有 coding。

- Sponsor 同意每個官方來源分開記錄實際呼叫量、為使用者刷新保留可用量，背景取樣只用餘額；來源硬限制／冷卻對使用者請求也有效。已更新 `decisions.md`、`requirements.md`、`realtime-query-design.md` 與 `open-questions.md`，並修正早期「無人時完全不取樣」候選以免與新方向衝突。保留量、每輪分頁／重試成本和每日上限仍待主系統實測；主系統沒有 coding。

- Sponsor 同意 Google Routes ETA 只在起點、目的地與出發時間確認後明確送出行程規劃時呼叫；修改條件須明確重新規劃。只讀進度、可見頁面自動刷新與手動更新車位均不重算 Google ETA。Google 搜尋、ETA 與政府即時來源用量分開計量；ETA 失敗仍可顯示有效目前車位，抵達時預測標缺 ETA。已更新 `decisions.md`、`route-eta-design.md`、`requirements.md` 與 `api-contract-v1-draft.md`；正式服務選型、費率／配額與搜尋節流待核對。沒有 coding。
- 已核對 Google 官方文件並在 `route-eta-design.md` 記錄整合候選：前端 Google Place Autocomplete Widget (New) 供目的地與手動起點選取，後端 Routes API `computeRoutes` 於送出行程時估 ETA。搜尋會在輸入階段產生用量，須與 Routes、地圖用量分別估算；預算警示不是硬性費用上限。沒有 coding。
- Sponsor 已同意 Google Place Autocomplete Widget (New) 與 Routes API `computeRoutes` 作第一版搜尋／ETA 服務。檢查官方服務專用條款後修正早先「地圖可獨立決定」的說法：Places／Routes 內容不得與非 Google 地圖合用，故建議第一版採 Google Maps JavaScript API 作底圖，尚待 Sponsor 確認；地圖載入有獨立 SKU。交通感知模式與費用上限仍待決。已更新 `decisions.md`、`route-eta-design.md`、`frontend-technology-options.md`；沒有 coding。
- Sponsor 明確決定第一版互動地圖採 Google Maps JavaScript API；地圖與 Google Place Autocomplete Widget (New)、後端 Routes API `computeRoutes` 的組合已收斂。已更新 `decisions.md`、`route-eta-design.md` 與 `frontend-technology-options.md`。下一個架構決策是 Routes 的交通感知模式，然後估算個別 SKU 與內部預算；沒有 coding。
- Sponsor 同意 Google ETA 要考慮交通，並希望接近 Google Maps 的預估方式。依官方文件選 `TRAFFIC_AWARE_OPTIMAL` 作第一版設計預設；僅明確規劃／重新規劃時向 Routes 取得 ETA，未來出發時間交給 Google 估算，畫面明示這是預估且有計算時刻。`TRAFFIC_AWARE` 留作延遲實測後的替代設定；兩者目前都屬 Compute Routes Pro，完整成本、配額與等待時間仍待核算。已更新 `decisions.md`、`route-eta-design.md`；沒有 coding。
- Sponsor 更正：使用者要直接使用當次 Google 路線 ETA 作停車預測的目標時刻，不接受固定 60 分鐘或 10／30／60 分鐘作另一個抵達時間。已撤回前次 60 分鐘產品門檻提案；路邊與路外均以同一 Google ETA 預測。模型可信範圍只決定能否回傳機率，不能改寫 ETA；不足時保留 ETA 與目前車位、機率顯示資料不足。已更新 `decisions.md`、`route-eta-design.md`、`requirements.md`、`prediction-inference-contract.md`；沒有 coding。
- 已在 `route-eta-design.md` 補上結果頁長時間停留時的**待確認提案**：每次明確規劃保留原 Google ETA 與計算時刻，車位更新可在同一 ETA 時刻重新推論，但不自動呼叫 Routes；當原預計抵達時刻已過，停止把該次抵達機率當作未來預測並提示重新規劃。Google ETA 與停車快照各自顯示時間，不共用停車資料 5 分鐘過期門檻。已同步 `open-questions.md`；沒有 coding。
- Sponsor 同意上述結果頁時間處理，已轉為 `decisions.md` 與 `route-eta-design.md` 的已確認規則。另新增 `google-usage-budget.md`，將 Dynamic Maps、Places 候選／詳情、Routes Pro 與政府車位來源分開計量，列出金鑰限制、配額、帳單警示和失敗降級；沒有虛構費用數字。下一步需要 Sponsor 提供 Google Cloud 帳單設定狀態與可接受支出上限，再依實際展示操作估算。沒有 coding。
- Sponsor 回報目前只知有 Google One／雲端硬碟 2TB，尚不確定 Google Cloud Maps Platform 專案與帳單是否存在；Google 服務每月實付目標最多新臺幣 200 元。已在 `google-usage-budget.md` 記錄官方價目快照、各選定 SKU 的免費用量、Cloud Console「我的專案」檢查路徑與預算警示不是硬扣款上限。少量本機展示大致可在免費用量內，但需實測欄位／會話計費、核對帳戶與配額；公開測試不能保證仍低於 200 元。沒有 coding。
- Sponsor 想起已有 Gemini API 帳單；已更正狀態為「付款設定存在，但停車專案所需的 Maps Platform 專案、帳單連結及三項 API 啟用狀態未驗證」。Gemini 預付點數不支付 Maps 等其他 Cloud 服務。已在 `google-usage-budget.md` 補充 Cloud Console 查核步驟與建議獨立停車專案以分開監控用量；尚未建立專案、啟用服務、產生或讀取任何金鑰，沒有 coding。

- Sponsor 已查看目前所選 Google Cloud 專案的已啟用 API：只有 Gemini API 與 Telemetry API；並同意建立停車專用專案。已在 `decisions.md` 與 `google-usage-budget.md` 記錄分離決策及下一步的帳單、Maps JavaScript、Places (New)、Routes、金鑰限制與費用控制檢查。現階段仍是 Sprint 1 架構與服務設定規劃；停車 Cloud 專案尚未建立，主系統沒有 coding。

- 已在 `google-usage-budget.md` 將停車 Cloud 專案設定拆成可核對的順序：專案／帳單、NT$200 預算通知、必要 API、網頁與伺服器憑證限制、保守配額、小量測試及帳單 SKU 核對。設定完成標準與未完成狀態分開記錄；配額數字須由實際用量決定。下一步可由 Sponsor 在 Cloud Console 建立專案並回報非敏感的專案／帳單連結狀態，或繼續討論主系統 API 契約。沒有 coding。

- Sponsor 已回報 Google Cloud 停車專案 `Taiwan Parking Forecast` 建立完成（ID `taiwan-parking-forecast`，編號 `1031986609157`），Maps JavaScript API、Places API (New)、Routes API 均已啟用。已同步 `decisions.md`、`google-usage-budget.md` 和視覺化進度頁。下一個設定關口是核對該專案的有效帳單連結、NT$200 預算通知、網頁／後端各自受限憑證及可調配額；尚未核對，主系統沒有 coding。

- Sponsor 再確認停車 Google Cloud 專案的帳單連結有效，且 Console 可以設定預算通知；沒有回報通知已建立。已在 `google-usage-budget.md` 區分一般預算通知與預覽中的 Spend cap budget：目前官方適用服務未列 Maps Platform，NT$200 仍須配合配額與用量監測控制。下一步是建立以停車專案為範圍的預算通知，並確認金鑰限制及服務配額；未 coding。

- Sponsor 明確回報預算通知已設定，帳單規劃由 Sponsor 自行處理，要求先跳過費用議題並進入下一項。已更新 `google-usage-budget.md`、`decisions.md` 與視覺化進度頁。Sprint 1 下一項改回主系統前後端 API 契約：明確送出行程才取 Google ETA、只讀進度與車位刷新分開；先討論輸入來源及信任邊界。金鑰限制與配額仍在正式介接前核對，不視為已完成；沒有 coding。

- Sponsor 同意同一目的地座標同時作 Google Routes ETA 終點與直線 300 公尺停車範圍圓心；已列入 `decisions.md`。`api-contract-v1-draft.md` 新增下一步待審的建立查詢契約：起點種類、NOW／指定出發時間、明確重複送出識別、試點外與輸入錯誤處理。這些欄位名稱與 HTTP 碼仍是提案；主系統沒有 coding。

- Sponsor 同意建立查詢的起點／目的地、NOW／指定出發時間、連點與網路重試防重送、試點外不啟動上游的語意；已列入 `decisions.md`，具體欄位與 HTTP 碼仍待技術定稿。`api-contract-v1-draft.md` 追加待審的非同步第一回應：快速回 `queryId`、目的地與範圍；ETA、路邊、路外及兩類預測各自補齊，MySQL 目錄不可用須標未知，`GET` 僅讀進度。尚未 coding。

- Sponsor 同意分階段補齊結果：查詢快速回目的地／範圍與可信目錄位置，ETA、路邊、路外及各類預測彼此獨立，先完成先顯示；`GET` 僅讀進度，工作最終須有終止狀態。已寫入 `decisions.md`，`202` 與欄位／期限仍屬技術提案。`result-states.md` 新增下一步待確認的畫面狀態表，逐區塊區分載入、完成、失敗、過期、有效 0、未收錄與預測不足；沒有 coding。

- Sponsor 接受各結果區塊獨立呈現載入、完成、失敗、過期、有效 0、未收錄與預測不足；已更新 `decisions.md` 與 `result-states.md`。單一「更新車位」入口原作下一步提案，現已在下方記為已確認。
- Sponsor 同意單一「更新車位」按鈕分別處理路邊與路外：新鮮快照沿用、過期且允許才啟動來源級共用擷取、執行中加入、冷卻／失敗分別回饋；更新不重算 Google ETA。已同步 `decisions.md`、`result-states.md`、`open-questions.md`。下一步討論頁面自動刷新與手動按鈕同時發生時如何避免重送及顯示進度；文案、等待秒數與 API 欄位仍待定，沒有 coding。
- Sponsor 同意可見頁面自動刷新與手動「更新車位」共用後端每來源同一輪擷取；前端避免同頁重送，後端處理跨分頁／多人併發，手動按鈕可加入既有工作。頁面重新可見先只讀狀態再判斷；車位刷新不重算 Google ETA。已同步 `decisions.md`、`result-states.md`、`open-questions.md`。下一步討論擷取失敗後自動與手動重試規則；冷卻時長、來源成本、等待秒數與 API 欄位仍待量測，沒有 coding。
- 使用者準備開啟新分支以接續對話，已新增 `docs/conversation-handoff.md`，濃縮角色、Sprint 1 進度、已決策架構、資料來源界線及下一個未決的失敗重試規則。這是交接文件，不代表新增功能或完成實作；主系統仍未 coding。工作樹目前包含多份未提交文件，新 Git worktree 若只從 HEAD 建立，須先確認能取得最新文件。

- Sponsor 同意官方車位來源擷取失敗後的重試規則：路邊與路外分開失敗，單次失敗即停止載入並顯示最後成功取得時間；後端以來源級冷卻跨使用者、分頁、自動、手動與背景取樣共用。頁面可見且資料過期時，冷卻後最多共用自動重試一輪；仍失敗則停止自動重試，之後只接受手動更新且不得突破冷卻、預算或來源硬限制。舊有位資料不可作目前推薦或導航依據，車位重試不重算 Google ETA。已同步 `decisions.md`、`result-states.md` 與 `open-questions.md`；冷卻秒數、錯誤分類與等待上限待來源量測。沒有 coding。

- Sponsor 同意來源狀態 API 採統一欄位語意：路邊與路外各自回傳 `FRESH`、`STALE`、`REFRESHING`、`FAILED`、`COOLDOWN`、`NO_DATA`、`UNSUPPORTED` 等狀態，並搭配最後成功取得時間、來源觀測時間、可再試時間、刷新中、是否可立即刷新與原因碼。過期、失敗、冷卻或未知時，主要目前可用數為空；舊值只作附時間的次要參考，不作目前推薦或導航依據。已同步 `decisions.md` 與 `api-contract-v1-draft.md`；實際欄位名稱與錯誤碼細節待技術定稿。沒有 coding。

- Sponsor 同意查詢總回應採固定頂層分層：`query`、`destination`、`area`、`eta`、`roadside`、`offStreet`、`predictions`、`refresh`。此分層將行程生命週期、目的地、300 公尺範圍、Google ETA、路邊觀測、路外觀測、抵達時模型預測與來源刷新控制分開，避免前端把目前車位當成抵達時機率，也方便未來接入其他資料來源。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；各區塊內正式欄位仍待定。沒有 coding。

- Sponsor 同意先定 `query` 區塊：它只描述查詢生命週期與前端輪詢控制，不放目的地、ETA、車位數或模型結果。第一版語意包含 `queryId`、整體狀態、建立時間、伺服器時間、查詢到期時間、是否仍有工作、建議輪詢秒數、進行中／已終止區塊、是否需要重新規劃及可選防重送追蹤。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式 JSON 命名、期限數字和 HTTP 表達待定。沒有 coding。

- Sponsor 同意 `destination` 區塊只描述本次選定目的地與主系統支援判定，不放 ETA、停車選項或模型預測。第一版語意包含顯示名稱、可選格式化地址、可選 Google `placeId`、目的地座標、來源種類、支援狀態、MVP 區域、拒絕原因與使用者訊息。目的地座標同時作 Google ETA 終點與 300 公尺搜尋圓心；試點外目的地不啟動 Google Routes 或官方車位來源。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式欄位命名和 `placeId` 保存策略待定。沒有 coding。

- Sponsor 同意 `area` 區塊描述本次停車查詢的 300 公尺地理範圍與資料覆蓋，不放 ETA、即時剩餘位或模型機率。第一版以目的地座標為圓心、直線距離 300 公尺為半徑，並分別標示整體、路邊與路外覆蓋狀態。若查詢圓跨出新莊收錄範圍，可顯示已收錄位置與合格路外單場結果，但不得輸出完整 300 公尺路邊區域機率。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；邊界資料與目錄統計方式待定。沒有 coding。

- Sponsor 同意 `eta` 區塊只描述本次 Google Routes 行程時間，不放車位資料或模型機率。第一版語意包含 ETA 狀態、起點摘要、目的地參照、出發時間、計算時間、行車秒數、預計抵達時間、交通模式、供應者、是否需要重規劃、失敗原因與使用者訊息。`estimatedArrivalAt` 是路邊與路外抵達時預測的共同目標時刻；ETA 失敗或過期時，預測不得輸出有效機率。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；Google 回應欄位映射與錯誤碼細節待定。沒有 coding。

- Sponsor 同意 `roadside` 區塊只描述 300 公尺範圍內的路邊目前觀測、來源狀態與格位明細，不放抵達時區域機率。路邊抵達時「至少一格可停」機率統一放在 `predictions`；`roadside.summary.availableSpotCount` 只代表新鮮快照中的目前觀測。格位外部導航入口僅在目前回報有位、資料新鮮、一般汽車適用、座標有效且品質合格時提供；過期或未知資料不能作目前推薦。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式欄位命名、清單上限、特殊格位排除與座標品質待定。沒有 coding。

- Sponsor 同意 `offStreet` 區塊只描述目的地 300 公尺內已收錄路外停車場的目前觀測、來源狀態與逐場資訊，不放抵達時逐場機率。路外抵達時「該停車場仍有汽車位」機率統一放在 `predictions`；目前剩餘汽車位只代表新鮮快照中的目前觀測。有效 `0`、有效正數與 `AVAILABLE=-9` 類無法提供即時動態的特殊狀態必須分開；過期或未知資料不能作目前有位推薦。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式欄位命名、入口座標、總車位、排序與清單上限待定。沒有 coding。

- Sponsor 同意 `predictions` 區塊是唯一承載抵達時預測的地方，不放目前車位數。路邊預測表示目的地 300 公尺內抵達時至少一個一般汽車路邊格位可停的區域機率；路外預測表示每個合格路外停車場抵達時仍有汽車位的逐場機率。`targetArrivalAt` 必須等於 Google Routes 的 `eta.estimatedArrivalAt`；ETA 缺失、過期、覆蓋不完整、資料不足或模型未驗證時，必須明確標示不可預測或資料不足。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；機率尺度、回測指標、模型版本與原因碼待定。沒有 coding。

- Sponsor 同意 `refresh` 區塊只描述路邊與路外官方來源的刷新操作、共用擷取、冷卻、失敗重試與可再試時間，不放目前車位數或抵達時預測。來源處置語意包含沿用新鮮快照、加入既有刷新、啟動新刷新、冷卻、預算阻擋、失敗、成功、無資料與不支援。車位刷新不重新搜尋地點、不重算 Google ETA、不延長 `queryId`，也不能讓前端繞過後端冷卻、預算與來源限制。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；冷卻秒數、等待上限、最低間隔與錯誤碼待定。沒有 coding。

- 已在 `api-contract-v1-draft.md` 補上兩個 API 範例回應草案：建立查詢後快速回應，以及各區塊完成或終止後的查詢回應。範例用假資料示範 `query`、`destination`、`area`、`eta`、`roadside`、`offStreet`、`predictions`、`refresh` 如何同時存在，並明確區分目前觀測、抵達時預測與刷新控制。已同步 `decisions.md`；正式欄位命名、大小寫、必填／可空規則、分頁、排序與錯誤碼待定。沒有 coding。

- 已把主系統正式 API 路由收斂為三個查詢端點：`POST /api/v1/parking-queries` 建立非同步查詢、`GET /api/v1/parking-queries/{queryId}` 只讀進度、`POST /api/v1/parking-queries/{queryId}/refresh` 只做路邊／路外受控刷新。HTTP 成功只代表主系統成功處理請求，不代表 Google、政府來源或模型都成功；上游失敗由各區塊狀態表達。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式錯誤碼、請求欄位、驗證細節、TTL 與限流策略待定。沒有 coding。

- 已定義 `POST /api/v1/parking-queries` Request Body 草案：只接收 `requestToken`、`origin`、`destination`、`departure`。前端不傳 ETA、目前車位、模型機率、車種、半徑、距離算法、Google 交通模式或刷新策略；`NOW` 由後端取伺服器時間，`SCHEDULED` 需含時區偏移。目的地座標同時作 Google Routes 終點與 300 公尺圓心；試點外不呼叫上游。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；正式 DTO 名稱、欄位大小寫、長度限制、`requestToken` TTL 與限流待定。沒有 coding。

- 已定義查詢 Response 必填與可空規則：`query`、`destination`、`area`、`eta`、`roadside`、`offStreet`、`predictions`、`refresh` 八個頂層區塊固定存在；有效 0 回數字 0，未知或不適用才用 `null` 並搭配狀態／原因碼。清單可為空陣列，但不能用空陣列直接表示附近沒有車位；機率只在預測 `status=READY` 時有效。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；OpenAPI required 清單、DTO 註解、分頁與錯誤格式待定。沒有 coding。

- 已定義主系統錯誤回應格式與候選錯誤碼：錯誤回應固定含 `error.code`、`error.message`、`error.retryable` 與 `serverTime`，並可帶 `details`、`nextAllowedAt`。第一版錯誤碼涵蓋輸入錯誤、座標錯誤、requestToken 錯誤、試點外、出發時間錯誤、限流、查詢過期、查詢不存在、服務暫不可用與主系統內部錯誤。上游來源失敗、冷卻或預算阻擋通常放在區塊狀態，不作整頁錯誤。已同步 `api-contract-v1-draft.md` 與 `decisions.md`；`details` 結構、traceId、前端文案與 Spring Boot 例外類型待定。沒有 coding。

- 已補上後端 Spring Boot package 分層與 DTO 命名草案：`api` 放 Controller 與 request／response DTO，`application` 放查詢、刷新、ETA、預測 use case 協調，`domain` 放停車、查詢、刷新與預測語意，`adapter` 放 Google／新北市／TDX 外部來源，`infrastructure` 放 JPA 與 Redis，`config` 放設定。JPA entity 不直接回前端，來源原始欄位不直接進 API response。已同步 `backend-stack-and-normalization.md` 與 `decisions.md`；正式 root package、Maven groupId、Java／Spring Boot 版本與 command 物件細節待定。沒有 coding。

- 已新增 `docs/engineering/data-model.md`，定義 MySQL／JPA 資料模型草案：`parking_place`、`source_mapping`、`availability_snapshot`、`availability_observation`、`source_state`、`ingestion_run`，並說明 `parking_query` 第一版不持久化到 MySQL，短期查詢狀態放 Redis。資料庫主要服務使用者查詢，保存觀測歷史供未來訓練為次要用途；有效 0、未知、無即時資料與原始特殊值分開保存。已同步 `backend-stack-and-normalization.md` 與 `decisions.md`；欄位型別、索引、分區、保留期限、原始 response 保存與批次寫入待量測。沒有 coding。

- 已補上快照發布交易流程：來源擷取先建立 `ingestion_run` 與 `CANDIDATE` 快照，批次寫入觀測並完成品質檢查；缺頁或整輪失敗標記拒絕，不切換 `source_state.active_snapshot_id`，也不更新 Redis。驗證通過後，用短交易將快照改為 `PUBLISHED` 並切換啟用版本；交易提交後才更新或失效 Redis。使用者查詢只讀已發布快照，未發布候選、失敗擷取與模型補值不得作訓練標籤。已同步 `snapshot-validation.md`、`engineering/data-model.md` 與 `decisions.md`；交易隔離、批次大小、候選清理與 Redis 補快取待定。沒有 coding。
- 繼續收斂 300 公尺附近查詢的後端落地方式：已決定第一版先用 MySQL 經緯度矩形預篩，再由 Spring application service 計算精確直線距離；Repository 只取候選位置，不決定產品語意。路邊與路外共用位置候選流程，但各自連到自己的已發布快照與來源狀態。MySQL spatial index 暫列效能實測後的升級選項；索引欄位順序、候選數、排序／分頁與延遲待量測。已更新 `nearby-search.md`、`engineering/data-model.md`、`decisions.md` 與 `open-questions.md`；沒有 coding。
- 繼續收斂 Redis 設計，新增 `redis-design.md`。已決定 Redis 第一版只放短期 `queryId` 進度、防重送 token、來源刷新短鎖／進度，以及含快照版本且可由 MySQL 重建的結果快取；MySQL 仍是已發布快照、來源冷卻、用量和訓練歷史的事實來源。Redis TTL 不代表官方資料新鮮度，Redis 清空只讓短期查詢失效，不會刪除已發布車位資料。實際 TTL、容量、序列化、鎖 owner token／心跳及快取命中率待實測；沒有 coding。
- 繼續收斂 Docker／部署架構，新增 `docker-deployment.md`。已決定第一版以單台伺服器 Docker Compose 作候選，服務包含 `web`、`api`、`mysql`、`redis`；對外只暴露 `web`，`api`、MySQL、Redis 留在內部 network。MySQL volume 必須持久化，Redis 可視為短期狀態；Google／TDX／資料庫密碼均以環境變數或部署端 `.env` 注入，不提交 Git 或寫進 image。HTTPS 入口、正式版本、備份流程、目標主機與模型產物放置方式仍待定；沒有 coding。
- 實作前最後確認時，Sponsor 補充開發端測試希望使用 H2。已更新 `backend-stack-and-normalization.md` 與 `decisions.md`：H2 用於 repository／service 快速測試，不取代正式 MySQL、Docker 整合測試、索引行為或資料量測。另在 `project-status.html` 新增 UML 架構視圖，包含 Docker Compose 部署圖與核心資料模型類別圖，方便實作前與面試回顧；沒有 coding。
- 實作前最後確認新增 `implementation-plan.md`，確認開始 coding 後的順序：先建 Spring Boot／Vue／Docker／MySQL／Redis／H2 測試骨架，再做 JPA 資料模型與 API mock，接著完成 300 公尺停車目錄查詢、官方即時來源快照發布、受控刷新、Google 搜尋與 ETA、前端結果頁，最後接預測基準與模型。第一個可展示版本先證明主系統資料流與狀態降級，預測可先顯示資料不足原因，不用假機率。已同步 `roadmap.md`、`decisions.md`、`project-status.html`；沒有 coding。
- 實作前最後確認補上開發流程：新增 `development-workflow.md`，決定由 PM 角色拆任務，任務需有範圍、驗收標準、測試方式與 Human-in-the-loop 檢查點；AI 可負責實作與自檢，Sponsor 在任務範圍、UI、外部服務、部署、預測結果與合併前做關鍵驗收。Harness 若指 CI/CD 平台，第一版暫不導入，先以 GitHub Issues／Projects 與 GitHub Actions 候選支撐 MVP；沒有 coding。
## 2026-09-25 Sprint 2 第一組 Issues 草案

依 Sponsor 指示，先不要 coding，已改由 PM／Scrum Master／Tech Lead 角度拆 Sprint 2 第一組 GitHub Issues 草案。草案新增於 `docs/sprint-2-issue-drafts.md`，涵蓋實作分支與工作規則、Spring Boot Maven 後端骨架、Vue 3 + Vite 前端骨架、Docker Compose、本機 H2 測試與 MySQL 設定邊界、mock 查詢 API 生命週期，以及文件同步與驗收檢查清單。

此批任務的目的，是在接 Google、新北市官方即時資料與 ML 前，先建立可啟動、可測、可部署、可由 Sponsor 逐張驗收的主系統骨架。草案尚未轉成正式 GitHub Issues，也尚未開始產品程式碼實作。下一步是由 Sponsor 確認 Issue 順序與範圍，再決定是否建立正式 GitHub Issues 並從 S2-001 開始。
## 2026-09-25 GitHub Issues 已建立

已將 Sprint 2 第一組草案轉成 GitHub Issues：S2-001 至 S2-007。GitHub connector 對 private repo 無 Issue 權限，因此改用本機 GitHub 憑證呼叫 GitHub API 建立，過程未輸出 token。`docs/sprint-2-issue-drafts.md` 已補上正式 Issue 連結。下一步可建立 Sprint 2 實作分支，從 S2-001 開始處理；主系統仍尚未 coding。
## 2026-09-25 Sprint 2 實作分支已建立

已從 `main` 建立並推送 Sprint 2 實作分支 `sprint-2-main-system-scaffold`，對應 GitHub Issues 第一組任務。此分支目前只作為 Sprint 2 開發入口，尚未開始主系統產品程式碼實作。下一步可在此分支處理 S2-001 的剩餘工作：確認工作規則、金鑰不進 Git、任務完成檢查與後續 PR／合併流程，再進入 S2-002 後端骨架。

## 2026-09-25 S2-001 工作規則補齊

已補上 `.env.example` 作為本機環境變數範本，僅含假值與變數名稱；真實金鑰、密碼與 token 仍由 `.gitignore` 排除並不得提交。`docs/development-workflow.md` 新增 Branch／Issue／PR 規則、金鑰與環境變數規則，以及 Definition of Done 檢查清單。`README.md` 補上 Sprint 2 啟動狀態與 `.env.example` 使用提醒。這些變更用於收尾 S2-001，尚未開始產品程式碼實作。
## 2026-09-25 S2-002 後端骨架建立

已建立 `backend/` Spring Boot Maven 後端骨架，使用 Java 21、Spring Boot `4.1.1`、Spring Web MVC、Validation、Actuator 與 Maven Wrapper。設定檔採 Sponsor 指定的 YAML：`backend/src/main/resources/application.yml`。目前提供 `/api/health` 與 `/actuator/health`，並保留 `api`、`application`、`domain`、`adapter`、`infrastructure`、`config` package 邊界。執行 `backend/mvnw.cmd test` 通過：2 tests、0 failures。尚未接 MySQL、Redis、Google、新北市 API 或 ML。
## 2026-09-25 S2-002 package 結構簡化

依 Sponsor 回饋，移除 `tw/jiawei` 反向網域命名層級，後端 root package 改為 `parking.forecast`，主類別改為 `ParkingForecastApplication`。同時將 `api/application/infrastructure` 調整為較直覺的 `controller/service/repository`，保留 `domain`、`adapter`、`config`。目前可見結構為 `controller`、`service`、`repository`、`domain`、`adapter`、`config`。執行 `backend/mvnw.cmd test` 通過：2 tests、0 failures。
## 2026-09-25 Sprint 2 Issues 用語調整

依 Sponsor 指示，Sprint 2 第一組 Issues 不再使用「面試官角度」或面試相關詞語。`docs/sprint-2-issue-drafts.md` 已改為「工程審查重點」，相關描述改成對外說明、技術說明、作品集專案或展示等中性用語。正式 GitHub Issues S2-001 至 S2-007 也同步更新。
## 2026-09-25 S2-002 後端骨架驗收完成

S2-002 已完成驗收：`backend/mvnw.cmd test` 通過，結果為 2 tests、0 failures；實際啟動後端後，`GET /api/health` 與 `GET /actuator/health` 均回 `UP`。後端 package 已依 Sponsor 回饋簡化為 `parking.forecast`，並改成 `controller`、`service`、`repository` 三層式可見結構。GitHub Issue S2-002 已更新並關閉。下一步進入 S2-003 前端骨架。
## 2026-09-25 S2-003 前端骨架建立

已建立 `frontend/` Vue 3 + Vite 前端骨架，包含目的地搜尋 placeholder、地圖 placeholder、ETA／路邊／路外結果摘要 placeholder，以及 `VITE_API_BASE_URL`、`VITE_GOOGLE_MAPS_BROWSER_API_KEY` 環境變數入口。已新增 `frontend/README.md` 與 `frontend/.env.example`。執行 `npm run build` 通過。尚未接 Google Maps JavaScript API、Places Autocomplete、Routes API 或後端真查詢。
## 2026-09-25 S2-003 前端骨架驗收完成

Sponsor 確認 S2-003 前端 placeholder 版面可接受。前端骨架目前包含目的地搜尋 placeholder、地圖 placeholder、ETA／路邊／路外結果摘要 placeholder，以及 `frontend/.env.example`。`npm run build` 已通過，GitHub Issue S2-003 已關閉。下一步進入 S2-004 Docker Compose 本機開發環境。
## 2026-09-25 新增 S2-008 CI/CD 基礎檢查 Issue

Sponsor 詢問 CI/CD 對應 Issue。原第一組 Issues 中 S2-007 只涵蓋流程與驗收清單，沒有獨立實作 CI/CD；因此新增 GitHub Issue S2-008：[CI/CD] 建立 GitHub Actions 基礎 CI。第一版範圍為後端 Maven test、前端 npm build，Docker build 視 S2-004 完成狀態加入；不做 Harness、不做遠端部署。
## 2026-09-25 S2-004 Docker Compose 本機環境建立

已建立 Docker Compose 本機開發環境，包含 `web`、`api`、`mysql`、`redis`。因本機 3306 與 8080 已被占用或受限，host port 調整為：API `18080 -> 8080`、MySQL `3307 -> 3306`、Web `5173 -> 80`、Redis `6379 -> 6379`。驗證結果：`docker compose build` 成功；`docker compose up -d` 後 api/mysql/redis healthy，`GET http://localhost:18080/api/health` 回 `UP`，`http://localhost:5173` 回 HTTP 200。驗證後已執行 `docker compose down` 停止服務。
## 2026-09-25 S2-004 Docker Compose 驗收完成

Sponsor 確認 S2-004 可接受。Docker Compose 本機開發環境已完成並驗證，GitHub Issue S2-004 已關閉。下一步進入 S2-005：後端 profile、H2 測試邊界與 MySQL 連線設定。

## 2026-09-25：S2-005 後端 profiles、H2 與 MySQL 設定進行中

已在後端加入 JPA、MySQL Connector 與 H2 依賴，並建立 `local`、`test`、`docker` 三種 profile。`local` 與 `test` 使用 H2 in memory；`docker` 使用 MySQL 並由 Docker Compose 注入 datasource 環境變數。新增測試確認 `test` profile 實際連到 H2，`backend` Maven 測試 3 個測試全部通過。

此階段只完成資料庫連線邊界與測試設定，尚未建立正式 entity、repository、schema migration、資料表或 API 資料流程。Docker Compose 已以 `docker` profile 驗證 API 可連 MySQL 並回應健康端點；S2-005 尚待 GitHub Issue 回報與 Sponsor 驗收後關閉。

## 2026-09-25：S2-006 mock 查詢 API 生命週期初版完成

已建立 `POST /api/v1/parking-queries`、`GET /api/v1/parking-queries/{queryId}`、`POST /api/v1/parking-queries/{queryId}/refresh` 的 mock/stub 流程。後端以 in-memory mock service 暫存 `queryId`，回應固定包含 `query`、`destination`、`area`、`eta`、`roadside`、`offStreet`、`predictions`、`refresh` 八個頂層區塊；預測區塊明確回 `INSUFFICIENT_DATA`，不輸出假機率。前端已可送出 mock 查詢並顯示 queryId、ETA、路邊、路外與預測資料不足狀態。

驗證結果：後端 Maven test 通過 5 個測試；前端 `npm run build` 通過；本機 HTTP 手動驗證三個 mock endpoint 均可用；Docker Compose `api` 與 `web` image build 通過；Docker runtime 已透過 `http://localhost:18080/api/v1/parking-queries` 驗證建立、讀取與刷新 mock 查詢，Web `http://localhost:5173` 回 200。此階段尚未接 Google Places／Routes、新北市官方 API、Redis、MySQL query persistence 或正式預測模型。

## 2026-09-25：S2-006 驗收完成，S2-007 文件同步開始

Sponsor 確認 S2-006 可繼續，GitHub Issue S2-006 已以 completed 關閉。GitHub connector 已由 Sponsor 重新連線並恢復 private repo Issue 讀寫能力。已建立 `docs/task-completion-checklist.md` 作為每張 Issue 完成前的共同 Definition of Done，涵蓋範圍、功能、測試、文件、Git 與工程說明驗收。`docs/development-workflow.md` 已連結此清單並修正驗收用語；README 已同步 Sprint 2 實際進度，避免仍描述為尚未開始主系統程式碼。

## 2026-09-25：S2-007 Sponsor 回覆

Sponsor 確認任務完成檢查清單不會太繁瑣，並確認 CI/CD 先使用 GitHub Actions，不導入 Harness。已補充文件驗收規則的語意：重要任務若影響需求、架構、API、部署、資料規則、驗收結果或目前進度，需要同步對應文件；小型 typo、樣式微調或不影響理解的小重構可說明無需文件更新。

## 2026-09-25：S2-007 驗收完成

Sponsor 確認小修改可不更新文件，只需在回報中說明原因；重要任務仍需同步必要文件。GitHub Issue S2-007 已以 completed 關閉。Sprint 2 第一組 S2-001～S2-007 已完成，後續可進入 S2-008 GitHub Actions 基礎 CI，或依需要先整理 PR／合併回 main。

## 2026-09-25：Sprint 2 第一組成果 PR 已建立

已將 `sprint-2-main-system-scaffold` 開 PR 到 `main`：https://github.com/liujiawei-tw/taiwan-parking-forecast/pull/9。PR 範圍包含 S2-001～S2-007：後端骨架、前端骨架、Docker Compose、後端 profiles、mock 查詢生命週期、文件同步與任務完成檢查清單。PR 前已重新驗證：後端 Maven test 5 tests／0 failures、前端 `npm run build` 通過、Docker Compose `api`／`web` image build 通過。GitHub 回報 PR 可合併且狀態為 clean；Sponsor 確認後已合併回 main，merge commit 為 8a6e18b9643cb8cd5d50fc7a573d3c81448af2c3。

## 2026-09-25：S2-008 GitHub Actions 基礎 CI 進行中

已刪除已合併的 `sprint-2-main-system-scaffold` 本機與遠端分支，並從 `main` 建立 `feature/s2-008-github-actions-ci` 作為 S2-008 工作分支。已新增 `.github/workflows/ci.yml`，第一版 CI 包含後端 Maven test、前端 npm build，以及 Docker Compose 的 api／web image build；此 workflow 不需要 Google、TDX、新北市 API key 或任何真實機密。本機對照驗證已完成：後端 Maven test 5 tests／0 failures、前端 npm run build 通過、Docker Compose api／web image build 通過。由於 `backend/mvnw` 在 Git 中不是可執行權限，workflow 已加入 `chmod +x ./mvnw` 步驟，避免 Linux runner 權限失敗。已建立 PR #10：S2-008：建立 GitHub Actions 基礎 CI。第一次遠端 CI 中後端 Maven test 與前端 npm build 通過，但 Docker image build 因 Maven Wrapper 執行權限失敗；已補上 Dockerfile 內的 `chmod +x ./mvnw`，並將 `backend/mvnw` 在 Git 中標記為可執行。重新推送後，GitHub Actions pull_request run 已通過：後端 Maven test、前端 npm build、Docker image build 全部成功。PR #10 目前可作為 S2-008 的驗收候選，等待 Sponsor 確認是否合併回 main。

## 2026-09-26：S2-008 合併完成，第二組 Issues 已建立

PR #10 已合併回 `main`，merge commit 為 `7e1241c22fbc4c55cac432698ca20e0fb187b6bf`；S2-008 已透過 PR 關閉。本機 `main` 已同步，且已刪除 `feature/s2-008-github-actions-ci` 本機與遠端分支。Sprint 2 第一組 S2-001～S2-008 到此完成。

已建立 Sprint 2 第二組 GitHub Issues：S2-009 到 S2-013。第二組目標是建立資料庫模型、schema migration、新莊小樣本、300 公尺查詢服務，最後把本地停車目錄結果接入既有 `parking-queries` 流程。此組尚未開始實作；正式 Google ETA、新北市官方即時 API、Redis query state 與 ML 預測仍不在此組範圍內。

## 2026-09-26：S2-009 核心資料模型第一輪實作完成

已從 `main` 建立 `feature/s2-009-data-model` 分支，開始處理 S2-009。新增核心 JPA Entity 與 Repository：`parking_place`、`source_mapping`、`availability_snapshot`、`availability_observation`、`source_state`、`ingestion_run` 對應的 Java 模型與資料存取介面。此階段只建立資料模型與 repository 邊界，尚未建立正式 migration、seed 流程、300 公尺查詢服務、即時來源介接或前端顯示。

Sponsor 發現曾出現錯誤的 `backend/backend/src` 空目錄。已確認該目錄沒有檔案且未被 Git 追蹤，已刪除；正確後端目錄仍為 `backend/src/...`。為避免重複發生，已更新 `AGENTS.md` 與 `docs/development-workflow.md`：建立、移動或刪除檔案前，必須先檢查 repo 架構與 Issue 範圍，後端檔案只放在 `backend/src/...`，前端檔案只放在 `frontend/src/...`。

驗證結果：因本機沒有獨立 Maven，且 Windows Maven wrapper 在目前 shell 環境有啟動問題，改用 Docker 容器執行 Linux Maven wrapper。`./mvnw test` 通過：6 tests、0 failures、0 errors。測試包含既有 health/mock API 測試，以及新增的 `DataModelRepositoryTest`，確認核心 entity 關聯可在 H2 test profile 中持久化與查詢。

## 2026-09-26 Sprint 2：S2-010 schema migration 驗證

S2-010 已建立第一版 Flyway migration：`backend/src/main/resources/db/migration/V1__create_core_parking_schema.sql`，涵蓋核心資料表與索引。Spring Boot 4 需使用 `spring-boot-starter-flyway` 才會載入 Flyway 自動設定；已由原本只加 `flyway-core` 修正為 starter。

驗證結果：Docker Maven 後端測試通過，6 tests／0 failures；測試日誌確認 H2 test profile 會套用 Flyway V1 migration。另以臨時 Docker Compose project 驗證 MySQL，API container 變成 healthy，MySQL `flyway_schema_history` 顯示 version 1 success，並建立 `parking_place`、`source_mapping`、`ingestion_run`、`availability_snapshot`、`availability_observation`、`source_state`。

邊界：H2 是開發端快速測試；MySQL schema、索引、型別與啟動行為仍需 Docker MySQL 驗證。S2-010 不包含 seed 資料、300 公尺查詢、官方即時 API 或預測模型。

## 2026-09-26 Sprint 2：S2-011 新莊停車目錄小樣本

S2-011 已新增可提交的小樣本 seed：`backend/src/main/resources/db/seed/R__seed_xinzhuang_parking_catalog.sql`。內容包含 3 筆新莊路邊示範汽車格位與 2 筆新莊路外示範停車場，並建立對應 `source_mapping`。這批資料使用 `source_system=SYSTEM` 與 `SEED-XINZHUANG-*` 來源 ID，明確標示為合成小樣本，不是官方完整資料，也不含即時剩餘車位或 ML 訓練標籤。

載入方式：`test` 與 `local` profile 會載入 `classpath:db/migration,classpath:db/seed`；`docker` profile 預設不載入 seed，若要在 MySQL 驗證小樣本，可明確設定 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed`。

驗證結果：Docker Maven 後端測試通過 8 tests／0 failures；測試日誌確認 H2 套用 V1 schema migration 與 repeatable seed migration。另以臨時 Docker Compose MySQL 驗證，API container healthy，MySQL 中有 3 筆路邊、2 筆路外、5 筆 source mapping。

邊界：S2-011 不包含完整新北市資料匯入、即時剩餘位、Google Maps 顯示、300 公尺查詢服務或 ML 訓練資料。

## 2026-09-26 S2-011 人工驗收完成

Sprint 2 S2-011 已完成新莊停車目錄小樣本與 seed 流程。Sponsor 使用 Postman 進行人工驗收並回報測試 OK，Docker 測試環境已由 Sponsor 關閉。AI 另以等同 Postman 的 HTTP 流程驗證 API health、建立查詢、讀取查詢與刷新端點皆可回應；目前查詢端點仍使用 S2-006 mock 查詢生命週期，S2-011 seed 資料已進資料庫，但尚未透過 HTTP 查詢。S2-012 將實作 300 公尺停車位置查詢服務，才會把 seed 目錄轉成可查詢結果。

驗收重點：Docker Maven test 通過 8 tests／0 failures；臨時 Docker MySQL 已驗證 Flyway seed 載入 3 筆路邊、2 筆路外與 5 筆 source mapping；手動測試未發現阻礙。PR #18 目前可進入 Sponsor 最終合併確認。

## 2026-09-26 S2-012 300 公尺停車位置查詢服務

Sprint 2 S2-012 已開始實作後端停車目錄查詢 service。新增 `NearbyParkingSearchService`，使用 `parking_place` 目錄資料做「目的地直線 300 公尺內」查詢：先以 service area、active status、經緯度矩形範圍向 repository 預篩，再用 Haversine 公式精算直線距離，依距離排序，並回傳路邊與路外數量。此 service 目前只提供後端內部能力，尚未接到 HTTP API 或前端；S2-013 才會接入 `parking-queries` 流程。

驗證結果：Docker Maven 後端測試通過 12 tests／0 failures；新增 service 測試涵蓋 300 公尺內查詢、距離排序、路邊／路外分流、無結果與非法半徑。另用臨時 MySQL 8.4 容器覆蓋 datasource、載入 V1 migration 與 seed，執行 `NearbyParkingSearchServiceTest` 通過 4 tests／0 failures。第一次 MySQL 驗證曾因 test profile 的 H2 driver 與 MySQL URL 不一致而失敗，修正驗證環境變數後已通過；該失敗不是服務邏輯錯誤。

邊界：S2-012 不包含 HTTP endpoint、前端顯示、即時剩餘位、Google ETA、Redis 或預測模型。300 公尺目前為直線距離，不代表步行距離。

## 2026-09-26 S2-013 停車目錄查詢接入 parking-queries

Sprint 2 S2-013 已開始將本地停車目錄查詢接入既有 `parking-queries` mock 流程。`POST /api/v1/parking-queries` 仍沿用原查詢生命週期與 mock ETA，但路邊與路外結果改由 `NearbyParkingSearchService` 查詢 S2-011 新莊 seed 目錄產生。回應會依目的地 300 公尺內是否有目錄資料，回傳 `PARTIAL` 或 `NO_CATALOG_RESULTS`；路邊目前可用數與路外可用汽車位仍為 `null`，避免把目錄位置誤認為即時車位。

驗證結果：Docker Maven 後端測試通過 13 tests／0 failures；新增 controller 測試涵蓋 300 公尺內回傳本地路邊／路外目錄、範圍外無目錄結果、建立後讀取與刷新。另用臨時 MySQL 8.4 容器載入 V1 migration 與 seed，執行 `ParkingQueryControllerTest` 通過 3 tests／0 failures。

邊界：S2-013 不包含 Google ETA、新北市即時來源、Redis 查詢狀態、前端畫面調整或 ML 預測；即時剩餘位仍待後續官方來源與快照發布功能。


## 2026-09-26 S2-013 合併完成，第二組資料基礎收尾

PR #20 已合併回 `main`，merge commit 為 `ef000d02180794dbf4eb205cf7a4726f44db0680`。本機 `main` 已同步到 `origin/main`，並刪除 `feature/s2-013-integrate-catalog-query` 本機分支。Sprint 2 第二組 S2-009～S2-013 已完成：核心資料模型、Flyway schema、seed 小樣本、300 公尺查詢 service，以及 `parking-queries` 接入本地停車目錄。

目前主查詢仍使用 mock ETA，且只回已收錄停車位置；尚未接 Google ETA、新北市官方即時車位、Redis 查詢狀態、前端地圖整合或 ML 預測。下一組任務應從「官方即時來源與快照發布」開始，並維持既有規則：失敗不覆蓋舊快照、0 位／未知／過期／未收錄要分開、訓練 collector 不參與使用者查詢。


## 2026-09-26 S2-014～S2-018 第三組 Issues 建立

已建立 Sprint 2 第三組 GitHub Issues，聚焦官方即時來源與快照發布：S2-014 新北市官方即時來源 Client 與原始 DTO、S2-015 即時資料正規化與資料品質規則、S2-016 候選快照寫入與發布交易、S2-017 `parking-queries` 讀取最新已發布快照、S2-018 受控刷新與 Redis 協調。

此組尚未 coding。目標是讓主系統自行取得新北市官方即時資料，經正規化、品質檢查與快照發布後，讓使用者查詢讀到可信的目前車位狀態，同時避免多人查詢放大官方 API 呼叫。`parking-data-collector` 仍維持暫緩，且不參與使用者查詢。


## 2026-09-26 S2-014 新北市官方即時來源 Client 初版完成

已在 `feature/s2-014-ntpc-realtime-client` 實作主系統的新北市官方即時來源 client 初版。新增 `NtpcOpenDataProperties` 與 `NtpcOpenDataClientConfig`，可用 YAML／環境變數設定 base URL、timeout、最大嘗試次數、是否允許外部呼叫，以及路邊／路外 dataset id 與 JSON path。新增 `adapter.ntpc` client 與原始 DTO，分別處理路邊停車空位與公有路外停車場即時剩餘車位。

此階段只做呼叫與解析邊界，不寫入 MySQL、不發布快照、不接前端、不做 ML 訓練資料，也不判斷原始狀態碼代表可停或不可停。失敗時會丟出 `NtpcOpenDataClientException`，讓後續 S2-016／S2-018 決定如何記錄 ingestion run、冷卻與降級。

驗證結果：Docker Maven 後端測試通過 20 tests／0 failures。新增測試涵蓋原始 DTO JSON 解析、路邊／路外 client URL 與分頁參數、`AVAILABLECAR`／`AVAILABLE` alias、HTTP 失敗重試、外部呼叫停用，以及非法 page／size。自動測試使用 fixture 與 mock HTTP，未實際呼叫新北市 API。


## 2026-09-28 S2-015 即時資料正規化初版完成

已在 `feature/s2-015-ntpc-normalization` 實作新北市即時資料正規化初版。新增 `NormalizedAvailabilityObservation` 作為來源 DTO 與後續快照寫入之間的統一觀測物件，並新增 `NtpcAvailabilityNormalizer` 將新北市路邊與路外原始資料轉成統一狀態。

初版規則：路邊 `cellstatus=0` 轉為 `AVAILABLE`，`cellstatus=1` 轉為 `OCCUPIED`，未知或缺值不推論；路邊格位只有在文字可確認為「小型車」或「汽車」時才可成為目前可推薦格位。路外 `AVAILABLECAR > 0` 轉為 `AVAILABLE`，`0` 轉為 `FULL`，`-9` 轉為 `OUT_OF_SERVICE` 並保留空的 `availableCarSpaces`，非數字或缺值轉為 `UNKNOWN` 或 `FAIL`。

此階段不寫入 MySQL、不發布快照、不判斷資料過期、不接前端、不觸發官方 API。驗證結果：Docker Maven 後端測試通過 29 tests／0 failures；新增 9 個 normalizer 測試涵蓋有效值、有效 0 位、缺值、特殊值、未知狀態碼、非數字與汽車資格未知時不可推薦。

## 2026-09-28 S2-016 候選快照寫入與發布交易初版完成

已在 `feature/s2-016-snapshot-publishing` 實作 `AvailabilitySnapshotPublishingService`，作為來源資料正規化後、使用者查詢讀取已發布快照前的內部發布交易。此 service 會建立 `ingestion_run`、候選 `availability_snapshot`，依 `source_mapping` 將正規化觀測寫入 `availability_observation`，品質通過後才把快照切為 `PUBLISHED` 並更新 `source_state.active_snapshot`。

初版品質規則：整批沒有資料、來源不一致、或沒有任何觀測能對應到已驗證停車位置時，不發布新快照；已存在的 active snapshot 不會被覆蓋。個別資料若已被正規化為 `UNKNOWN`／`FAIL`，且能對應到停車位置，會保留為觀測資料並讓整批快照成為 `PARTIAL_PASS`，避免把未知值誤當成 0 或可停。

為支援交易狀態更新，補上 `IngestionRunEntity#setRunStatus` 與 `SourceStateEntity#setUpdatedAt`。S2-016 不包含排程、Redis 刷新鎖、前端顯示、官方 API 實際擷取流程或 `parking-queries` 讀取最新快照；下一步 S2-017 才會把已發布快照接回使用者查詢結果。

驗證結果：Docker Maven 後端全測試通過 33 tests／0 failures；新增 `AvailabilitySnapshotPublishingServiceTest` 4 tests 覆蓋成功發布、失敗不覆蓋舊快照、未知觀測保留、空資料拒絕發布。另以臨時 MySQL 8.4 容器載入 V1 migration 與 seed，執行 `AvailabilitySnapshotPublishingServiceTest` 通過 4 tests／0 failures，驗證交易與查詢在 MySQL 上可運作。

## 2026-09-28 S2-017 parking-queries 讀取最新已發布快照初版完成

已在 `feature/s2-017-query-published-snapshot` 實作使用者查詢讀取已發布快照的第一版。新增 `PublishedAvailabilitySnapshotService`，只讀 `source_state.active_snapshot` 指向的 `PUBLISHED` snapshot，並依停車位置 ID 讀取對應 `availability_observation`；`CANDIDATE` 與 `REJECTED` snapshot 不會出現在使用者查詢結果。

`POST /api/v1/parking-queries` 現在會在本地 300 公尺停車目錄結果上補上目前車位狀態。路邊只在新鮮 `READY` 且觀測為 `AVAILABLE`、`availableSpot=true` 時列入可導航格位；快照超過 5 分鐘會回 `STALE`，主要目前可用數為 `null`，不再把舊有位格位當成目前可推薦導航。路外停車場新增 `availabilityStatus`、`systemCollectedAt` 與 `message`，能分開表示 `AVAILABLE`、有效 `FULL/0`、`UNKNOWN`、`STALE`、`NO_OBSERVATION` 與 `NO_REALTIME_SNAPSHOT`。

S2-017 不會觸發新北市官方 API、Google ETA、Redis 刷新鎖或 ML 預測；手動 refresh 仍只回覆目前 query 狀態，真正來源級受控刷新留到 S2-018。現階段 service 名稱仍保留 `ParkingQueryMockService`，避免在本 Issue 混入命名重構；後續可獨立整理。

驗證結果：後端 Maven 測試通過 37 tests／0 failures。新增 controller 測試覆蓋無已發布 snapshot、讀取新鮮路邊／路外已發布快照、有效 0 位、UNKNOWN、STALE，以及既有讀取／刷新與範圍外無目錄結果。另以臨時 MySQL 8.4 容器載入 V1 migration 與 seed，執行 `ParkingQueryControllerTest` 通過 7 tests／0 failures，驗證已發布快照查詢在 MySQL 上可運作。

## 2026-09-28 GitHub 文件語言規則更新

Sponsor 指定後續 GitHub Issue、Pull Request、PR 說明、Issue 內容、驗收標準、測試方式與 GitHub 留言都使用繁體中文。已更新 `AGENTS.md` 與 `docs/development-workflow.md`；程式碼、API 欄位、套件名稱、錯誤碼、commit message 或必要技術名詞可保留英文。

## 2026-09-29 S2-018 受控刷新與 Redis 協調完成

已在 `feature/s2-018-refresh-control` 實作來源資料集層級的受控刷新。手動刷新與頁面可見時的自動刷新可共用同一個 `SourceRefreshCoordinationService`；目前 `POST /api/v1/parking-queries/{queryId}/refresh` 已透過它依序檢查路邊與路外資料集，再重新讀取最新的 `PUBLISHED` snapshot。

協調流程先以 MySQL `source_state.next_allowed_refresh_at` 檢查 cooldown，再使用 Redis `SETNX` 短鎖避免多個使用者或執行個體同時呼叫同一官方來源。Redis 失效時，單機會降級為本機鎖；回應會明確標示為 `*_WITH_LOCAL_LOCK`，不會把快取或舊資料冒充成新資料。來源失敗、品質拒絕或頁數上限導致資料可能截斷時，不發布新快照，也保留既有已發布快照；品質拒絕的失敗次數只由發布服務累計一次。

設定由 YAML／環境變數提供：`PARKING_REFRESH_SOURCE_COOLDOWN`（預設 5 分鐘）、`PARKING_REFRESH_LOCK_TTL`（預設 90 秒）、`PARKING_REFRESH_PAGE_SIZE` 與 `PARKING_REFRESH_MAX_PAGES`。這些是 MVP 初始值，仍需依正式來源的回應時間、資料量與呼叫限制量測調整。前端自動刷新觸發與 Docker Compose 的 Redis 正常／停用人工驗證，保留到本 Issue 的 Human-in-the-loop 驗收。

驗證：Docker Maven 完整後端測試 44 tests／0 failures／0 errors；新增測試覆蓋 cooldown、Redis 不可用時的本機鎖降級、同時刷新共用一輪、來源失敗、發布拒絕時不重複累計失敗次數，以及資料頁數可能截斷時拒絕發布。未在測試中呼叫真實新北市官方 API。

## 2026-09-29 S2-018 Human-in-the-loop 驗收完成，PR #30 待合併

已對 PR #30 執行 Docker Compose 人工驗收。驗收時先發現 API image build 會在 `dependency:go-offline` 解析 Redis/Lettuce 的舊 optional transitive descriptor `org.iq80.snappy:snappy:0.4` 時失敗；已移除 Dockerfile 的預下載步驟，改由正式 `package` 階段解析實際建置所需相依。接著發現 Compose 雖啟動 Redis container，但 API 未注入 Spring Redis host，容器內會連到 `localhost:6379` 而非 `redis:6379`；已在 `docker-compose.yml` 補上 `SPRING_DATA_REDIS_HOST=redis` 與 `SPRING_DATA_REDIS_PORT=6379`。

隔離 Compose project `s2018hit` 驗收結果：MySQL、Redis、API 均 healthy，`/actuator/health` 回 `UP`。Redis 正常時，手動刷新回 `FAILED`（不帶 `_WITH_LOCAL_LOCK`），路邊因超過頁數上限拒絕發布不完整快照，路外因沒有目錄 mapping 拒絕發布；查詢結果仍為 `NO_CATALOG_RESULTS`，目前可用數維持 `null`，未把舊資料或假資料當成最新資料。MySQL 驗證顯示 `source_state.next_allowed_refresh_at` 已寫入，路外只有 `REJECTED` 候選快照，沒有 `PUBLISHED` 假快照。立即再次刷新回 `COOLDOWN`，未重複呼叫來源。

停止 Redis 後，先將隔離測試庫的 cooldown 調回過去，再觸發手動刷新；回應明確標示 `FAILED_WITH_LOCAL_LOCK`，同樣保留既有已發布快照、不發布不完整或無 mapping 的新快照。回歸測試使用 Docker JDK 執行 `./mvnw test`，結果 44 tests／0 failures／0 errors。PR #30 目前仍未合併；下一步是將本次驗收修正推上 `feature/s2-018-refresh-control`，確認 PR 檢查後再合併 main。

## 2026-09-29 S2-018 合併完成，第三組官方來源基礎收尾

PR #30 已合併回 `main`，merge commit 為 `cf8ff2af0a3e9a2b91569b8a51d2c192c8a5e83d`。本機 `main` 已同步到 `origin/main`，並已刪除 `feature/s2-018-refresh-control` 本機與遠端分支。Sprint 2 第三組 S2-014～S2-018 已完成：新北市官方來源 client、正規化、候選快照發布、使用者查詢讀取 PUBLISHED snapshot，以及來源受控刷新與 Redis 協調。

目前主查詢仍使用 mock ETA，前端尚未接 Google Maps／Places／Routes，也尚未接頁面可見時自動刷新；後端已具備 `TriggerType.VISIBLE_AUTO_REFRESH` 與共用刷新協調能力。下一步規劃 Google Places／Routes 的真實 ETA，接著前端 Google Maps 結果頁與可見頁自動刷新。

## 2026-09-29 S2-019 Google ETA Issue 建立

已建立 GitHub Issue S2-019：接入 Google Places／Routes 真實 ETA。範圍聚焦後端 Google Routes API `computeRoutes` client、ETA service、mock ETA 替換與降級狀態；不包含前端 Google Maps JavaScript API、Place Autocomplete Widget 或地圖結果頁，這些保留後續 Issue。Issue 明確要求不提交真實 Google API key，測試以 mock HTTP／fixture 覆蓋成功、timeout、4xx／5xx、外部呼叫停用與無 API key 情境。

## 2026-09-29 S2-019 後端 Google Routes ETA 初版完成

已在 `feature/s2-019-google-eta` 實作後端 Google Routes ETA 初版。新增 `GoogleRoutesProperties`、`GoogleRoutesClientConfig`、`adapter.google` client/DTO/exception 與 `GoogleEtaService`，使用 Routes API `computeRoutes`，以 `X-Goog-FieldMask=routes.duration,routes.staticDuration,routes.distanceMeters` 只取 ETA 需要欄位，預設 `travelMode=DRIVE`、`routingPreference=TRAFFIC_AWARE_OPTIMAL`。`GOOGLE_ROUTES_SERVER_API_KEY` 只由環境變數注入，不提交真實金鑰；`.env.example` 與 `docker-compose.yml` 僅加入變數名稱與安全預設。

`POST /api/v1/parking-queries` 已停止產生固定 18 分鐘 `MOCK_GOOGLE_ROUTES`。建立查詢時會呼叫 `GoogleEtaService`；成功時 `eta.provider=GOOGLE_ROUTES`、`eta.status=READY`，並以 Google 回傳 `duration` 推算 `estimatedArrivalAt`。外部呼叫停用、未設定 API key、Google HTTP 失敗或無路線時，`eta.status=FAILED`，`durationSeconds` 與 `estimatedArrivalAt` 保持 `null`；目前停車觀測仍照常回傳，`predictions.targetArrivalAt` 不再用假 ETA。手動刷新仍保留原查詢 ETA，不重算 Google Routes。

驗證結果：使用 Docker JDK 在正式專案執行完整後端測試，通過 51 tests／0 failures／0 errors。新增測試涵蓋 Google Routes 請求 body、API key 與 field mask header、成功 duration 解析、外部呼叫停用、無 API key、HTTP 500、無 routes，以及 ETA service 成功與失敗降級。測試全部使用 mock HTTP／mock service，未呼叫真實 Google API。

邊界：S2-019 仍不包含前端 Google Maps JavaScript API、Place Autocomplete Widget、結果頁地圖、頁面可見時自動刷新前端接線、Google 金鑰限制驗收或真實 API 人工呼叫。下一步是建立 PR，並在 Sponsor 提供受限 server key 的本機環境中做 Human-in-the-loop 驗收：外部呼叫停用、無 key、mock/測試、以及有 key 時的一次小量 Google Routes 手動驗證。

## 2026-09-29 S2-019 PR #32 已建立，待人工驗收

已將 `feature/s2-019-google-eta` 推上 GitHub，並建立 PR #32：<https://github.com/liujiawei-tw/taiwan-parking-forecast/pull/32>。PR 目前包含 1 個 commit：`6cc9385 feat: integrate google routes eta`，並連結 S2-019 Issue。下一步是針對 PR #32 做 Human-in-the-loop 驗收：使用真實且受限的 `GOOGLE_ROUTES_SERVER_API_KEY` 驗證 `eta.status=READY`，再確認外部呼叫停用或無 key 時回 `eta.status=FAILED` 且不產生假抵達時間。

## 2026-09-29 S2-019 PR #32 部分人工驗收完成

已使用隔離 Docker Compose project `s2019hit` 啟動 MySQL、Redis、API 與 Web，`/actuator/health` 回 `UP`。在沒有 `GOOGLE_ROUTES_SERVER_API_KEY` 的本機環境完成兩個降級驗收：第一，預設 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=false` 時建立查詢，`eta.status=FAILED`、`eta.provider=GOOGLE_ROUTES`、`estimatedArrivalAt=null`、`durationSeconds=null`，訊息明確指出外部呼叫被設定停用；第二，重建 API 並設定 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=true` 但 server key 為空時，建立查詢同樣回 `eta.status=FAILED`，訊息明確指出 Google Routes API key 未設定，沒有填入假 ETA。

本次驗收也確認停車觀測區塊仍照常回應，不會因 Google ETA 降級而失敗；隔離 Compose 驗證後已執行 `docker compose -p s2019hit down -v` 清理資源。尚未完成真實 Google Routes `READY` 路徑，因目前執行環境沒有可用的受限 `GOOGLE_ROUTES_SERVER_API_KEY`；PR #32 合併前仍需由 Sponsor 在本機或安全環境補做一次小量真實 Routes 呼叫驗收。

## 2026-09-29 S2-019 真實 Google Routes 驗收通過

Sponsor 已在本機 `.env` 設定不同用途的 Google Maps Platform key，並將 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=true`。使用隔離 Docker Compose project `s2019real` 啟動 API 後，第一次真實驗收發現 Google Routes 回 HTTP 400；直接以同一把 server key 與同一組座標呼叫 `computeRoutes` 且不帶 `departureTime` 可成功，確認問題不是 key、API restriction、座標或 field mask，而是 NOW 查詢送出了測試環境時間 `departureTime`。

已修正 Routes 呼叫策略：`NOW` 查詢不送 `departureTime` 給 Google Routes，由 Google 使用當下時間；`SCHEDULED` 查詢才送使用者指定的 `departureTime`。修正後新增／更新自動測試，完整後端測試通過 53 tests／0 failures／0 errors。

重建隔離 Compose API 後再次建立新莊查詢，真實 Routes 驗收通過：`eta.status=READY`、`eta.provider=GOOGLE_ROUTES`、`durationSeconds=1218`、`estimatedArrivalAt` 有值，`predictions.targetArrivalAt` 同步使用該抵達時間。停車觀測區塊仍正常回傳；驗收後已執行 `docker compose -p s2019real down -v` 清理資源。驗收完成後，本機 `.env` 可將 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED` 改回 `false`，避免後續開發誤耗 Google 額度。

## 2026-09-29 S2-019 合併完成，後端 Google ETA 收尾

PR #32 已合併回 `main`，merge commit 為 `f4612b86b7f48fd9a5c19132deea44d4d1077d04`，並關閉 S2-019 Issue。本機 `main` 已同步到 `origin/main`，並已刪除 `feature/s2-019-google-eta` 本機與遠端分支；殘留的本機 `feature/s2-018-refresh-control` 也已清理。

S2-019 完成後，建立查詢已可使用 Google Routes 真實 ETA，並在外部呼叫停用、無 key 或 Google 失敗時明確降級，不再產生固定 18 分鐘 mock ETA。下一步轉向前端 Google Maps／Places 整合、結果頁地圖，以及把 S2-018 已提供的 `TriggerType.VISIBLE_AUTO_REFRESH` 接到前端頁面可見時自動刷新。

## 2026-09-29 S2-019 外部呼叫關閉回歸驗收與狀態頁重整

Sponsor 已將本機 `.env` 的 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED` 改回 `false`。使用隔離 Docker Compose project `s2019off` 重新啟動 API 驗證：`/actuator/health` 回 `UP`；建立新莊查詢時，即使 `GOOGLE_ROUTES_SERVER_API_KEY` 仍有值，系統仍不呼叫 Google Routes，回應 `eta.status=FAILED`、`eta.provider=GOOGLE_ROUTES`、`estimatedArrivalAt=null`、`durationSeconds=null`，訊息明確指出外部呼叫被設定停用；`predictions.targetArrivalAt` 也維持 `null`。驗收後已執行 `docker compose -p s2019off down -v` 清理資源。

已重整 `docs/project-status.html`，移除原本分散且重複的歷史段落，改成單頁專案進度儀表板：關鍵狀態摘要、開發進度總覽、20 天目標排程視圖、後端／前端／預測三條工作線、Sprint 2 Issue 進度地圖、下一步建議與驗收提醒。此頁目前聚焦高層摘要，詳細歷史仍以 `current-state.md`、`decisions.md`、`implementation-plan.md` 與 `roadmap.md` 為準。

## 2026-09-29 下一步規劃完成，尚未開始實作

已完成現況盤點與下一步排序。盤點確認 S2-014～S2-019 的後端能力已在 `main`，但資料庫仍只有 3 筆路邊、2 筆路外的合成 seed 目錄，且不是新北市官方來源的完整 `source_mapping`。因此下一個實作增量應先建立可追溯的正式 catalog／mapping 與低頻來源量測，再處理查詢契約、Google Maps／Places 結果頁與前端受控刷新；預測仍以資料品質與回測為進入門檻。

本次只更新規劃文件與進度摘要，不修改產品程式、不呼叫官方來源，也不啟動模型訓練。規劃明確要求保留來源觀測、快照、mapping 與模型推論的邊界；資料不足時不得輸出機率或把未知值當 0。

## 2026-09-29 下一組 GitHub Issues 已建立

已在 GitHub 建立並加上 `sprint-2` 標籤：S2-020 建立新莊正式停車目錄與來源 ID 對應、S2-021 量測官方即時來源與設定發布品質門檻、S2-022 收斂停車查詢生命週期與 API 契約、S2-023 接入 Google Maps／Places 與停車結果頁、S2-024 接入可見頁受控刷新與端到端驗收、S2-025 校正 Sprint 2 文件基線與 Sprint 3 預測進入條件。順序先確保正式 catalog、mapping 與量測證據，再進行 API 與前端整合；各 Issue 皆明確排除以 collector 當主系統即時來源或在資料不足時輸出預測機率。

## 2026-09-29 Repo 複查與文件進度同步

本次重新閱讀 `main` 的後端、前端與核心文件後，確認 S2-001 至 S2-019 的程式能力與既有驗收紀錄一致：後端已有資料模型、少量合成 seed、300 公尺查詢、來源 adapter／快照發布、受控刷新，以及 Google Routes ETA 與降級。最近一次已記錄的完整後端驗證為 Docker Maven 53 tests／0 failures／0 errors；本次為文件盤點，未重新執行測試或呼叫外部 API。

同時確認目前不能宣稱正式即時停車服務已完成：目錄只有 3 筆路邊與 2 筆路外合成 seed，正式 source mapping 與來源量測尚未完成；前端仍採固定 mock 起點與佔位地圖，尚未接 Maps JavaScript／Places 或可見頁自動刷新。已同步更新根目錄與前後端 README、實作計畫、路線圖、待確認問題及專案狀態頁；S2-020 至 S2-025 保持為下一批尚未開始的工作。

## 2026-09-29 S2-020 官方目錄小樣本基線完成

已完成 S2-020 初版，將 `R__seed_xinzhuang_parking_catalog.sql` 從 S2-011 的 `SYSTEM` 合成示範資料改為可追溯的新北市官方 Open Data 小樣本。新 seed 目前包含 7 筆新莊路邊一般汽車格位與 3 筆新莊路外汽車停車場，`source_mapping.source_system=NTPC_OPEN_DATA`，路邊使用官方路邊資料的 `id` 作來源 key，路外使用官方路外靜態與即時剩餘位共用的 `ID` 作來源 key。舊的 `SEED-XINZHUANG-*` 合成 mapping 會在 repeatable migration 中清除。

本次小量官方核對結果：新北市路邊資料完整回應約 30,941 筆，其中新莊 `areacode=65000050` 有 2,124 筆；路外靜態目錄前 1000 筆中篩到 97 筆新莊汽車場，且這 97 個 ID 均能在路外即時剩餘位資料中對上。官方 API 的 `filter` 參數在本次測試未實際套用，因此目前先以小量全回應後本機篩選作證據，不把平台篩選能力視為已確認。路外 TWD97 座標已轉為 WGS84 寫入 seed，原始 TWD97 值保留在 mapping hint。

同時修正 `NtpcAvailabilityNormalizer` 的路邊來源 ID 選擇：優先使用官方 `id`，缺值時才退回 `cellid`，避免 `cellid` 在不同路段重複造成誤合併。查詢回應中的本地目錄來源標示改為 `LOCAL_NTPC_CATALOG`，並更新 repository、nearby search、controller 與 normalizer 測試。驗證使用 Docker JDK 執行精準後端測試：`NtpcAvailabilityNormalizerTest`、`XinzhuangSeedRepositoryTest`、`NearbyParkingSearchServiceTest`、`ParkingQueryControllerTest`，共 23 tests／0 failures／0 errors；完整後端 `./mvnw test` 通過 54 tests／0 failures／0 errors。

限制：這仍不是完整新莊正式 catalog，不可宣稱覆蓋 2,124 格或 97 場；目前也沒有把路外靜態匯入流程自動化，沒有新增官方 API 高頻呼叫或模型訓練資料。下一步仍需在 S2-021 量測完整性、分頁、資料量、更新耗時與發布品質門檻，再決定是否匯入完整目錄或建立正式 importer。

## 2026-09-29 S2-021 官方來源量測摘要基線實作

已開始 S2-021 並完成主系統量測摘要基線。`NtpcOpenDataClient` 保留既有 List 回傳方法，另新增 page metadata 版本，記錄單頁 rows、attempts、UTF-8 response bytes 與 client 端 elapsedMillis。`NtpcRealtimeSourceRefreshService` 已改用 page metadata 累計 pages、HTTP attempts、responseBytes 與分頁完整性；若超過 `maxPages`，錯誤訊息會明確包含 dataset、pages、httpRequests、pageSize、maxPages、responseBytes 與 `REJECT_INCOMPLETE_PAGE_TRAVERSAL`，不會進入發布。

新增 `SourceMeasurementService` 將每輪觀測整理成 `sourceMeasurement{...}` 摘要，包含 retries、缺 source ID、重複 source ID、verified mapping 覆蓋、unmapped、failed observation、mappingCoverage 與發布建議。`AvailabilitySnapshotPublishingService` 會將此摘要保存到 `ingestion_run.quality_summary`；`availability_snapshot.quality_reason` 仍保留發布層的簡短 normalized／published／unmapped／failedObservations 結果，避免把量測摘要當成使用者查詢契約。

驗證：Docker Maven 精準測試 `NtpcOpenDataClientTest`、`SourceMeasurementServiceTest`、`NtpcRealtimeSourceRefreshServiceTest`、`AvailabilitySnapshotPublishingServiceTest` 通過 12 tests／0 failures／0 errors；完整後端 `./mvnw test` 通過 56 tests／0 failures／0 errors。測試覆蓋 page metadata、bytes／attempts、缺頁拒絕發布、mapping 覆蓋、缺 ID、重複 ID、failed observation 與量測摘要保存。

限制：本階段沒有高頻呼叫官方 API，也沒有執行多輪真實 refresh；S2-020 的低頻官方核對顯示路邊全量約 30,941 筆，以目前 `page-size=500`、`max-pages=20` 會超過上限，因此主系統會拒絕發布不完整路邊快照。正式調整 page-size、max-pages、cooldown 與品質門檻仍需 Sponsor 確認可接受用量後，再做低頻完整量測。

## 2026-09-29 S2-021 單輪真實來源量測紀錄

依 Sponsor 指示補上路邊／路外各一輪可重現的真實量測紀錄，詳見 `realtime-source-measurement.md`。本輪只呼叫新北市官方 Open Data API，不保存原始 JSON，不提交 API 回應內容，也沒有使用金鑰。

路邊格位即時狀態使用 `page=0&size=40000`，HTTP 200，1 request，1 page，30,941 rows，11,270,904 bytes，elapsed 4,600 ms；因 rows 小於 requested size，本輪視為取齊。新莊 `areacode=65000050` 有 2,124 rows；全量 source ID 中有 30,653 unique IDs、0 missing source ID、288 duplicate rows。S2-020 seed 的 7 個 verified mapping ID 本輪皆找到。

路外即時剩餘車位第一次 `size=40000` 只回 1,000 rows，因此以 `size=1000` 做分頁確認：page 0 有 1,000 rows、page 1 有 407 rows，共 2 requests、1,407 rows、50,642 bytes、elapsed 1,011 ms，本輪視為取齊。source ID 有 1,396 unique IDs、0 missing source ID、11 duplicate rows；`AVAILABLECAR`／`AVAILABLE` 缺值 0，負值 981，仍須依官方碼表分辨語意。S2-020 seed 的 3 個 verified mapping ID 本輪皆找到。

驗收判斷：缺頁、重複、來源欄位異常、部分 mapping 與發布建議已能分開記錄；一輪品質拒絕不覆蓋既有 PUBLISHED snapshot 由既有測試保護。設定建議仍保守：現行 `page-size=500`、`max-pages=20` 對路邊全量不足，應繼續拒絕不完整路邊快照；路外現行設定理論上可取齊，但官方平台對大 size 可能限制為 1,000 rows，不可假設單頁放大。這仍是單輪量測，尚不能建立 p50／p95、長期穩定性或正式 cooldown。

## 2026-09-29 S2-022 查詢生命週期與 API 契約基線實作

已開始 S2-022 並完成第一輪後端契約收斂。`ParkingQueryMockService` 已改名為 `ParkingQueryService`，查詢生命週期不再以 mock 命名；建立查詢會驗證 `requestToken`、起訖點座標、目的地是否在新莊 MVP 範圍，以及 `NOW`／`SCHEDULED` 出發時間語意。同一個有效 `requestToken` 會回同一個既有 `queryId`，避免網路重試或連點建立重複查詢。

HTTP 錯誤回應新增固定 `error` 物件，包含 `code`、`message`、`details`、`retryable`、`nextAllowedAt` 與 `serverTime`。目前已實作 `INVALID_COORDINATE`、`INVALID_REQUEST_TOKEN`、`UNSUPPORTED_AREA`、`INVALID_DEPARTURE_TIME`、`QUERY_NOT_FOUND`、`QUERY_EXPIRED` 與 JSON／Bean validation 的 `INVALID_REQUEST`；前端可依錯誤碼處理流程，不需解析文字訊息。`GET /api/v1/parking-queries/{queryId}` 仍為只讀；`POST /refresh` 只走來源刷新協調並沿用原 Google ETA，不重算 Routes。

目前 `queryId` 有效期實作為建立後 30 分鐘。正式預設使用 Redis query state：`parking:query:{queryId}` 保存短期查詢 response 與原始 request，預設保留 60 分鐘，前 30 分鐘有效、後 30 分鐘用於穩定回 `410 QUERY_EXPIRED`；`parking:query-token:{requestToken}` 保存防重送索引，TTL 為 30 分鐘，避免舊 token 阻擋重新規劃。test profile 使用 memory store，避免單元測試依賴外部 Redis。Redis query state 不可用時，主系統回 `503 SERVICE_TEMPORARILY_UNAVAILABLE` 與 `details.component=redis_query_state`，不以單機 map 冒充多實例可恢復狀態。MySQL 仍保存停車目錄、快照、觀測與來源狀態，不保存使用者查詢生命週期。

驗證：使用本機 Maven 執行精準後端測試 `ParkingQueryControllerTest`、`ParkingQueryServiceTest`、`RedisParkingQueryStateStoreTest`，共 18 tests／0 failures／0 errors。測試覆蓋重複 `requestToken`、無效座標、區外目的地、過去指定出發時間、未知 queryId、refresh 未知 queryId、可控時鐘下的過期 queryId、Redis TTL 寫入、Redis JSON 還原與 Redis 失敗包裝，以及既有查詢讀取／刷新不重算 ETA。完整後端 `mvn test` 通過 67 tests／0 failures／0 errors；Docker Maven `maven:3.9-eclipse-temurin-21 mvn test` 也通過 67 tests／0 failures／0 errors。

## 2026-09-29 S2-023 Maps／Places 前端結果頁初版

已開始 S2-023 並完成第一版前端整合。Vue 單頁不再是固定 mock 目的地與佔位地圖；有 `VITE_GOOGLE_MAPS_BROWSER_API_KEY` 時會載入 Google Maps JavaScript API 與 Place Autocomplete Widget (New)，選取目的地後只取 `displayName`、`formattedAddress`、`location` 等前端需要欄位，並在使用者明確按下「規劃停車查詢」時才把起點、目的地座標與出發時間送到主系統 `POST /api/v1/parking-queries`。前端不提交自算 ETA、目前車位數或預測機率，也不直接呼叫政府停車來源。

結果頁改為地圖優先布局：地圖呈現目的地、300 公尺圈、路外停車場標記，以及後端回傳且 `availableSpots` 內的路邊目前有位格位標記；右側結果面板顯示 query／ETA／預測狀態、路邊與路外資料時間、全部結果清單與外部 Google Maps 路線入口。路邊格位文案明確標示只代表目前觀測，不代表抵達時仍有位；抵達時機率仍依後端 `predictions` 區塊呈現資料不足或未完成狀態。

未設定瀏覽器 Google key 時，前端維持手動座標 fallback，CI 與一般本機 build 不會呼叫 Google。此階段尚未接 S2-024 的頁面可見時自動刷新，也尚未做真實 browser key 的人工 Places 查詢驗收；Google 金鑰限制、Places 實際用量與手機真機可用性仍需 Sponsor 在安全本機環境確認。

## 2026-09-29 S2-024 可見頁受控刷新初版

已開始 S2-024 並完成前後端接線。`POST /api/v1/parking-queries/{queryId}/refresh` 新增 `trigger` query parameter，預設維持 `MANUAL_REFRESH`；前端可見頁自動刷新使用 `VISIBLE_AUTO_REFRESH`。Controller 只允許 `MANUAL_REFRESH` 與 `VISIBLE_AUTO_REFRESH`，其他 trigger 會回穩定錯誤 `INVALID_REFRESH_TRIGGER`，避免瀏覽器端繞過主系統來源刷新用途。`ParkingQueryService` 會把 trigger 傳給 `SourceRefreshCoordinationService`，讓 `ingestion_run.trigger_type` 能分辨手動與可見頁自動刷新。

前端在結果頁有 queryId 後監聽 `visibilitychange`：頁面隱藏時停止進度輪詢與自動刷新；頁面重新可見時先用 `GET /api/v1/parking-queries/{queryId}` 只讀恢復狀態，再依最新 `roadside.status`／`offStreet.status` 判斷。若來源狀態為 `STALE` 或 `NO_REALTIME_SNAPSHOT`，且目前沒有 `refreshInProgress`，同一 queryId 與來源狀態組合只送一次 `VISIBLE_AUTO_REFRESH`。冷卻、失敗或仍無 snapshot 時不靠定時器密集重送；使用者手動「更新車位」仍可發起 `MANUAL_REFRESH` 檢查，且同樣受後端 cooldown、Redis 鎖與來源限制。

驗證：前端 `npm run build` 通過；後端精準測試 `ParkingQueryControllerTest`、`ParkingQueryServiceTest` 通過 16 tests／0 failures／0 errors。此階段未做真實官方來源高頻刷新驗收，也未宣稱 5 分鐘門檻已是正式服務承諾；它只是把既有後端受控刷新能力接到前端可見頁生命週期。

## 2026-09-29 S2-025 文件基線與 Sprint 3 預測進入條件

已開始 S2-025 並完成文件基線校正。新增 `sprint-3-entry-criteria.md`，明確區分目前已完成的主系統能力、仍未完成的資料與人工驗收，以及 Sprint 3 可以開始預測工作的硬性門檻。預測機率輸出前至少需要可重現多輪來源量測、清楚的 catalog／mapping 覆蓋界線、與線上 300 公尺定義一致的訓練標籤、無時間洩漏的回測、基準模型比較、完整推論契約與可回滾模型版本。

同步更新 README、backend README、roadmap、implementation plan、open questions 與 project status，移除 S2-020 至 S2-025 尚未開始、前端仍為 mock、目錄仍為合成 seed 等過時敘述。當時基線是：S2-020 至 S2-024 已完成官方小樣本 mapping、單輪來源量測、Redis query state、Maps／Places 結果頁與可見頁受控刷新接線；但完整新莊 catalog、多輪 p50／p95、真實 browser key Places 人工驗收、端到端官方刷新人工驗收及兩類預測模型仍未完成。2026-10-01 後多輪官方 API 下載段量測已補齊，端到端刷新寫入與發布量測仍未完成。

驗證：本次為文件任務，未修改產品程式、未呼叫外部 API、未保存原始回應或敏感設定。使用 `git diff --check` 做格式與空白檢查；不重新跑 Maven／前端 build，因為行為程式碼未變更。

## 2026-09-29 Sprint 3 下一步規劃與 GitHub Issues 建立

依 Sponsor 指示「不實作，規劃接下來要的事情，並上傳到 GitHub」，本次只做規劃與任務建立，沒有修改產品程式、沒有呼叫官方來源、沒有訓練模型，也沒有新增任何預測機率。

已新增 `sprint-3-next-work-plan.md`，把 S2 hardening 與 Sprint 3 預測前置工作拆成六個 GitHub Issues：S2-HARDEN-001 補齊真實 Places 與可見頁刷新人工驗收、S2-HARDEN-002 完整新莊 catalog／source mapping 匯入策略與覆蓋報告、S2-HARDEN-003 多輪官方來源量測與刷新品質門檻、S3-001 已發布觀測匯出與標籤 dry run、S3-002 基準模型與時間切分回測報告、S3-003 預測推論契約降級與模型版本接點。

規劃順序維持保守：先補前端與刷新人工驗收，再確認 catalog／mapping 覆蓋與來源穩定性，之後才產生訓練標籤 dry run、做 baseline 回測，最後規劃推論契約接點。未完成 S2-HARDEN-002 至 S3-002 前，不應宣稱可輸出可信預測；未完成 S3-003 前，即使離線有模型結果，也不接入使用者查詢回應。

## 2026-09-30 S2-HARDEN-001 開始實作

已開始 S2-HARDEN-001。前端將查詢 payload 組裝、Google browser key 判斷與可見頁自動刷新決策抽成 `planner.js` 純函式，並新增 `npm run test` 的 Node 測試；GitHub Actions 前端 job 已加入 `npm run test`，使 payload 與自動刷新契約成為 PR 守門檢查。測試覆蓋：placeholder 或空 browser key 不載入 Google、查詢 payload 只包含起訖點座標／label／departure／requestToken、不包含 `placeId`、ETA、目前車位數或預測機率，以及頁面可見且來源狀態為 `STALE`／`NO_REALTIME_SNAPSHOT` 時同一 query/status 組合只觸發一次 `VISIBLE_AUTO_REFRESH`。

新增 `s2-harden-001-manual-acceptance.md` 作為人工驗收紀錄模板，列出無 key fallback、真實 Places 選取、可見頁受控刷新與手動刷新檢查步驟。當前執行環境沒有 `frontend/.env.local`，因此尚未執行真實 browser key Places 驗收；不可宣稱 S2-HARDEN-001 完成，只能宣稱自動化契約測試與人工驗收步驟已補上。

後續補上前端右側「人工驗收事件」面板，供本機驗收時記錄 Maps/Places 載入、Places 選取、查詢 payload 欄位摘要、`POST /parking-queries`、`GET /parking-queries/{queryId}` 與 `POST /refresh` 的狀態碼與 trigger。事件紀錄只保存狀態、trigger 與欄位摘要，不保存 Google key、requestToken、完整 request／response body 或官方原始回應；人工驗收文件已改用該面板作主要紀錄來源，DevTools Network 只作輔助確認。

使用 Sponsor 本機已設定的 browser-restricted Google key 進行 Docker smoke 驗證時，發現並修正兩個 Docker profile 啟動缺口：`application-docker.yml` 未納入 `db/seed` Flyway location，導致既有 MySQL volume 的 repeatable seed migration 驗證失敗；Redis query state 使用的 `ObjectMapper` 在 production context 未宣告 bean，導致 API 啟動失敗。修正後 Docker Compose 已可啟動 `api`、`web`、`mysql`、`redis`；`GET /actuator/health` 回 `UP`，前端 Docker bundle 確認含 Maps loader 與人工驗收事件面板，且本機 key 已進入 Docker web build。以 Docker API 送出一筆低成本查詢得到 `202` 與 queryId；因未啟用 Google Routes server key，ETA 回 `FAILED`，預測仍為 `INSUFFICIENT_DATA`，這符合目前不把估計冒充正式預測的限制。Docker 容器保留啟動，Sponsor 已接續完成瀏覽器人工驗收並回報 OK。

`s2-harden-001-manual-acceptance.md` 已依 2026-09-30 Sponsor 人工驗證結果補齊，涵蓋真實 Places 選取、payload 邊界、可見頁先 GET 再 `VISIBLE_AUTO_REFRESH`、手動 `MANUAL_REFRESH` 與不重算 Google Routes ETA。S2-HARDEN-001 可視為完成候選；文件只記錄結論、狀態碼摘要與邊界檢查，不保存金鑰、截圖、完整 request／response body 或官方原始回應。

驗證：`frontend/npm run test` 通過，`frontend/npm run build` 通過。未呼叫 Google、未呼叫新北市官方來源，未提交任何 key、原始 API 回應或敏感設定。

## 2026-09-30 S2-HARDEN-002 開始實作

已開始 S2-HARDEN-002。新增 `CatalogCoverageReportService`，以目前 MySQL catalog／source mapping 與 S2-020／S2-021 已量測的官方基準數產生新莊覆蓋報告。第一版固定目前事實：路邊 active catalog 7／官方新莊路邊基準 2,124，verified mapping 7／2,124，覆蓋率 0.33%；路外 active catalog 3／官方新莊汽車場基準 97，verified mapping 3／97，覆蓋率 3.09%。兩者狀態皆為 `PARTIAL`，因此不得宣稱完整新莊覆蓋，也不得輸出完整 300 公尺路邊區域機率。

新增 `s2-harden-002-catalog-coverage.md` 作為覆蓋報告與匯入策略文件，明確列出完整匯入前需要的可重跑輸入、catalog／mapping 分離、缺 ID／重複／座標異常／車種資格不明的分流，以及路邊 2,124、路外 97 的完成候選門檻。此階段未重新呼叫官方 API、未匯入全量 catalog、未保存原始回應；只把已量測基準轉為可測的覆蓋治理證據。

依 Sponsor 回饋，後續補上 source-agnostic `CatalogImportDryRunService`，避免把新莊寫成唯一規格。新的 dry-run 使用 `CatalogImportScope` 與共同 `CatalogSourceRecord`，由來源 adapter 先轉出 service area、source system、dataset、source ID、位置、車種資格與必要 raw hint；dry-run 再統計 scope rows、accepted candidates、missing source ID、duplicate source ID、invalid coordinates、unknown eligibility、existing verified mappings 與 import recommendation。新莊會是第一個 scope config，未來新增地區時應新增 scope 與 adapter，不複製新莊專用流程。

後續完成 NTPC catalog adapter 基線：`NtpcOpenDataClient` 新增新北市公有路外停車場靜態目錄 dataset `B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68`；新增 `NtpcOffStreetParkingLotDto` 解析 `ID`、`AREA`、`NAME`、`ADDRESS`、`TW97X`、`TW97Y`、`TOTALCAR`。`NtpcCatalogRecordMapper` 可把路邊即時來源列與路外靜態目錄列轉成共用 `CatalogSourceRecord`，由呼叫端傳入 `serviceArea`、路邊 `areacode` 或路外行政區名稱，因此新莊只是第一個 scope，不把規格寫死在 service 核心。路外 TWD97 座標會轉成 WGS84；解析失敗、缺 ID、重複 ID與車種資格不明仍交由 dry-run 分開計數，不會自動匯入 DB 或標為 verified mapping。

驗證：使用 Docker JDK 執行精準後端測試 `NtpcOpenDataDtoTest`、`NtpcOpenDataClientTest`、`NtpcCatalogRecordMapperTest`，共 11 tests／0 failures／0 errors；完整後端 `./mvnw test` 通過 76 tests／0 failures／0 errors／0 skipped。此階段未呼叫官方 API、未保存原始 JSON、未提交敏感設定，也尚未執行完整新莊 catalog 匯入。

再補上 NTPC catalog dry-run orchestration：新增 `NtpcCatalogImportProperties` 與 `NtpcCatalogDryRunService`，把 NTPC client、catalog mapper 與 generic `CatalogImportDryRunService` 串起來。`parking.catalog.ntpc` 可設定 page size、max pages 與多個 scope；預設 scope 是 `NEW_TAIPEI_XINZHUANG`／`65000050`／`新莊區`。dry-run result 會保留路邊與路外 catalog 的 pages、HTTP attempts、response bytes、elapsed millis 與 traversal 是否完整，達到 `max-pages` 仍滿頁時會標示 incomplete，避免把缺頁結果偽裝成完整 catalog。這仍只產生審查報告，不寫入 `parking_place`、`source_mapping` 或發布快照。

驗證：使用 Docker JDK 執行精準後端測試 `NtpcCatalogDryRunServiceTest`、`NtpcOpenDataClientTest`、`NtpcCatalogRecordMapperTest`、`CatalogImportDryRunServiceTest`，通過 16 tests／0 failures／0 errors；完整後端 `./mvnw test` 通過 79 tests／0 failures／0 errors／0 skipped。此階段仍未呼叫官方 API，也未提交任何原始回應或敏感設定。

2026-10-01 補齊 S2-HARDEN-002 驗收文件收斂：`s2-harden-002-catalog-coverage.md` 已列出完整 catalog 匯入候選方案，明確不採手寫全量 repeatable seed 或一次性 migration 作第一選擇，採「NTPC catalog dry-run → 人工審查摘要 → 受控管理命令匯入」方向。覆蓋規則也已拆成 catalog 覆蓋、mapping 覆蓋與 realtime 覆蓋三層；路邊 300 公尺區域預測只有在 catalog／mapping 完整、coverage boundary 可判斷、即時 snapshot 新鮮且模型回測通過時才可輸出 `READY`，否則必須以 `CATALOG_COVERAGE_PARTIAL`、`COVERAGE_BOUNDARY_UNKNOWN`、`REALTIME_SNAPSHOT_EXPIRED`、`MODEL_NOT_READY` 等原因拒絕或標示資料不足。同步更新 `decisions.md` 與 `open-questions.md`。本次為文件收斂，未修改產品程式、未呼叫官方 API、未提交原始回應或敏感設定。

## 2026-10-01 S2-HARDEN-003 多輪官方來源量測

已開始 S2-HARDEN-003 並完成低頻真實多輪量測紀錄。路邊格位即時狀態 5 輪皆使用新北市官方 Open Data JSON `page=0&size=40000`，每輪 1 request／1 page／30,941 rows／11,270,904 bytes；HTTP 成功 5/5，延遲 p50 5,279 ms、p95 8,575 ms。路外即時剩餘車位 5 輪皆使用 `size=1000` 取 page 0 與 page 1，每輪 2 requests／2 pages／1,405 rows；HTTP 成功 5/5，bytes p50/p95 50,592，延遲 p50 358 ms、p95 667 ms。

品質紀錄已分開記錄缺 source ID、重複 source ID、來源欄位異常、mapping 覆蓋與發布建議。路邊缺 source ID 0、重複 source ID rows 288、`cellstatus`／`parkingstatus` 缺值 0；S2-020 seed 的 7 個 verified mapping 皆仍可在官方即時回應找到，但全新莊 verified mapping 仍只有 7/2,124。路外缺 source ID 0、重複 source ID rows 11、`AVAILABLECAR` 缺值 0、負值 979；S2-020 seed 的 3 個 verified mapping 皆仍可在官方即時回應找到，但全新莊 verified mapping 仍只有 3/97。

設定建議已寫入 `docs/realtime-source-measurement.md`：路邊若沿用 JSON 全量擷取，可先以 `page-size=40000`、`max-pages=1` 作低頻驗證候選；路外 `page-size=1000`、`max-pages=3` 可取齊目前 1,405 rows，保守可留 `max-pages=5`。最低刷新間隔仍維持 5 分鐘暫定；失敗冷卻、前景等待上限、品質比例門檻與正式 SLA 仍未量到，不能偽裝成服務承諾。本輪只提交摘要與文件，不保存原始 JSON、不提交完整 request／response body、未使用金鑰。

## 2026-10-01 S3-001 標籤 dry run 初版

已開始 S3-001。新增 `TrainingLabelDryRunService`，第一版只讀 `snapshot_status=PUBLISHED` 且位於指定 `system_fetched_at` 時間窗內的 `availability_observation`，查詢條件採起點含括、終點不含括，避免相鄰批次重複取樣。不讀 `CANDIDATE`／`REJECTED` snapshot，不呼叫 Google、新北市官方 API 或 TDX，也不建立大型匯出檔。Repository 新增依 snapshot 狀態、dataset 與時間窗查詢已發布觀測的方法。

路邊 dry run 以目的地圓心、直線半徑、`serviceArea`、`ON_STREET_SPOT`、`ACTIVE`、汽車資格 `CONFIRMED` 與有效觀測比例產生區域候選樣本；若 catalog coverage 不是 `COMPLETE`，即使範圍內有目前可用格位，也會拒絕並記 `CATALOG_COVERAGE_PARTIAL`，避免把目前小樣本誤當完整 300 公尺區域標籤。路外 dry run 以單一 verified lot 的已發布觀測建立候選樣本，會分開拒絕類型錯誤、汽車資格不符、資料過期、品質不可用與未知可用數。

新增 `docs/s3-001-label-dry-run.md` 記錄 dry run 目標、資料來源、路邊／路外標籤規則、報告欄位與拒絕原因。驗證：Windows Maven wrapper 仍有既有問題，改用 Docker Maven 執行精準測試 `TrainingLabelDryRunServiceTest`，通過 3 tests／0 failures／0 errors；完整後端 `mvn -q test` 通過 82 tests／0 failures／0 errors。此階段不訓練模型、不輸出預測機率、不提交原始 API 回應或敏感設定。

## 2026-10-01 S3-002 baseline 回測初版

已開始 S3-002。新增 `BaselineBacktestService`，直接接收 S3-001 的 `LabelDryRunReport`，只使用 usable、sample time 與 positive label 皆完整的候選樣本，依時間順序切成 training／validation／test。第一版固定三個可解釋 baseline：訓練窗正樣本率、訓練窗多數類別、訓練窗最後已知標籤。路外同場站樣本可用同一 parking place 的訓練窗最後標籤；路邊區域樣本沒有場站 ID 時使用整體訓練窗最後標籤。

回測報告輸出 Brier score、log loss、accuracy、precision、recall 與 TP／FP／FN。接受口徑在看結果前定義：若 usable samples、training／validation／test split 不足、test split 只有單一類別、或最佳 test Brier score 高於預先設定門檻，報告一律建議 `INSUFFICIENT_DATA`。新增 `docs/s3-002-baseline-backtest.md` 記錄時間切分、baseline、指標與資料不足規則。

驗證：使用 Docker Maven 執行精準測試 `BaselineBacktestServiceTest`，通過 3 tests／0 failures／0 errors。此階段不接線上推論、不輸出正式預測機率、不呼叫官方 API、不提交原始 API 回應或敏感設定。

## 2026-10-24 S3-023 最小 UI 驗收續作

Chrome 本機 DEMO 桌面畫面確認 Places 目的地「雙鳳路」、測試起點、500 公尺查詢、`Query READY`、來源資料時間及附近路外結果皆可見。Google Routes 外部呼叫維持關閉，因此 ETA 顯示無法估算；停車資料仍可查看，預測保持 `INSUFFICIENT_DATA`，沒有機率或模型版本。此為預期降級，不重新執行官方刷新或 API 驗收。

畫面同時殘留舊的 `讀取查詢狀態失敗：Failed to fetch`，但人工驗收事件後續已有 `QUERY_GET` HTTP 200。確認成功讀取分支未清除先前的讀取錯誤，造成成功資料與紅色錯誤提示矛盾；已在 `readParkingQuery()` 最小修正，只在成功讀取後清除同類舊錯誤。前端 `npm test` 與 `npm run build` 通過。尚未能以目前瀏覽器工具設定手機 viewport，因此手機版仍待人工檢查；未保存截圖檔。

## 2026-10-25 車位資訊稽核與地圖改版

本次工作分支為 `codex/parking-map-ui-redesign`，起始工作樹乾淨。已完成後端資料流擴充：`parking_place` 以向後相容的 nullable 欄位保存地址、車格類型、收費方式／說明、收費日／時段、官方汽車總位數與 catalog 備註；NTPC 路邊 `pay`、`paycash`、`day`、`hour`、`memo`、`roadName` 及具 `cellid` 的單格資訊，以及路外 `TYPE`、`SUMMARY`、`PAYEX`、`SERVICETIME`、`TOTALCAR` 均映射至受控欄位。原始 hint 留在內部 mapping，不作前端契約。

新增唯讀 `GET /api/v1/parking-queries/{queryId}/map-data/viewport`，採新莊 MVP 支援邊界裁切、bounds 查詢、offset／limit 分頁及最多 500 筆上限；它只讀本地已驗證目錄與已發布快照，不觸發 Google Routes 或官方來源刷新。既有 500 公尺行程規劃查詢仍保留。來源未提供官方逐筆觀測時間時仍回空值，不以系統取得時間代替官方時間；未知、過期、資格未確認仍隱藏可用數與導航資格。

完整後端 Maven 測試 `backend/.\mvnw.cmd test` 通過 112 tests／0 failures／0 errors／0 skipped。首輪曾因 H2 不支援單一 ALTER TABLE 連續 ADD COLUMN 語法失敗，已改為逐欄 migration 並重新跑完整套件通過。使用者由附件明確選擇 Impeccable「臺灣街道圖集」手機稿，產品脈絡與視覺規範分別記於 `docs/PRODUCT.md`、`docs/DESIGN.md`。

前端已接上 viewport bounds 分頁與 request guard；新範圍載入時保留既有標記到新回應抵達，路邊格位以 56 CSS 像素鄰近距離群聚、zoom 21 起逐格顯示，路外場站保持獨立標記。選取詳情可呈現可用／總格數、官方狀態、品質、汽車資格、來源時間、系統取得時間、地址／座標、收費／時段及抵達預測資料不足；地圖摘要提示新莊支援範圍外或部分超界狀態。fallback 查詢契約保留 API 已提供的車位狀態與欄位。新增 `docs/parking-map-ui-audit.md` 記錄欄位矩陣、可信度與限制，`docs/DESIGN.md` 記錄 RWD 與資料表述方向。

兩軸獨立 code review 的規格缺口已修正：viewport 載入期間標記閃爍、網格邊界附近的群聚、詳情容量與支援範圍提示。標準審查指出的重複 CSS 主題覆寫已合併；來源文字清理方法改名為 `normalizeAndLimitText`。回應 record 欄位相似屬低風險維護建議，保留各 API 契約欄位以維持明確性。

本機驗證：前端 `npm test` 通過 7 組測試，`npm run build` 成功；後端完整 Maven 套件 112/112 通過；`impeccable detect --json frontend/src/App.vue` 回傳空清單。真實瀏覽器操作與五種 viewport 截圖尚未完成：自動化瀏覽器安全政策拒絕讀取本機 Vite 頁，且明確禁止改用其他瀏覽器路徑重試。Docker CLI pipe 亦回拒絕存取；既有 Docker 網頁先前作為基線開啟，但此輪沒有重建或修改容器／volume，也沒有送出查詢。Docker MySQL 尚未驗收 V2 migration。座標未經實地精度校驗仍是產品資料限制。

## 2026-10-25 Sprint 3 ML 評估可信度與 Calibrated Persistence

在獨立分支 `codex/ml-801010-calibrated-persistence`（基準 `0993b1786c5861835786062bdca935cfbd91f09e`）完成離線實驗改造，未改動原工作樹中既有 UI／Places／Docker 修改。讀取原本 31 個 ignored collector ZIP：roadside 2,522,054 列、offstreet 192,850 列；無官方 API 或 Google Routes 呼叫。原 0993b17 compatibility runner 以新輸出路徑重跑，validation／test 四種 dataset-lead 共 24 筆既有方法指標與先前報告逐列一致。

新增 `calibrated_state_persistence_v1`：每 dataset／lead 僅用 train 的 True／False／Unknown 狀態正例率，Laplace alpha=1，每狀態至少 20 筆，否則回退平滑後 train 全域正例率。新 Logistic artifact 使用 schema v2、feature version `parking_features_as_of_v2`，時間特徵命名為 `as_of_hour_*`。報告及權重只在 ignored 私有路徑；狀態保持 `EXPERIMENT_ONLY`，`publishToOnlineSystem=false`。

測試集（2026-10-22 至 10-24；此前已分析，不是新 holdout）核心比較如下。欄位 Brier／Log loss；FPR 和 near-full Precision 保留以檢查假陽性風險：

| Cohort／lead | n | 固定 Persistence Brier／Log loss | Calibrated Persistence Brier／Log loss | Logistic Brier／Log loss | FPR（三者相同） | near-full Precision（三者相同） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Offstreet 30m | 5,400 | 0.0130／0.0856 | **0.0115／0.0595** | 0.0254／0.1087 | 6.18% | 95.61% |
| Offstreet 60m | 5,360 | 0.0178／0.1013 | **0.0163／0.0779** | 0.0289／0.1195 | 8.70% | 94.46% |
| Roadside legacy partial 30m | 6,750 | 0.0966／0.3593 | 0.1046／1.0234 | **0.0922／0.3314** | 7.77% | 71.30% |
| Roadside legacy partial 60m | 6,700 | 0.1169／0.4257 | 0.1271／1.1709 | **0.1094／0.3823** | 9.53% | 68.40% |
| Roadside complete circle 30m | 41 | 未評估 | 未評估 | 未評估 | — | — |
| Roadside complete circle 60m | 145 | 0.0025／0.0513 | 0.0000／0.0002 | 0.0124／0.1001 | 0.00% | 100.00% |

Roadside complete 30m test 只有 41 筆，低於設定的 100 筆最低門檻，所有 test 指標均未開啟；60m 雖有 145 筆且同時有正負例，但只有 3 個已看過日期，屬探索性結果。完整 coverage test 每 lead 有 7,000 個候選：30m 接受 41／拒絕 6,959（40 正、1 負）；60m 接受 145／拒絕 6,855（60 正、85 負）。legacy partial 分別接受 6,750／7,000（2,052 正、4,698 負）及 6,700／7,000（2,040 正、4,660 負）。准入差異顯著，兩 policy 的分數不可混作同條件優劣，樣本接受率也不是使用者查詢預測覆蓋率。

預設 gate：train 至少 500、validation/test 各至少 100，且三個 split 都要同時有正負例；validation 依 Brier 選模，完成選模才評估 test。新增 near-full、狀態轉換、臺北日期及 calibration 分桶／低樣本標記與拒絕原因。此輪不代表 S3-020 gate 已通過：時間外推僅 3 日、roadside 圈有重疊、來源更新時間語意未完全確認、完整 coverage 接受量極低、仍非 ETA-aware；正式 predictions 維持資料不足。

私有報告：`data/private/collector-samples/calibrated-persistence-2026-10-25.md`；模型產物：`ml-training/models/calibrated-persistence-2026-10-25/`。執行版 `calibrated_persistence_v1` runner／ML Python source SHA-256：`eb29c492de5b954e9c29c8253b221657aa6542fc7bd068ff710f00aa3473c01f`。完整 unittest、compileall 與 Git diff 驗證在本次 PR 最終檢查記錄。

### PR #96 第三輪審查修正與全量重跑（2026-10-25）

- **時間切分可信度**：新版 calibrated runner 固定使用 `collected_at_taipei_v1`，以具明確時區的 `collected_at_utc` 轉成 `Asia/Taipei` 日期切分；as-of 與 arrival 都要求明示時區及 `collected_date_taipei`，並核對欄位日期與轉換日期一致。日期不一致以 `collected_date_taipei_mismatch` 拒收；arrival 跨 split 也拒收。原 0993b17 runner 保留 `file_date_v0993b17`，舊結果不因新版政策改寫。
- **Roadside 對照開關與端到端測試**：`compareLegacyRoadsidePolicy` 現在直接控制是否建立 legacy cohort。新增合成暫存 ZIP 測試，實際呼叫 `run_calibrated_experiment()`，涵蓋新舊 cohort 開關、報告、測試 gate 與實驗限定 artifact；不使用私有 collector 資料。
- **31 個 ZIP 全量結果**：roadside 2,522,054 列、offstreet 192,850 列；日期切分為 train／validation／test 25／3／3 日（約 80.6%／9.7%／9.7%）。全量 audit 中，offstreet 與 roadside 的台北日期不一致、時區缺失及台北日期欄位缺失均為 0，必要欄位 parser skip 合計 0。樣本配對日期不一致拒收亦為 0。
- 新 timestamp policy 的三組 cohort 接受數與原報告相同：offstreet 96,666／385,700 個候選；roadside legacy 118,900／131,000；roadside complete-circle 83,902／131,000。與先前 calibrated 報告的 validation／test 48 列結果逐欄一致；原 0993b17 相容重跑的 24 列既有指標亦逐列一致。因此本次嚴格日期檢查沒有改變這批資料的分割或評估數值。
- **測試集摘要**（2026-10-22 至 10-24 曾在前次分析使用，不是新 holdout）：offstreet 30m／60m calibrated persistence Brier 為 0.0115／0.0163，固定 persistence 為 0.0130／0.0178；roadside legacy 的 calibrated persistence Brier 為 0.1046／0.1271，Logistic 為 0.0922／0.1094。roadside complete-circle 30m 僅接受 41 筆，未達 100 筆門檻而不評估；60m 接受 145 筆，仍只有三個已分析日期，僅作探索性結果。完整指標與拒絕原因見私有報告。
- 新政策完整報告：`data/private/collector-samples/timestamp-policy-third-review-2026-10-25.md`；artifact：`data/private/collector-samples/timestamp-policy-third-review-artifacts/`。0993b17 相容報告：`data/private/collector-samples/compatibility-third-review-2026-10-25.md`。上述路徑均屬 ignored 私有資料，未加入 Git；沒有呼叫官方 API 或 Google Routes。輸出均維持 `EXPERIMENT_ONLY`，不接入線上 predictions。
- PR 驗證：`python -m unittest discover -s ml-training/tests` 24 tests 通過；`python -m compileall -q ml-training` 成功；`git diff --check` 通過。
