# 停車位抵達預測｜後端面試簡報大綱 v2

- 對象：後端工程師面試官
- 形式：單檔 HTML、兩頁 16:9 橫向簡報；架構／流程均由 HTML、SVG 呈現
- 視覺：沿用產品前端暖米白、深綠、無襯線字體
- 頁數：兩頁，無備用頁
- 時長：未提供，不預設分鐘數

| 頁碼 / ID | 頁面目的 | 觀眾可見內容 | 講者備忘重點 |
|---|---|---|---|
| 01 / system-architecture | 一頁交代主要系統元件與交付環境 | Vue 3／Vite 使用端；Java 21／Spring Boot REST；MySQL 持久資料與已發布快照；Redis 短期狀態與刷新鎖；Google Maps／Places／Routes；新北市官方來源刷新；Python 離線訓練；GitHub Repository、Docker Compose、health check；Java、JavaScript、Python、SQL 語言列 | 查詢讀已發布快照；官方刷新獨立執行；collector 僅產生離線訓練素材；路外預測維持實驗性。 |
| 02 / backend-flows | 用三條流程說明資料責任和失敗保護 | 查詢：REST API → requestToken 冪等 → MySQL 已發布快照 → Redis TTL 狀態 → 回應；刷新：獨立端點 → 鎖定／冷卻 → 分頁擷取 → mapping／品質閘門 → 交易發布；離線 ML：collector → 品質／標籤 → 時間切分／baseline → 版本化產物 → 實驗推論 | Routes ETA 僅在有起點時呼叫；刷新失敗保留既有快照；官方觀測、模型估計與無資料分開表達。 |

## 工程邊界

- MVP 即時來源以新北市官方 Open Data 為主；全臺 TDX 即時來源尚未整合。
- MySQL 保存持久資料與發布狀態；Redis 保存短期狀態與協調鎖。
- parking-data-collector 是獨立離線訓練資料收集器，不提供主系統即時快照或 API，也不決定刷新頻率。
- 路外模型服務仍為 EXPERIMENT_ONLY，未通過正式生產驗證。
- GitHub Repository 表示程式碼版本管理；Docker Compose 表示容器啟動與服務編排，不宣稱自動部署管線。
