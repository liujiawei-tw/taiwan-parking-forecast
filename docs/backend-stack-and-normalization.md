# 主系統技術棧與多來源資料統一化（Sprint 1 設計）

更新日期：2026-09-24。狀態：設計文件；沒有主系統 coding。Sponsor 已指定第一版加入 Redis，並要求不同政府或業者來源先整理為一致資料欄位，才提供給前端。具體版本、索引、快取範圍與 TTL 待實作前／實測時確定。

## 技術棧與責任

| 層次 | 技術方向 | 責任與邊界 |
| --- | --- | --- |
| 前端 | Vue 3、Vite；Google 式地點搜尋／互動地圖的實際產品待選 | 地點選取、地圖與結果、只讀主系統進度；不直接向政府 API 抓車位 |
| Web API | Java、Spring Boot Web／Spring MVC、REST、Bean Validation | 驗證查詢、協調 ETA／附近搜尋／車位狀態／預測；統一回應與錯誤狀態 |
| 建置與相依管理 | Maven（Sponsor 已選；在 IntelliJ IDEA 使用） | 管理 Spring Boot 專案的相依套件、建置與測試；實際 Java／Spring Boot 版本於開始實作前核對並固定 |
| 官方來源介接 | Spring Boot HTTP client；每個來源有獨立 adapter 與欄位映射 | 新北市路邊、路外；日後接 TDX 或其他政府／業者，不把來源欄位直接交給前端 |
| 持久資料 | MySQL、Spring Data JPA；大量觀測列的批次寫入方式待量測 | 停車目錄、來源 ID 對應、已發布快照及可追溯觀測歷史、刷新冷卻與用量紀錄；資料庫是權威來源，歷史可供未來離線訓練匯出 |
| 短期狀態與快取 | Redis（第一版納入） | 短期 `queryId` 進度、共用刷新中的協調訊號／短鎖，以及經驗證的近期查詢結果快取；用途與 TTL 按量測收斂。Redis 不保存唯一的停車真相、月度用量或完整性決策 |
| 預測 | 離線 Jupyter／Anaconda 模型實驗；線上模型產物讀取方式待定 | 訓練 collector 僅產歷史樣本；主系統只載入經時間切分驗證的模型版本來推論，觀測與預測分開 |
| 交付 | Git／GitHub、Docker 映像與部署設定 | 可在其他伺服器重現；第一版候選為單伺服器 Docker Compose，包含 web、api、MySQL、Redis。HTTPS 入口與正式主機待定，見 `docker-deployment.md` |

先前同意的「單實例 `queryId` 暫存於 Spring 記憶體」已由 Sponsor 新增 Redis 決策更新：第一版改以 Redis 保存短期進度，設定期限與容量上限；Redis 重啟或鍵過期時，該次查詢失效，前端提示重新查詢。這不影響 MySQL 內的完整快照與原始取得時間。

## 多來源資料流程

`來源 API → 該來源 adapter 解析原值 → 轉成共同欄位並驗證 → 候選完整快照 → MySQL 原子發布 → Redis 短期快取／進度 → 主系統查詢 API → Vue`

每個 adapter 只知道其來源格式；共同服務只讀標準化資料。新增城市或業者時，先核對授權、車種、狀態、時間、座標、ID 穩定性與覆蓋範圍，再增加 adapter 和映射規則。不能僅靠改欄位名稱就假定語意相同。

## 共同資料表的邏輯分工

| 表／邏輯集合 | 統一的內容 |
| --- | --- |
| `parking_place` | 系統內停車位置 ID、`on_street_spot`／`off_street_lot`、名稱、WGS84 位置、一般汽車資格與核對狀態 |
| `source_mapping` | 來源代碼、資料集、原始 ID、內部停車位置 ID、對應版本與核對狀態，避免不同來源的同名／同號碰撞 |
| `availability_snapshot` | 每來源資料集的一次完整候選／已發布版本、成功取得與發布時間、完整性與品質 |
| `availability_observation` | **統一的動態觀測表**：快照 ID、內部位置 ID、原始來源 ID、汽車剩餘數（可空）、格位狀態（可空）、品質原因、可空來源觀測時間與系統取得時間；路邊單格位與路外整場保留不同粒度 |
| `ingestion_run`、`source_state` | 實際 HTTP 次數、失敗、上次／下次允許刷新時間、啟用快照與預算狀態 |

