# S2-HARDEN-002 新莊 Catalog／Source Mapping 覆蓋報告

更新日期：2026-09-30。狀態：第一版覆蓋報告、匯入策略與 NTPC catalog adapter 基線。本文記錄已確認事實、目前工作假設與待驗證項目；不保存官方原始回應、金鑰或敏感設定。

## 結論

目前主系統的新莊 `parking_place`／`source_mapping` 仍是可追溯官方小樣本，不是完整新莊 catalog。

| 類別 | 官方基準數 | 目前 active catalog | 目前 verified mapping | catalog 覆蓋 | mapping 覆蓋 | 狀態 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 路邊汽車格位 | 2,124 | 7 | 7 | 0.33% | 0.33% | PARTIAL |
| 路外汽車停車場 | 97 | 3 | 3 | 3.09% | 3.09% | PARTIAL |

因此，在完成完整匯入與覆蓋驗收前：

- 不宣稱新莊 catalog 完整。
- 不輸出「300 公尺內至少一格路邊車位可停」的完整區域機率。
- 查詢結果可以顯示已收錄的 catalog 與已發布觀測，但必須維持 `PARTIAL`／資料不足語意。

## 已確認事實

- S2-020 低頻核對時，新北市路邊資料完整回應約 30,941 筆，其中新莊 `areacode=65000050` 有 2,124 筆。
- S2-020 低頻核對時，路外靜態目錄前 1,000 筆中篩到 97 筆新莊汽車場，且這 97 個 ID 均能在路外即時剩餘位資料中對上。
- S2-021 單輪量測中，S2-020 seed 的路邊 7 個 verified mapping ID 皆找到；路外 3 個 verified mapping ID 皆找到。
- 目前 seed 只保留 7 筆路邊、3 筆路外，用於主流程與 mapping 契約驗證。

## 匯入策略

第一版完整匯入不直接把全量 SQL 手寫進 repeatable seed。建議拆成三步：

1. 建立可重跑的匯入輸入檔或 importer。
   - 路邊來源：以官方路邊資料 `areacode=65000050` 篩選一般汽車格位。
   - 路外來源：以官方路外靜態目錄篩選新莊汽車停車場，再與即時剩餘位資料以 `ID` 對齊。
   - 保留來源 ID、名稱、座標、原始位置提示與匯入批次版本。

2. 分開處理 catalog 與 mapping。
   - `parking_place` 只保存系統內可查詢的位置與資格狀態。
   - `source_mapping` 保存 `source_system`、`dataset_code`、`source_place_id`、mapping 狀態與版本。
   - 缺 source ID、重複 source ID、座標異常與車種資格不明時，不自動標 `VERIFIED`。

3. 建立覆蓋驗收門檻。
   - 路邊完整匯入候選：active catalog 與 verified mapping 接近 2,124，且缺 ID／重複／座標異常有分開報告。
   - 路外完整匯入候選：active catalog 與 verified mapping 接近 97，且所有匯入 ID 可對上即時剩餘位資料。
   - 若任何一類未達完整門檻，API 與文件必須維持 `PARTIAL`，不得包裝成完整覆蓋。

## 正規化 Import Dry Run

後續不應把新莊寫成唯一規格。第一版已新增 source-agnostic `CatalogImportDryRunService`，使用共同的 `CatalogSourceRecord` 作為 importer adapter 的輸出格式：

- `serviceArea`：例如 `NEW_TAIPEI_XINZHUANG`，未來可加入板橋、中和或其他縣市。
- `sourceSystem`／`datasetCode`／`sourcePlaceId`：保留來源與資料集邊界，避免不同地區或不同來源 ID 撞名。
- `placeType`：路邊格位或路外停車場。
- `displayName`、WGS84 `latitude`／`longitude`、`carEligibleStatus`。
- `rawNameHint`／`rawLocationHint`：只保存必要提示，不保存完整原始回應。

Dry run 會依 `CatalogImportScope` 統計：

- scope 內來源 rows。
- accepted catalog candidates。
- missing source ID。
- duplicate source ID。
- invalid coordinates。
- unknown eligibility。
- existing verified mappings。
- import recommendation。

