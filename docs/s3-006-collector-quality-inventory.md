# S3-006 collector 訓練資料品質盤點與匯入規格

更新日期：2026-10-03。狀態：第一版盤點工具與 9 天 Google Drive 真實 daily ZIP 樣本報告；roadside label 仍為 provisional，因此不進入正式模型訓練。

## 目的

#62 的目標是先確認獨立 `parking-data-collector` 產出的 daily CSV／ZIP 是否足以產生訓練標籤。這些資料只供離線訓練／回測評估，不服務使用者查詢、不替代主系統官方即時來源，也不寫入主系統 `PUBLISHED` snapshot。

本輪先讀取 sibling repo `C:\Users\z0976\OneDrive\桌面\dev-side\parking-data-collector` 的文件與本機檔案目錄，接著從 Google Drive `parking-training-data/exports` 取得 `parking-training-2026-09-24.zip` 至 `parking-training-2026-10-02.zip` 共 9 包做本機盤點。`parking-training-2026-09-23.zip` 先排除，因使用者指出該日資料不齊。原始 ZIP、CSV 與私有完整報告不提交 Git；正式文件只保留摘要數字與限制。

## 可重跑盤點工具

新增 `tools/collector_quality_inventory.py`，使用 Python 標準函式庫讀取 collector 匯出根目錄或單一 daily ZIP，輸出 Markdown 摘要。工具會掃描：

- `roadside_training_samples_yyyy-MM-dd.csv`
- `offstreet_training_samples_yyyy-MM-dd.csv`
- `manifest_yyyy-MM-dd.json`
- `parking-training-yyyy-MM-dd.zip`

範例：

```powershell
python tools/collector_quality_inventory.py --input "D:\parking-training-data" --expected-start 2026-09-23 --expected-end 2026-09-30 --output data/private/collector-quality-2026-09-23_2026-09-30.md
```

`data/private/` 與原始資料不應提交 Git；正式 PR 只提交摘要與結論。

## 本輪可得資料盤點

| 來源位置 | 結果 |
| --- | --- |
| `parking-data-collector\data` | 只有 `collector.mv.db`、`collector.lock.db`、`collector.pid` 與 stdout/stderr log，未找到 daily CSV、manifest 或 ZIP。 |
| `parking-data-collector` repo | 僅找到 `apps-script/appsscript.json` 這類設定檔；未找到 `roadside_training_samples_*.csv`、`offstreet_training_samples_*.csv`、`manifest_*.json` 或 `parking-training-*.zip`。 |
| Google Drive `parking-training-data/exports` | 已讀到 `parking-training-2026-09-23.zip` 至 `parking-training-2026-10-02.zip` 共 10 包；本輪盤點使用 `2026-09-24` 至 `2026-10-02` 共 9 包，排除資料不齊的 `2026-09-23`。 |

因此本輪可對 2026-09-24 至 2026-10-02 做 9 天真實盤點；資料可支撐 #63 label generation dry run 的輸入審查，但不能直接啟動 #64 模型訓練。

## 2026-10-02 真實樣本摘要

來源：Google Drive `exports/parking-training-2026-10-02.zip`，本機私有路徑 `data/private/collector-samples/parking-training-2026-10-02.zip`，大小 1,606,543 bytes。ZIP 內容包含：

- `roadside_training_samples_2026-10-02.csv`
- `offstreet_training_samples_2026-10-02.csv`
- `manifest_2026-10-02.json`

盤點結果：

| 類型 | CSV 檔數 | Manifest 檔數 | Row count | 日期數 | Header mismatch | Duplicate rows | Parse errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| roadside | 1 | 1 | 87,084 | 1 | 0 | 0 | 0 |
| offstreet | 1 | 1 | 6,068 | 1 | 0 | 0 | 0 |

時間與品質摘要：

| 類型 | collected_at_utc unique timestamps | min interval | median interval | max interval | Candidate label ratio | Reliable label ratio | Unknown/blank | Negative unknown | Missing raw | Missing entity ID | Missing label rule | Missing source response | Duplicate observations | Invalid coordinates | Provisional label rows |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| roadside | 41 | 29m | 30.0m | 89m | 6.83% | 0.00% | 81,139 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 87,084 |
| offstreet | 41 | 29m | 30.0m | 89m | 24.85% | 24.85% | 4,560 | 4,396 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

