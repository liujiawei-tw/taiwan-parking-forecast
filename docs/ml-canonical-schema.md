# ML 訓練資料正規化 Schema 草案

更新日期：2026-10-03。狀態：Sprint 3 訓練資料治理草案；尚未成為線上 API 契約，尚未發布模型產物。

## 目的

未來會接入 TDX 與不同地方政府 API。各來源的欄位名稱、狀態碼、更新頻率與管制語意不同，不能直接把 raw code 混在同一個模型訓練表。訓練資料需先轉成 canonical observation，再由 versioned label rule 決定哪些觀測可作 reliable label。

## 資料流

1. Source adapter 讀取官方或 collector 匯出的 raw observation。
2. Source adapter 保留 raw status 與來源欄位，並映射成 canonical availability state。
3. Label rule 根據來源、狀態碼、車種、時間與品質條件產生 `label_value` 或拒絕原因。
4. Feature pipeline 只吃 canonical 欄位與已驗證的 label，不直接依賴地方政府 raw code。
5. Model artifact 只記錄 schema version、label rule version、feature version 與訓練資料摘要，不保存 raw API response 或敏感設定。

## Canonical Observation 欄位

| 欄位 | 型別 | 必填 | 說明 |
| --- | --- | --- | --- |
| `source_system` | string | yes | 例如 `ntpc_open_data`、`tdx`、`collector`。 |
| `source_dataset` | string | yes | 來源資料集或 endpoint 名稱。 |
| `source_entity_id` | string | yes | 來源原始停車場、路段或格位 ID。 |
| `canonical_entity_id` | string | no | 主系統正規化後的停車實體 ID；未 mapping 時可空。 |
| `parking_type` | enum | yes | `roadside` 或 `offstreet`。 |
| `service_area` | string | no | 行政區、場域或城市 scope。 |
| `latitude` / `longitude` | decimal | no | 觀測座標；若來源無逐格座標可空。 |
| `observed_at` | timestamp | yes | 來源觀測時間；若來源只提供 API response time，需標示品質限制。 |
| `fetched_at` | timestamp | yes | collector 或主系統取得時間。 |
| `raw_status_code` | string | yes | 來源主要狀態碼，例如 NTPC `parkingstatus` 或 TDX `SpotStatus`。 |
| `raw_status_aux` | json | no | 輔助狀態，例如 `cellstatus`、`isnowcash`、`ServiceStatus`。 |
| `availability_state` | enum | yes | 正規化狀態，見下表。 |
| `is_reliable_label` | boolean | yes | 是否可作 supervised training label。 |
| `label_value` | boolean | no | `true` 表示抵達時可停，`false` 表示不可停；只有 reliable label 才可填。 |
| `label_rejection_reason` | string | no | 例如 `restricted_state`、`unknown_status`、`missing_observed_at`。 |
| `label_rule_version` | string | yes | 產生 label 的規則版本。 |
| `capacity` | integer | no | 路外總格位或路段容量。 |
| `available_count` | integer | no | 路外剩餘位；路邊逐格資料通常可空。 |
| `availability_ratio` | decimal | no | 路外剩餘率；由 `available_count/capacity` 產生。 |
| `vehicle_type` | string | no | 車種，例如一般小客車、機車、大型車。 |
| `is_general_car_eligible` | boolean | no | 是否適合一般小客車使用；專用格或管制格需為 false 或 unknown。 |

## Availability State

| State | 訓練 label | 說明 |
| --- | --- | --- |
| `AVAILABLE` | positive | 一般車輛可停，且來源語意明確。 |
| `OCCUPIED` | negative | 已被停放或無可用格，且來源語意明確。 |
| `FULL` | negative | 路外停車場剩餘位為 0 或低於明確門檻。 |
| `RESTRICTED` | no | 管制、專用、時段性禁停、非一般車輛可用。 |
| `CLOSED` | no | 非營運、非收費時段或暫停開放。 |
| `OUT_OF_SERVICE` | no | 設備、施工或來源品質異常。 |
| `TRANSITION` | no | 過渡狀態，尚未能判斷穩定可用性。 |
| `UNKNOWN` | no | 未知、未定義或尚未驗證狀態碼。 |

## Source Mapping Contract

- 每個來源 adapter 必須提供 raw code 到 canonical state 的 mapping 文件。
- 每個 label rule 必須有版本，且能重跑同一批歷史資料產生相同 aggregate 結果。
- 不同城市或來源不得共用未驗證的狀態碼語意；即使 raw code 相同，也要先建立 source-specific mapping。
- `RESTRICTED`、`CLOSED`、`OUT_OF_SERVICE`、`TRANSITION`、`UNKNOWN` 預設不作一般可停／不可停 label。
- 前台地圖、第三方網站或 UI 顏色只能作交叉推論證據；不可取代官方 enum 或實測 mapping。

## 新北市 Roadside v1 候選

| 來源 raw code | Canonical state | Reliable label | 備註 |
| --- | --- | --- | --- |
| `parkingstatus=1` | `OCCUPIED` | negative | 由 TDX `SpotStatus=1 使用中`、官方前台紅色有車語意交叉支持。 |
| `parkingstatus=2` | `AVAILABLE` | positive | 由 TDX `SpotStatus=2 空位`、官方前台綠色空位語意交叉支持。 |
| `parkingstatus=3` | `RESTRICTED` 或 `CLOSED` | no | 官方前台存在非收費、時段性禁停與特殊狀態；不當一般 label。 |
| `parkingstatus=5` | `UNKNOWN` 或 `TRANSITION` | no | 尚無官方 enum 定義；保留 raw code 並拒絕。 |

## 與主系統關係

本 schema 是離線 ML 訓練資料治理，不代表主系統即時快照契約已變更。Redis query state、PUBLISHED snapshot 與線上查詢仍以主系統資料契約為準；模型只能在通過回測、版本化與回滾驗證後接入。

## 後續

1. 在 `parking-data-collector` 導入 canonical export 或 relabel staging。
2. 用 S3-010 mapping 重跑 roadside label dry-run，確認 positive/negative/rejected 比例。
3. 將 offstreet 與 roadside 共用欄位收斂到同一個 feature schema，但保留不同 label rule。
4. 模型訓練報告需列出 schema version、label rule version、資料日期、來源覆蓋、拒絕原因與已知限制。
