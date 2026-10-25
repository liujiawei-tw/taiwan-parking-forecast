# 全臺停車位抵達預測｜專案記憶

本專案探索：使用者從出發地前往目的地時，周邊停車場在抵達時仍有車位的可能性。第一期以 TDX 停車資料為主，後續再接入地方政府免費 API。這個 repository 保存需求、決策、規劃與 Sprint 2 主系統骨架。

## 從這裡開始

1. [專案總覽](docs/project-brief.md)：目標、範圍、限制。
2. [需求與驗收](docs/requirements.md)：使用者能做什麼、怎樣算完成。
3. [決策紀錄](docs/decisions.md)：已同意的方向及原因。
4. [目前狀態](docs/current-state.md)：現在進度與下一步。
5. [待確認問題](docs/open-questions.md)：尚未決定或需實測的事項。
6. [路線圖](docs/roadmap.md)：Sprint 0 與後續增量。
7. [架構草案](docs/architecture.md)、[資料與費率盤點](docs/tdx-data-and-cost.md)：技術設計的依據。
8. [歷史資料計畫](docs/historical-data-plan.md)：資料候選、格式、日期與點數風險。
9. [新北市路邊資料概況](docs/new-taipei-data-profile.md)、[桃園機場路外資料概況](docs/airport-data-profile.md)：兩份已取得資料的初步盤點。
10. [Sprint 0 等待期間工作清單](docs/sprint-0-while-waiting.md)：長區間資料產製時可先完成的工作與交付條件。
11. [資料來源與預測目標討論稿](docs/data-source-strategy.md)：高額歷史檔的替代方案，以及搜尋／抵達前刷新流程。
12. [Sprint 3 預測進入條件](docs/sprint-3-entry-criteria.md)：預測開始前必須具備的資料、標籤、回測與推論契約證據。
13. [模型訓練架構](docs/model-training-architecture.md)：離線訓練 workspace、資料切分與禁止接線上的邊界。

新北市路邊原始 ZIP 與 CSV 放置位置：`data/raw/tdx/new-taipei/on-street-spot-availability/`；該資料夾的 README 會被追蹤，下載的資料檔不會進 Git。
桃園機場路外停車歷史 CSV 放置位置：`data/raw/tdx/taoyuan-airport/off-street-availability/`；首次盤點見 [桃園機場資料概況](docs/airport-data-profile.md)。

## 協作方式

使用 GitHub Issues 表示可執行任務，GitHub Projects 安排 Sprint，Git commit 留下小而完整的變更歷史。重要討論的結論更新相應文件。所有 TDX 金鑰與個人設定只存在本機安全設定中，不提交至 Git。