因此 Sponsor 提出的「專門統整資料表」由**統一觀測表加共同目錄／來源對應**實現，而不是一張同時裝位置、所有原始欄位、歷次車位與預測的大表。靜態位置與高頻觀測更新節奏不同；路邊格位只有 0／1／未知的空位語意，路外是整場汽車剩餘數；預測有自己的時間與模型版本。共同 API 可以保持欄位一致，同時保留這些類型差異。

例如新北市路邊 `cellid` 和 TDX 格位 ID 要先經 `source_mapping` 核對才可能指向同一 `parking_place`；路外官方 `-9` 映射為未知並保留原值，不轉成 0。`null`、有效 0、過期及未收錄各自有狀態。原始欄位或原始回應的保存形式與期限待資料量量測，不把來源獨有欄位硬塞進共同表的每一列。

本文件的表格只描述邏輯分工；MySQL／JPA entity、候選欄位、索引與 parking_query 是否持久化，另見 docs/engineering/data-model.md。

## Redis 的使用規則

Redis 第一版有四個明確用途：短期 `queryId` 查詢進度、防重送 token、來源刷新短鎖／進度，以及可由 MySQL 已發布快照重建的結果快取。完整 key 類型、建議 TTL 與失效規則見 `redis-design.md`。

- MySQL 成功發布完整快照後，才允許更新或失效對應 Redis 快取；快取鍵需含來源／資料集與快照版本。半套新資料不能進快取。
- 回應的新鮮度依 MySQL 記錄的來源觀測時間／系統成功取得時間判斷；Redis TTL 只控制快取壽命，不會把舊資料變新。超過目前 5 分鐘試驗門檻仍須走受控共用刷新。
- 多位使用者同時查詢可共用同一來源的刷新；Redis 短鎖只作協調，不能繞過 MySQL `source_state` 的冷卻、預算與啟用快照狀態。最短刷新間隔、上游用量與重啟後冷卻狀態持久存在 MySQL。
- Redis 失效時，主系統仍可依 MySQL 完整快照回答目前車位或標示過期；短期查詢進度失效則明確提示重查。快取命中率、記憶體用量與 MySQL 延遲應量測，避免為了使用 Redis 而快取整市全量資料。

## Docker 部署方向

第一版部署候選為單台伺服器 Docker Compose：`web` 提供 Vue build 後靜態檔並代理 `/api`，`api` 執行 Spring Boot，`mysql` 保存持久資料，`redis` 保存短期狀態與可重建快取。對外只暴露 `web`；`api`、`mysql`、`redis` 留在內部 network。機密用環境變數或部署端 `.env` 管理，不提交 Git，也不寫進 image。詳見 `docker-deployment.md`。

## 第一版驗收與待決

- 同一前端回應可呈現路邊／路外共同的來源、位置、汽車適用性、目前觀測與品質欄位；類型專有欄位仍在各自區塊。
- 兩種不同來源的原始 ID 相同時不能誤合併；映射未驗證時各自保留來源身分。
- 刷新失敗、部分分頁、Redis 清空／重啟時，不發布半套快照或把過期「有位」標成新資料。
- 待量測：主系統一輪抓取大小／時間、Redis 要快取的最小資料與 TTL、快取命中率、各來源欄位與狀態碼映射、批次寫入速度。

## Spring Boot package 分層與 DTO 命名草案

第一版後端採 Spring Boot Web／Spring MVC REST，並以 Maven 管理相依。package 分層以「API 契約、應用流程、領域語意、外部介接、資料存取」分開，避免 Controller 直接呼叫政府 API、資料庫 entity 直接回給前端，或把模型推論混進來源 adapter。

建議 root package 先用 `tw.jiawei.parkingforecast`；正式 groupId 可在建立 Maven 專案時再確認。

