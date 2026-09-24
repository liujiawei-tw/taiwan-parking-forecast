# 主系統 MySQL／JPA 資料模型草案

更新日期：2026-09-25。狀態：Sprint 1 設計文件；沒有主系統 coding。第一版資料庫以服務使用者查詢為主要目的，保存可追溯歷史供未來模型訓練為次要目的。資料表名稱、欄位名稱、型別與索引需在實作與資料量測後定稿。

## 設計原則

- MySQL 是已發布停車目錄、快照、觀測歷史、來源狀態與用量紀錄的主要事實來源。
- Redis 只保存短期查詢進度、短鎖與經驗證快取，不保存唯一真相。
- JPA entity 不直接回給前端；API response 由 application service 組合 DTO。
- 原始來源欄位不直接暴露給前端；adapter 先轉成共同欄位，再寫入候選快照。
- 已發布快照要可追溯，未完成或品質不合格的候選快照不得對外發布。
- 路邊單格位與路外整場保留不同粒度，但共同查詢 API 可用一致欄位呈現來源、時間、狀態與品質。

## 第一版資料表與 JPA entity

| 資料表 | JPA entity 候選 | 主要責任 |
| --- | --- | --- |
| `parking_place` | `ParkingPlaceEntity` | 系統內統一停車位置目錄，包含路邊格位與路外停車場 |
| `source_mapping` | `SourceMappingEntity` | 外部來源 ID 與系統內位置 ID 對應，避免不同來源 ID 撞名或誤合併 |
| `availability_snapshot` | `AvailabilitySnapshotEntity` | 某來源某次擷取的候選／已發布快照版本與品質摘要 |
| `availability_observation` | `AvailabilityObservationEntity` | 快照內每個位置的動態觀測，用於目前查詢與未來訓練資料 |
| `source_state` | `SourceStateEntity` | 每個來源目前啟用快照、冷卻、下一次可刷新時間與失敗狀態 |
| `ingestion_run` | `IngestionRunEntity` | 每次來源擷取工作的開始、結束、HTTP 次數、錯誤與品質結果 |
| `api_usage_budget` | `ApiUsageBudgetEntity` | 記錄來源用量、保留量與月／日預算狀態；是否第一版建表待定 |

## `parking_place`

用途：保存系統內統一停車位置。路邊單格位與路外停車場都可放在這張表，但用 `place_type` 區分。

候選欄位：

- `id`：系統內主鍵。
- `place_type`：`ON_STREET_SPOT` 或 `OFF_STREET_LOT`。
- `display_name`：顯示名稱；路邊格位可用路段或來源名稱。
- `latitude`、`longitude`：WGS84 座標。
- `entrance_latitude`、`entrance_longitude`：路外停車場入口座標；可空。
- `car_eligible_status`：一般汽車是否可用，例如 `CONFIRMED`、`NOT_ELIGIBLE`、`UNKNOWN`。
- `active_status`：是否仍在目錄中有效，例如 `ACTIVE`、`INACTIVE`、`UNKNOWN`。
- `service_area`：第一版為 `NEW_TAIPEI_XINZHUANG`。
- `created_at`、`updated_at`。

建議索引：

- `(place_type, service_area, active_status)`。
- `(latitude, longitude)` 作第一版簡易範圍查詢；後續若 MySQL spatial 實測可用，再評估 `POINT` 與 spatial index。

## `source_mapping`

用途：保存來源 ID 與系統內停車位置的對應。新增 TDX 或其他縣市來源時，先在此表建立映射與核對狀態。

候選欄位：

- `id`。
- `source_system`：例如 `NTPC_OPEN_DATA`、`TDX`。
- `dataset_code`：例如 `NTPC_ROADSIDE_SPOT`、`NTPC_OFF_STREET_AVAILABILITY`。
- `source_place_id`：來源原始 ID，例如新北市 `cellid` 或路外 `ID`。
- `parking_place_id`：指向 `parking_place.id`。
- `mapping_status`：`VERIFIED`、`CANDIDATE`、`CONFLICT`、`RETIRED`。
- `valid_from`、`valid_to`。
- `raw_name_hint`、`raw_location_hint`：可選，協助人工核對。
- `created_at`、`updated_at`。

