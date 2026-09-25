# 全臺停車位抵達預測｜專案記憶

本專案探索：使用者從出發地前往目的地時，周邊停車場在抵達時仍有車位的可能性。第一期以 TDX 停車資料為主，後續再接入地方政府免費 API。這個 repository 目前只保存需求、決策與規劃；尚未開始寫應用程式。

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

Sprint 2 第一組 GitHub Issues 已建立，實作分支為 `sprint-2-main-system-scaffold`。目前仍在 S2-001 工作規則收尾；主系統產品程式碼尚未開始。任務草案與正式 Issue 連結見 [Sprint 2 第一組 GitHub Issues 草案](docs/sprint-2-issue-drafts.md)。

本機環境變數請從 `.env.example` 複製為 `.env` 後自行填入；真實金鑰與密碼不得提交。