資料來源：[TDX API 使用指引](https://tdx.transportdata.tw/api-service/swagger)、[TDX 官方範例](https://github.com/tdxmotc/SampleCode)、[TDX 訂閱收費](https://tdx.transportdata.tw/pricing)。

## 目前狀態

更新至 2026-10-25：Sprint 2 主系統基線與 Sprint 3 預測前置工作已存在。核心資料表與 Flyway、附近目錄查詢（目前產品半徑 500 公尺）、候選快照正規化與發布、MySQL 冷卻與 Redis 短鎖、Google Routes ETA 與降級行為、Redis query state、Google Maps／Places 載入路徑、頁面可見時的受控自動刷新，以及 Sprint 3 預測進入條件均已存在。2026-10-25 已完成 31 日 collector 樣本的首輪 chronological 80/10/10 離線 ML 訓練與測試；候選只保留在本機實驗產物，尚未接線上查詢。此分支統一起訖點 Places 搜尋並將 Docker Compose 收斂為單一安全預設設定；PR 審查結果及其精確驗證狀態以本次交付記錄為準。

現階段目錄已從少量合成種子資料推進為可追溯的新北市官方 Open Data 小樣本與來源 ID 對應，但仍不是完整新莊目錄。設定受限 browser key 時可載入 Maps／Places；沒有 key 時搜尋會明確顯示不可用，已核實的新莊捷運站預設地點仍可使用，畫面不提供手動座標欄位。真實 Places 候選互動與可見頁官方刷新仍需人工驗收，預測模型尚未開始輸出機率。

GitHub Issues [#33](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/33) 至 [#38](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/38) 已作為 S2-020 至 S2-025 收斂：正式目錄與來源 ID 小樣本、官方來源量測、查詢契約、Maps/Places 前端、受控刷新接線，以及文件基線與 Sprint 3 進入條件。下一批應以 Sprint 3 的標籤 dry run、基準模型回測與推論降級契約為主，不直接宣稱預測完成。

本機環境變數請從 `.env.example` 複製為 `.env` 後自行填入；真實金鑰與密碼不得提交。每張 Issue 完成前使用 [任務完成檢查清單](docs/task-completion-checklist.md) 做文件、測試、Git 與工程說明驗收。

## Docker Compose

根目錄的 `docker-compose.yml` 是唯一設定，本機測試、展示與單機部署都使用相同服務與網路安全邊界。第一次設定：

```powershell
Copy-Item .env.example .env
```

將 `.env` 中的 `PARKING_DB_PASSWORD` 與 `MYSQL_ROOT_PASSWORD` 換成不同的長隨機值，再啟動：

```bash
docker compose --env-file .env up -d --build
```

- Web 預設網址為 `http://127.0.0.1:18088`，只綁定主機 loopback；可用 `WEB_HTTP_PORT` 調整主機 port。
- 瀏覽器透過 Nginx 同源 `/api` 呼叫 API。API、MySQL 與 Redis 只在 Compose 網路內使用，不發布主機埠。
- 主機公開網站時，由 Caddy 或既有 HTTPS reverse proxy 終止 TLS，並代理到 `127.0.0.1:18088`；網域、防火牆與 HTTPS 設定見 [`docs/docker-deployment.md`](docs/docker-deployment.md)。
- MySQL 資料保存在 `taiwan-parking-forecast_mysql-data` named volume。一般 `stop`／`down` 不刪除資料；不要使用 `down -v` 清除正式資料。
- Routes 外部付費呼叫預設停用；無真實 Places key 時搜尋明確顯示不可用，不提供座標欄位。

完整的 config、啟停、health、logs、備份／隔離還原、回退與 smoke 命令皆以 [`docs/docker-deployment.md`](docs/docker-deployment.md) 為準。遠端 HTTPS、真 Routes ETA、正式金鑰與備份還原演練仍需相應資源和人工驗收，不能由 image build 推定已通過。

## GitHub Actions CI

第一版 CI 使用 GitHub Actions，不做部署，也不需要任何真實 API key。使用非敏感 `.env.example` 執行 Compose config 與 image build，並執行：

- 後端 Maven 測試：`backend/mvnw test`
- 前端建置：`npm ci` 與 `npm run build`
- Compose 設定與 Docker image build：`docker compose --env-file .env.example -f docker-compose.yml config --quiet`、`docker compose --env-file .env.example -f docker-compose.yml build api web`

後續若開始部署到其他 server，再另外新增部署 workflow。

## 離線模型訓練 Workspace

模型訓練相關程式放在 `ml-training/`，只用於離線 label generation、baseline、ML experiment 與模型產物驗證；不服務使用者查詢、不刷新官方來源、不提交 raw CSV／ZIP 或大型模型檔。第一版可用以下命令檢查訓練計畫與日期切分：

```bash
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config.example.json
python ml-training/scripts/generate_label_dry_run.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/label-dry-run-2026-09-24_2026-10-02.md
python ml-training/scripts/run_baseline_experiment.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/baseline-experiment-2026-09-24_2026-10-02.md
python ml-training/scripts/run_801010_experiment.py --config ml-training/config/training-config-80-10-10.example.json --input data/private/collector-samples --output data/private/collector-samples/experiment-80-10-10-2026-10-25.md --artifact-dir ml-training/models/experiment-80-10-10-2026-10-25
python -m unittest discover -s ml-training/tests
```