約束：

- `(source_system, dataset_code, source_place_id, valid_to)` 應避免同一有效來源 ID 對應多個位置。
- 不同來源 ID 相同時不能合併，必須靠 `source_system` 與 `dataset_code` 分隔。

## `availability_snapshot`

用途：保存某來源某次擷取的快照版本。只有完整性與品質通過的快照才可發布給查詢使用。

候選欄位：

- `id`。
- `source_system`、`dataset_code`。
- `snapshot_status`：`CANDIDATE`、`PUBLISHED`、`REJECTED`。
- `started_at`、`finished_at`。
- `system_fetched_at`：系統完成取得時間。
- `source_observed_at`：來源提供的觀測時間；沒有則空值。
- `published_at`。
- `expected_record_count`、`actual_record_count`。
- `quality_status`：`PASS`、`PARTIAL_PASS`、`FAIL`。
- `quality_reason`：例如缺頁、重複 ID、無效值過多。
- `ingestion_run_id`。

規則：

- `PUBLISHED` 快照才可被 `source_state.active_snapshot_id` 指到。
- `REJECTED` 快照可保留品質摘要，但不得提供給使用者查詢。
- 第一版不發布半套資料；缺頁或無法確認取齊時整輪拒絕。少數逐筆壞值可標未知，是否仍發布由品質規則決定。

## `availability_observation`

用途：保存快照中每個位置的動態觀測。這是主系統日後可匯出給模型訓練的主要歷史資料，但第一責任仍是支援使用者查詢。

候選欄位：

- `id`。
- `snapshot_id`：指向 `availability_snapshot.id`。
- `parking_place_id`：指向 `parking_place.id`。
- `source_system`、`dataset_code`、`source_place_id`：保留來源追溯。
- `availability_status`：`AVAILABLE`、`OCCUPIED`、`FULL`、`UNKNOWN`、`NO_REALTIME_AVAILABILITY`、`STALE` 等候選。
- `available_car_spaces`：路外汽車剩餘位；路邊格位可空。
- `total_car_spaces`：路外汽車總位數；未知可空。
- `is_available_spot`：路邊是否回報空位；未知可空。
- `raw_status_code`、`raw_availability_value`：保存關鍵原值，例如 `AVAILABLE=-9`；避免語意失真。
- `source_observed_at`、`system_fetched_at`。
- `quality_status`、`quality_reason`。
- `created_at`。

建議索引：

- `(snapshot_id, parking_place_id)`。
- `(parking_place_id, system_fetched_at)`：供未來訓練與歷史查詢。
- `(dataset_code, system_fetched_at)`：供快照與匯出。

注意：

- 有效 0 必須保存為 `0`；未知保存為 `null` 並有狀態原因。
- 路外 `-9` 類狀態不可轉成 0，應保存原值並轉成 `NO_REALTIME_AVAILABILITY` 或對應未知狀態。
- 路邊與路外都進同一觀測表，但只填各自有語意的欄位。

## `source_state`

用途：保存每個來源目前狀態，支援受控刷新、冷卻、共用擷取與查詢新鮮度判斷。

候選欄位：

- `id`。
- `source_system`、`dataset_code`。
- `active_snapshot_id`。
- `last_successful_fetched_at`。
- `last_source_observed_at`。
- `next_allowed_refresh_at`。
- `refresh_status`：`IDLE`、`REFRESHING`、`COOLDOWN`、`FAILED`、`BUDGET_BLOCKED`。
- `failure_reason`。
- `consecutive_failure_count`。
- `updated_at`。

規則：

- `source_state` 是後端保護官方 API 的核心表之一，不能只放 Redis。
- 多人查詢、手動更新、可見頁面自動刷新與背景取樣都必須看同一筆來源狀態。
- Redis 可做短鎖或快取，但最終可再試時間和最後成功時間應可從 MySQL 恢復。

## `ingestion_run`

用途：記錄每次官方來源擷取工作，協助除錯、用量估算與面試時說明上游保護策略。

候選欄位：