| package | 責任 | 第一版候選類別 |
| --- | --- | --- |
| `api.parkingquery` | 查詢 API Controller、request／response DTO、錯誤 DTO | `ParkingQueryController`、`CreateParkingQueryRequest`、`ParkingQueryResponse`、`RefreshParkingQueryResponse`、`ApiErrorResponse` |
| `application.query` | 建立查詢、讀取進度、手動刷新等 use case 協調 | `CreateParkingQueryService`、`GetParkingQueryService`、`RefreshParkingQueryService` |
| `application.refresh` | 來源級共用刷新、冷卻、預算保留、快照發布流程 | `ParkingSourceRefreshService`、`RefreshDecisionService` |
| `application.eta` | Google ETA 工作協調與結果轉換 | `EtaPlanningService` |
| `application.prediction` | 抵達時預測協調，呼叫模型或基準規則 | `PredictionService`、`PredictionEligibilityService` |
| `domain.parking` | 停車位置、車位狀態、車種資格、覆蓋語意 | `ParkingPlace`、`ParkingPlaceType`、`AvailabilityStatus`、`CoverageStatus` |
| `domain.query` | 查詢生命週期、查詢狀態、區塊狀態與錯誤語意 | `ParkingQuery`、`QueryStatus`、`SectionStatus` |
| `domain.refresh` | 來源刷新狀態、冷卻、刷新處置、品質判斷 | `RefreshAction`、`SourceState`、`SnapshotQuality` |
| `domain.prediction` | 預測目標、模型版本、資料不足原因 | `PredictionResult`、`PredictionStatus`、`PredictionReason` |
| `adapter.google` | Google Routes／Places 相關外部介接；第一版主要由後端呼叫 Routes | `GoogleRoutesClient`、`GoogleRoutesMapper` |
| `adapter.ntpc` | 新北市路邊與路外官方 API client 與原始欄位轉換 | `NtpcRoadsideClient`、`NtpcOffStreetClient`、`NtpcParkingMapper` |
| `adapter.tdx` | 日後 TDX 或歷史資料介接；第一版主系統可先保留擴充點 | `TdxParkingClient`、`TdxParkingMapper` |
| `infrastructure.persistence` | JPA entity、repository、MySQL 對應與查詢 | `ParkingPlaceEntity`、`AvailabilitySnapshotEntity`、`ParkingPlaceRepository` |
| `infrastructure.redis` | Redis query state、短鎖與快取序列化 | `QueryStateRedisRepository`、`RefreshLockRepository` |
| `config` | Spring 設定、外部 API properties、WebClient／RestClient 設定 | `GoogleApiProperties`、`NtpcApiProperties`、`RedisConfig` |

DTO 命名原則：API request／response DTO 只放在 `api` 層，名稱貼近 HTTP 契約；JPA entity 只放在 persistence 層，不直接出現在 Controller 回應；domain model 放系統語意，不攜帶 JSON 註解或 JPA 註解。這樣未來欄位名稱、資料表欄位與領域語意可以各自演進。

第一版查詢 API DTO 可對應目前契約：

- `CreateParkingQueryRequest`
  - `requestToken`
  - `OriginRequest`
  - `DestinationRequest`
  - `DepartureRequest`
- `ParkingQueryResponse`
  - `QueryDto`
  - `DestinationDto`
  - `AreaDto`
  - `EtaDto`
  - `RoadsideDto`
  - `OffStreetDto`
  - `PredictionsDto`
  - `RefreshDto`
- `ApiErrorResponse`
  - `ApiErrorDto error`
  - `serverTime`

Application service 的輸入可以先接收 API DTO 或轉成 command 物件；若實作時邏輯變多，建議在 `application.query` 加 `CreateParkingQueryCommand`，讓 Controller 負責 DTO 驗證與轉換，service 處理流程，不依賴 Web 層。

面試時可說明這個分層的價值：來源 API 格式會變、前端欄位會調整、資料庫也會演進，但核心流程與領域語意要保持穩定。對初階後端工程師而言，這比把所有邏輯寫在 Controller 裡更能展現可維護性。