這讓新莊只是第一個 `scope config`；未來新增地區時，應新增對應的 scope 與來源 adapter，不應複製新莊專用 service。

## NTPC Catalog Adapter 基線

第一版已新增 `NtpcCatalogRecordMapper`，把新北市 Open Data 的路邊與路外來源列轉成共用 `CatalogSourceRecord`，供 `CatalogImportDryRunService` 使用。這仍是 dry-run 輸入轉換，不會寫入 `parking_place` 或 `source_mapping`，也不會把任何來源列自動標為 `VERIFIED`。

路邊 adapter：

- 以呼叫端傳入的 `serviceArea` 與 `roadsideAreaCode` 篩選，例如新莊第一個 scope 使用 `NEW_TAIPEI_XINZHUANG`／`65000050`；service 本體不綁死新莊。
- `sourceSystem=NTPC_OPEN_DATA`、`datasetCode=NTPC_ROADSIDE_SPOT`、`placeType=ON_STREET_SPOT`。
- 來源 ID 優先使用官方 `id`，缺值時退回 `cellid`，讓 dry-run 能另外統計缺 ID 與重複 ID。
- 座標以來源 WGS84 `latitude`／`longitude` 解析；解析失敗時保留 `null`，交由 dry-run 計入 invalid coordinates。
- 車種資格延續目前正規化規則：文字含 `機車` 為 `NOT_ELIGIBLE`；含 `小型車` 或 `汽車` 為 `CONFIRMED`；其餘為 `UNKNOWN`。

路外 adapter：

- 使用新北市公有路外停車場靜態目錄 dataset `B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68`，以呼叫端傳入的 `offStreetAreaName` 篩選，例如 `新莊區`。
- `sourceSystem=NTPC_OPEN_DATA`、`datasetCode=NTPC_OFF_STREET_AVAILABILITY`、`placeType=OFF_STREET_LOT`。目前使用即時剩餘位 dataset code 作 mapping key，因靜態目錄 `ID` 與即時剩餘位 `ID` 是同一個後續查詢 key。
- 靜態目錄的 TWD97 `TW97X`／`TW97Y` 會轉為 WGS84；解析或轉換失敗時保留 `null`，交由 dry-run 計入 invalid coordinates。
- `TOTALCAR > 0` 視為汽車資格 `CONFIRMED`；`TOTALCAR <= 0` 為 `NOT_ELIGIBLE`；缺值或格式異常為 `UNKNOWN`。
- raw hint 只保留名稱、行政區、地址、TWD97 與汽車格數等必要排查線索，不保存完整官方原始回應。

## 待驗證

- 路邊 `id`／`cellid` 長期穩定性與重複列語意。
- 路邊 `name`、開放日、開放時段、備註是否足以判斷一般汽車資格與條件式可停。
- 路外靜態目錄是否需要全量分頁取得，避免只使用前 1,000 筆造成假完整。
- 路外 TWD97 座標轉 WGS84 已有單元測試基線；仍需以更多真實點驗證誤差與入口座標策略。
- catalog 更新時，單筆暫時消失應標 `INACTIVE`、保留上一版或等待多輪確認。

## 驗證方式

後端新增 `CatalogCoverageReportService`，使用目前資料庫內容與 S2-020/S2-021 的官方基準數產生 coverage report。測試固定目前 seed 的覆蓋率：

- 路邊：7 / 2,124 = 0.33%，`PARTIAL`。
- 路外：3 / 97 = 3.09%，`PARTIAL`。

這份報告只用於覆蓋治理與 Sprint 3 進入條件，不是使用者 API 契約，也不是正式服務承諾。

NTPC adapter 基線的自動化驗證：

- `NtpcOpenDataDtoTest`：解析路外靜態目錄欄位 `ID`、`AREA`、`NAME`、`ADDRESS`、`TW97X`、`TW97Y`、`TOTALCAR`。
- `NtpcOpenDataClientTest`：確認路外靜態目錄使用設定中的 dataset ID 組成 request。
- `NtpcCatalogRecordMapperTest`：確認路邊／路外 scope 篩選、來源 ID fallback、車種資格、座標解析失敗分流，以及路外 TWD97 轉 WGS84 的誤差範圍。
