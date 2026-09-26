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

新北市路邊原始 ZIP 與 CSV 放置位置：`data/raw/tdx/new-taipei/on-street-spot-availability/`；該資料夾的 README 會被追蹤，下載的資料檔不會進 Git。
桃園機場路外停車歷史 CSV 放置位置：`data/raw/tdx/taoyuan-airport/off-street-availability/`；首次盤點見 [桃園機場資料概況](docs/airport-data-profile.md)。

## 協作方式

使用 GitHub Issues 表示可執行任務，GitHub Projects 安排 Sprint，Git commit 留下小而完整的變更歷史。重要討論的結論更新相應文件。所有 TDX 金鑰與個人設定只存在本機安全設定中，不提交至 Git。

資料來源：[TDX API 使用指引](https://tdx.transportdata.tw/api-service/swagger)、[TDX 官方範例](https://github.com/tdxmotc/SampleCode)、[TDX 訂閱收費](https://tdx.transportdata.tw/pricing)。

## Sprint 2 啟動狀態

Sprint 2 第一組 GitHub Issues #1～#8 已完成並合併回 `main`。目前已完成後端骨架、前端骨架、Docker Compose、後端 profiles、S2-006 mock 查詢生命週期、S2-007 文件驗收清單與 S2-008 GitHub Actions 基礎 CI；正式 Google、新北市官方 API、Redis query state、資料庫正式模型與預測模型尚未接入。下一組任務為 S2-009～S2-013，聚焦資料庫與 300 公尺停車位置查詢，見 [Sprint 2 第二組 GitHub Issues：資料基礎與 300 公尺查詢](docs/sprint-2-data-foundation-issue-drafts.md)。

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