判定：檔案結構、header、解析、entity id、raw 欄位、source response 欄位與重複觀測在這一日樣本皆可重現量測；roadside 因所有列仍落在 provisional label rule，可靠標籤比例為 0%，不得直接進模型訓練。

## 2026-09-24 至 2026-10-02 全區間摘要

來源：Google Drive `exports/parking-training-2026-09-24.zip` 至 `exports/parking-training-2026-10-02.zip`，本機私有路徑 `data/private/collector-samples/`。每包 ZIP 皆包含當日 roadside CSV、offstreet CSV 與 manifest。

整體盤點結果：

| 類型 | CSV 檔數 | Manifest 檔數 | Row count | 日期數 | Header mismatch | Duplicate rows | Parse errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| roadside | 9 | 9 | 728,532 | 9 | 1 | 0 | 0 |
| offstreet | 9 | 9 | 50,516 | 9 | 1 | 0 | 0 |

時間與品質摘要：

| 類型 | collected_at_utc unique timestamps | min interval | median interval | max interval | Candidate label ratio | Reliable label ratio | Unknown/blank | Negative unknown | Missing raw | Missing entity ID | Missing label rule | Missing source response | Duplicate observations | Invalid coordinates | Provisional label rows |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| roadside | 343 | 3m | 30.0m | 464m | 6.83% | 0.00% | 678,797 | 0 | 0 | 0 | 0 | 36,108 | 0 | 0 | 728,532 |
| offstreet | 340 | 3m | 30m | 464m | 24.70% | 24.70% | 38,041 | 36,681 | 0 | 0 | 0 | 2,533 | 0 | 0 | 0 |

已知限制：

- `2026-09-24` 是舊 schema：roadside 18 欄、offstreet 17 欄，缺 `source_dataset_id`、`source_dataset_name`、`source_update_frequency`、`source_api_response_at_utc`、`collected_at_taipei`。
- `2026-09-25` 已是新 schema，但有部分列缺 `source_api_response_at_utc`：roadside 21,240 rows、offstreet 1,490 rows。
- `2026-09-26` 至 `2026-10-02` 兩類 CSV 皆符合新 schema，且本輪未量到缺 `source_api_response_at_utc`。
- 全區間沒有缺日、完全重複列、同一 `collected_at_utc + entity id` 重複觀測、缺 raw label、缺 entity id 或座標異常。

## 訓練／測試切分規格

為避免時間序列資料洩漏，#63 與 #64 不使用 row-random split。第一版固定以日期切分，並保留各列原始日期與收集時間：

| Split | 日期範圍 | Roadside rows | Roadside ratio | Offstreet rows | Offstreet ratio | 用途 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| train | 2026-09-24 至 2026-09-30 | 543,744 | 74.64% | 37,640 | 74.51% | label rule、feature、baseline fit |
| test | 2026-10-01 至 2026-10-02 | 184,788 | 25.36% | 12,876 | 25.49% | holdout 評估，不回流訓練 |

目前只有 9 天樣本，按日期無法精準切成 80/20；第一版採前 7 天訓練、後 2 天測試，約 75/25，較接近一般 train/test 比例且保留時間順序。未來資料天數增加後，#64 可改成 chronological 80/20 或 rolling-window evaluation。`2026-09-24` 的舊 schema 與 `2026-09-25` 的部分 source response 缺失必須在 #63 以 rejection reason 或 schema version 分開處理。

## 欄位語意檢查

依 `parking-data-collector` README、`docs/data-contract.md` 與 Apps Script：

### Roadside

必備欄位：

- 時間：`collected_at_utc`、`collected_date_taipei`、`weekday_taipei`、`hour_taipei`、`minute_bucket_taipei`、`collected_at_taipei`
- entity：`area_code`、`spot_id`、`cell_id`、`road_id`
- 位置：`latitude`、`longitude`
- raw label：`parking_status_raw`、`cell_status_raw`
- candidate label：`is_available`
- lineage：`label_rule`、`source_dataset_id`、`source_dataset_name`、`source_update_frequency`、`source_api_response_at_utc`

目前 Apps Script label rule 是 `parkingstatus_0_available_1_unavailable_keep_raw`。這代表 `parkingstatus=0/1` 可先作候選標籤，但仍屬 provisional；在官方狀態碼語意未完全核對前，不得把它當成可靠 0/1 答案。

### Offstreet

必備欄位：

