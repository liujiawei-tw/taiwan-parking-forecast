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

新北市路邊原始 ZIP 與 CSV 放置位置：`data/raw/tdx/new-taipei/on-street-spot-availability/`；該資料夾的 README 會被追蹤，下載的資料檔不會進 Git。
桃園機場路外停車歷史 CSV 放置位置：`data/raw/tdx/taoyuan-airport/off-street-availability/`；首次盤點見 [桃園機場資料概況](docs/airport-data-profile.md)。

## 協作方式

使用 GitHub Issues 表示可執行任務，GitHub Projects 安排 Sprint，Git commit 留下小而完整的變更歷史。重要討論的結論更新相應文件。所有 TDX 金鑰與個人設定只存在本機安全設定中，不提交至 Git。

資料來源：[TDX API 使用指引](https://tdx.transportdata.tw/api-service/swagger)、[TDX 官方範例](https://github.com/tdxmotc/SampleCode)、[TDX 訂閱收費](https://tdx.transportdata.tw/pricing)。

## Sprint 2 目前狀態

更新至 2026-09-29：Sprint 2 的 S2-001 至 S2-025 已完成可展示主系統基線、來源量測證據與文件基線。核心資料表與 Flyway、300 公尺目錄查詢、候選快照正規化與發布、MySQL 冷卻與 Redis 短鎖、Google Routes ETA 與降級行為、Redis query state、前端 Google Maps／Places 載入路徑、無 key fallback、頁面可見時的受控自動刷新接線，以及 Sprint 3 預測進入條件均已存在。

現階段的目錄已從少量合成種子資料推進為可追溯的新北市官方 Open Data 小樣本與來源 ID 對應，但仍不是完整新莊目錄；前端可在有瀏覽器 Google key 時載入 Maps／Places，無 key 時以手動座標 fallback 驗證主系統契約。因此目前不能把畫面上的資料視為完整正式即時停車資訊，真實 browser key Places 與可見頁官方刷新仍需人工驗收，預測模型也尚未開始輸出機率。

GitHub Issues [#33](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/33) 至 [#38](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/38) 已作為 S2-020 至 S2-025 收斂：正式目錄與來源 ID 小樣本、官方來源量測、查詢契約、Maps/Places 前端、受控刷新接線，以及文件基線與 Sprint 3 進入條件。下一批應以 Sprint 3 的標籤 dry run、基準模型回測與推論降級契約為主，不直接宣稱預測完成。

本機環境變數請從 `.env.example` 複製為 `.env` 後自行填入；真實金鑰與密碼不得提交。每張 Issue 完成前使用 [任務完成檢查清單](docs/task-completion-checklist.md) 做文件、測試、Git 與工程說明驗收。

## Docker Compose

Sprint 2 provides a local Docker Compose environment:

```bash
docker compose up --build
```

Services:

- `web`: Vue app served by nginx on `http://localhost:5173`
- `api`: Spring Boot API on `http://localhost:18080`
- `mysql`: MySQL 8.4 on host port `3307` and container port `3306`, persisted in the `mysql-data` volume
- `redis`: Redis on port `6379`

Current credentials are local placeholder values for development only. Replace them through deployment environment variables before any shared environment.

## GitHub Actions CI

第一版 CI 使用 GitHub Actions，不做部署，也不需要任何真實 API key。檢查項目包含：

- 後端 Maven 測試：`backend/mvnw test`
- 前端建置：`npm ci` 與 `npm run build`
- Docker image build：`docker compose build api web`

後續若開始部署到其他 server，再另外新增部署 workflow。
