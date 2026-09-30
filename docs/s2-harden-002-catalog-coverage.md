# S2-HARDEN-002 新莊 Catalog／Source Mapping 覆蓋報告

更新日期：2026-09-30。狀態：第一版覆蓋報告與匯入策略。本文記錄已確認事實、目前工作假設與待驗證項目；不保存官方原始回應、金鑰或敏感設定。

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

## 待驗證

- 路邊 `id`／`cellid` 長期穩定性與重複列語意。
- 路邊 `name`、開放日、開放時段、備註是否足以判斷一般汽車資格與條件式可停。
- 路外靜態目錄是否需要全量分頁取得，避免只使用前 1,000 筆造成假完整。
- 路外 TWD97 座標轉 WGS84 的誤差與入口座標策略。
- catalog 更新時，單筆暫時消失應標 `INACTIVE`、保留上一版或等待多輪確認。

## 驗證方式

後端新增 `CatalogCoverageReportService`，使用目前資料庫內容與 S2-020/S2-021 的官方基準數產生 coverage report。測試固定目前 seed 的覆蓋率：

- 路邊：7 / 2,124 = 0.33%，`PARTIAL`。
- 路外：3 / 97 = 3.09%，`PARTIAL`。

這份報告只用於覆蓋治理與 Sprint 3 進入條件，不是使用者 API 契約，也不是正式服務承諾。