- 時間：`collected_at_utc`、`collected_date_taipei`、`weekday_taipei`、`hour_taipei`、`minute_bucket_taipei`、`collected_at_taipei`
- entity：`lot_id`
- 場站：`lot_name`、`address`、`total_car`、`tw97_x`、`tw97_y`
- raw label：`available_car_raw`
- candidate target：`available_car`、`availability_ratio`
- quality：`is_unknown`
- lineage：`label_rule`、`source_dataset_id`、`source_dataset_name`、`source_update_frequency`、`source_api_response_at_utc`

目前 Apps Script 對 `available_car_raw` 的負值或無效值採 unknown：`available_car` 空白、`availability_ratio` 空白、`is_unknown=true`，且 label rule 為 `available_car_negative_unknown_keep_raw`。這些資料不得被當成可靠 0 車位。

## 時間語意

- `collected_at_utc` 是 collector 收集輪次開始時間，不是官方逐筆觀測時間。
- `source_api_response_at_utc` 是官方 API response `Date` header 或 fallback，不是每列官方更新時間。
- 模型特徵只能使用 `asOf` 當下與以前可得的資料；抵達後資料只能作 label，不可作訓練特徵。

## 拒絕原因分類

品質報告至少要分開記錄：

| 類型 | 拒絕或降級原因 |
| --- | --- |
| 缺檔／缺日 | daily roadside/offstreet 任一類缺失、manifest 缺失、ZIP 內容不完整 |
| 時間不可用 | `collected_at_utc` 缺失或不可解析、時間間隔異常、重複 collection timestamp |
| entity 不可用 | roadside 缺 `spot_id`，offstreet 缺 `lot_id` |
| raw label 不可用 | roadside 缺 `parking_status_raw`／`cell_status_raw`，offstreet 缺 `available_car_raw` |
| label 不可靠 | roadside provisional rule、offstreet negative／unknown、空值 |
| 位置不可用 | roadside 座標缺失或超出台灣合理範圍；offstreet TWD97 座標後續需和 catalog mapping 驗證 |
| 重複 | 完全重複列、同一 `collected_at_utc + entity id` 重複觀測 |

## 匯入規格草案

第一版不要把 collector CSV 直接匯入主系統線上表。後續若要做離線訓練匯入，建議使用獨立 staging 區或離線批次輸入，至少保留：

- `source_system`
- `dataset_code`
- `collector_schema_version`
- `collector_file_date`
- `collector_file_name`
- `row_number`
- `entity_id`
- `collected_at_utc`
- `source_api_response_at_utc`
- raw status 欄位
- candidate label 欄位
- label rule
- quality rejection reason

匯入結果只能作離線 label generation 或 backtest；不得被查詢 API 讀成目前車位。

## 本輪判定

- 檔案日期：已盤點 2026-09-24 至 2026-10-02，共 9 日；`2026-09-23` 因資料不齊先排除。
- Row count：roadside 728,532、offstreet 50,516。
- 缺日：以 2026-09-24 至 2026-10-02 為期待區間時，未量到缺日。
- 時間間隔：中位數約 30 分鐘；最小 3 分鐘、最大 464 分鐘需在 #63 分開解釋為補跑、延遲或收集間隔異常。
- 重複情況：完全重複列 0、duplicate observations 0。
- Source 欄位：`2026-09-24` 為舊 schema，`2026-09-25` 部分列缺 `source_api_response_at_utc`；兩者不得偽裝成完整 lineage。
- Roadside label 可用比例：candidate 6.83%，reliable 0.00%；provisional rule 已確認存在，尚不可視為可靠標籤。
- Offstreet label 可用比例：candidate/reliable 24.70%；negative／unknown 不可當成可靠 0。

結論：#62 目前完成「盤點工具、欄位規格、9 天真實 daily ZIP 可重現盤點、train/test 日期切分與匯入規格草案」。此樣本足以支撐 #63 做 label generation dry run 的輸入審查；roadside reliable label 仍為 0%，#64 模型訓練不能啟動。

## 下一步

1. 人工審查 roadside `parking_status_raw`／`cell_status_raw` 官方語意，決定哪些狀態能成為可靠 label。
2. #63 label generation dry run 固定使用 train=`2026-09-24..2026-09-30`、test=`2026-10-01..2026-10-02`，不可 row-random split。
3. #63 必須保留 provisional／unknown／negative unknown／舊 schema／缺 source response 的拒絕原因。
4. #64 模型訓練需等 #63 產出可靠 label 後再開始。