- `id`。
- `source_system`、`dataset_code`。
- `trigger_type`：`INITIAL_QUERY`、`VISIBLE_AUTO_REFRESH`、`MANUAL_REFRESH`、`BACKGROUND_SAMPLING`。
- `run_status`：`RUNNING`、`SUCCEEDED`、`FAILED`、`REJECTED`、`CANCELLED`。
- `started_at`、`finished_at`。
- `http_request_count`。
- `record_count`。
- `failure_reason`。
- `quality_summary`。

第一版可以先記錄摘要，不一定保存完整原始 response。是否保存壓縮原始檔或原始 JSON，需依資料量、授權與除錯需求決定。

## `parking_query` 是否持久化

第一版建議：`parking_query` 不先作為 MySQL 核心表，短期查詢狀態放 Redis；MySQL 只保存可重用的停車目錄、快照、觀測與來源狀態。

理由：

- `queryId` 是短期互動狀態，不是預約或長期監控。
- 查詢內含起點、目的地與時間，牽涉位置隱私，不宜為了方便先長期保存。
- Redis 過期後提示重新規劃即可；MySQL 快照仍可供新查詢使用。

日後若需要分析使用者查詢熱區或除錯，可另設低敏感度的查詢事件表，例如只保存模糊化區域、建立時間、來源狀態與錯誤碼，不保存精確起點。


## 300 公尺附近查詢索引策略

第一版附近查詢先以 `parking_place` 的 WGS84 `latitude`／`longitude` 做外接矩形預篩，再由 application service 計算目的地到候選位置的精確直線距離。Repository 不直接回傳「附近結果」，只回傳符合基本條件的候選位置；是否在 300 公尺內、是否可顯示目前有位、是否可預測，分別由 application service、來源快照狀態與 prediction service 判斷。

建議索引先服務 MVP：`service_area`、`place_type`、`active_status`、`latitude`、`longitude`。若新莊資料量或未來擴縣市後查詢延遲不穩，再新增固定 SRID 的空間欄位與 spatial index。這樣可以先保持 JPA 實作單純，並用實測資料決定是否需要空間資料庫優化。

路邊與路外都透過同一套位置候選流程進入 API 組裝，但觀測資料必須分別讀各自已發布快照，不能把其中一類的快照時間或品質套用到另一類。
## 快照發布與資料一致性

使用者查詢只能讀取 `source_state.active_snapshot_id` 指向的 `PUBLISHED` 快照。`availability_snapshot` 的 `CANDIDATE` 與 `REJECTED` 狀態只供擷取流程、品質檢查與除錯使用，不可被一般查詢 API 選用。

建議交易邊界：候選快照與觀測資料可以先批次寫入；驗證通過後，用一次短交易更新 `availability_snapshot.snapshot_status=PUBLISHED`、`published_at` 與 `source_state.active_snapshot_id`。交易提交後才更新或失效 Redis 快取。Redis 更新失敗時，MySQL 已發布版本仍是事實來源，後續可從 MySQL 重建快取。

路邊與路外各自有獨立 `source_state` 與啟用快照，因此可以各自成功、失敗或過期。前端不能把路邊的取得時間套用到路外，也不能用一個來源的新快照證明另一來源新鮮。

未發布候選、失敗擷取、品質拒絕資料和模型補值不得當作訓練標籤。訓練匯出應以已發布觀測歷史和快照品質欄位篩選資料。

## 保留與歸檔

- 使用者查詢服務需要最新已發布快照與近期歷史。
- 模型訓練希望有較長時間序列，但這是次要用途，不能讓訓練保存需求拖慢使用者查詢。
- 第一版先保留 MySQL 可承受的歷史量；資料量明確後，再決定冷資料匯出成 CSV／Parquet 或壓縮檔。
- 匯出訓練資料時以 `availability_observation`、`parking_place`、`source_mapping` 與快照品質欄位組合，不直接讀前端 API response。

## 待量測與待定

- 新北市路邊與路外每輪資料量、寫入時間與索引大小。
- 300 公尺查詢用經緯度 bounding box 加距離計算是否足夠；是否需要 MySQL spatial index。
- `availability_observation` 是否需要依日期或來源分區。
- 原始 response 是否保存、保存多久、是否壓縮。
- `api_usage_budget` 是否第一版建表，或先併入 `source_state`／設定檔。
- JPA 批次寫入設定與交易大小。
