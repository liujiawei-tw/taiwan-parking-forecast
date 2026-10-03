# S3-006 collector 訓練資料品質盤點與匯入規格

更新日期：2026-10-03。狀態：第一版盤點工具與本機可得資料報告；尚未取得 Google Drive daily CSV／ZIP 樣本，因此不進入 #63 label generation。

## 目的

#62 的目標是先確認獨立 `parking-data-collector` 產出的 daily CSV／ZIP 是否足以產生訓練標籤。這些資料只供離線訓練／回測評估，不服務使用者查詢、不替代主系統官方即時來源，也不寫入主系統 `PUBLISHED` snapshot。

本輪只讀取 sibling repo `C:\Users\z0976\OneDrive\桌面\dev-side\parking-data-collector` 的文件與本機檔案目錄；未呼叫 Google Drive、未下載 ZIP、未提交 collector CSV／ZIP、未提交大型資料或敏感設定。

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
| Google Drive `parking-training-data` | 本輪未存取；需要 Sponsor 提供或在本機同步 daily ZIP／CSV 後再跑工具。 |

因此本輪 row count、缺日、時間間隔與重複列只能判定為「沒有可盤點的 daily 匯出樣本」，不能推論資料品質良好。

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

- 檔案日期：未找到 daily CSV／ZIP，無法列出實際日期範圍。
- Row count：roadside 0、offstreet 0，因本機沒有匯出樣本。
- 缺日：未設定可盤點區間且沒有樣本；不能判定完整。
- 時間間隔：樣本不足。
- 重複情況：樣本不足。
- Roadside label 可用比例：N/A；provisional rule 已確認存在，尚不可視為可靠標籤。
- Offstreet label 可用比例：N/A；已確認 negative／unknown 不可當成可靠 0。

結論：#62 目前完成「盤點工具、欄位規格與本機資料可得性確認」。因沒有 daily CSV／ZIP 樣本，不足以進入 #63 label generation，也不能開始 #64 模型訓練。

## 下一步

1. 從 Google Drive `parking-training-data/exports/` 下載至少一組 `parking-training-yyyy-MM-dd.zip`，或同步 `daily/roadside`、`daily/offstreet`、`manifests` 到本機非 Git 目錄。
2. 用 `tools/collector_quality_inventory.py` 產生私有 Markdown 報告。
3. 將摘要數字人工摘入本文件或後續 PR，不提交原始 CSV／ZIP。
4. 若 roadside/offstreet 都有連續日期、row count 合理、時間間隔可解釋、拒絕比例可接受，再進 #63。
