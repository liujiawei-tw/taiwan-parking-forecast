# S2-HARDEN-002 新莊 Catalog／Source Mapping 覆蓋報告

更新日期：2026-10-01。狀態：第一版覆蓋報告、匯入策略、採用建議、NTPC catalog adapter 與 dry-run orchestration 基線。本文記錄已確認事實、目前工作假設與待驗證項目；不保存官方原始回應、金鑰或敏感設定。

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

## 匯入候選方案與採用建議

| 方案 | 作法 | 優點 | 風險 | 建議 |
| --- | --- | --- | --- | --- |
| 手寫 repeatable seed | 將 2,124 筆路邊與 97 筆路外直接寫入 `R__seed_xinzhuang_parking_catalog.sql` | 最快讓 DB 看起來完整 | SQL 巨大、審查困難、難保留每筆拒絕原因；未來新增地區會複製大量手工資料 | 不採用 |
| 一次性 migration | 以版本化 migration 寫入完整 catalog 與 mapping | 可追溯 schema 歷史，部署時自動套用 | 官方 catalog 更新時不易重跑；資料問題修正需再寫 migration；仍容易把資料與程式版本綁死 | 暫不採用 |
| 管理命令 dry-run + 審查後匯入 | 以 `NtpcCatalogDryRunService` 先量測與產生候選，審查通過後由受控管理命令寫入 DB | 可重跑、可分區、可保留缺 ID／重複／座標／資格問題；未來地區可共用 scope | 需要新增管理命令與人工審查流程；正式寫入前仍要定停用策略 | 採用方向 |
| 後續排程 importer | 週期性跑 catalog adapter，偵測新增、異動與消失 | 長期維護性最好 | 需要穩定的來源語意、版本策略與停用門檻；目前缺多輪證據 | Sprint 3 後再評估 |

採用建議：#41 先以「管理命令 dry-run + 審查後匯入」作下一步方向。正式 importer 寫入前，必須先跑一輪真實 dry-run 並輸出可審查摘要；只有 `sourceTraversalComplete=true`、accepted candidates 與官方基準數差距可解釋、拒絕原因可分流時，才允許建立匯入 PR。匯入 PR 應提交程式與審查摘要，不提交官方原始 JSON 或大檔。

第一版審查摘要至少包含：

- scope 與來源設定：`serviceArea`、路邊 `areacode`、路外 `AREA`、page size、max pages。
- 擷取量測：路邊／路外各自 pages、HTTP attempts、response bytes、elapsed millis、traversal complete。
- 來源 rows：官方 rows、scope rows、accepted candidates。
- 拒絕原因：missing source ID、duplicate source ID、invalid coordinates、unknown eligibility、not car eligible。
- 現有 mapping：existing verified mappings、匯入後預期 verified mappings。
- 採用／拒絕建議：可匯入、需人工審查、拒絕缺頁或拒絕無 accepted candidates。

## 覆蓋與 300 公尺預測規則

覆蓋判斷分三層，不可混用：

1. **Catalog 覆蓋**：`parking_place` 是否收錄該服務區的完整候選位置。
2. **Mapping 覆蓋**：`source_mapping` 是否能把官方 source ID 對到系統位置，且狀態為 `VERIFIED`。
3. **Realtime 覆蓋**：已發布 snapshot 是否有足夠且新鮮的即時觀測。

路邊 300 公尺區域預測只有在下列條件全部成立時才可輸出 `READY` 機率：

- 查詢目的地位於已支援 service area，且 300 公尺圓不跨出已確認的收錄邊界。
- 路邊 catalog 與 mapping 對該 service area 達完整門檻，且最近一次 catalog dry-run 沒有無法解釋的缺頁、重複或座標異常。
- 300 公尺圓內的路邊格位集合可由系統 catalog 重現，且路邊格位的汽車資格可解釋。
- 即時來源有可用的 `PUBLISHED` snapshot，且資料時效未超過當前門檻。
- Sprint 3 訓練標籤與模型回測已通過，且訓練時的 300 公尺定義與線上查詢一致。

只要任一條件不成立，路邊區域預測必須拒絕或標示資料不足：

- catalog 或 mapping 仍是 `PARTIAL`：`predictions.roadside.status=INSUFFICIENT_DATA`，原因建議為 `CATALOG_COVERAGE_PARTIAL`。
- coverage boundary 未知或查詢圓跨出已確認邊界：`predictions.roadside.status=BLOCKED` 或 `INSUFFICIENT_DATA`，原因建議為 `COVERAGE_BOUNDARY_UNKNOWN`／`SEARCH_AREA_CROSSES_COVERAGE_BOUNDARY`。
- 即時 snapshot 缺失、過期或刷新拒絕：不得用舊的目前有位數推估抵達時機率；原因建議為 `REALTIME_SNAPSHOT_MISSING`、`REALTIME_SNAPSHOT_EXPIRED` 或 `SOURCE_QUALITY_REJECTED`。
- 模型或回測未完成：原因建議為 `MODEL_NOT_READY` 或 `BACKTEST_NOT_VERIFIED`。

路外逐場預測可比路邊區域預測更早開放，但僅限「已收錄且 mapping verified 的單一停車場」。若路外 catalog 仍 `PARTIAL`，可對已收錄場站顯示逐場資料不足或預測結果，但不得宣稱 300 公尺內路外場站完整，也不得把空清單解讀為附近沒有停車場。

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

## NTPC Catalog Dry Run Orchestration

後續已新增 `NtpcCatalogDryRunService` 與 `NtpcCatalogImportProperties`，把 client、mapper 與 generic dry-run 統計串成可重跑流程。這個 service 仍不寫 DB、不發布 snapshot，也不會把候選資料自動標成 verified mapping；它只產生審查用報告。

第一版設定放在 `parking.catalog.ntpc`：

- `page-size` 與 `max-pages`：獨立於使用者查詢刷新設定，避免把 catalog 全量擷取和即時 refresh cooldown 混在一起。
- `scopes[]`：每個 scope 定義 `service-area`、`roadside-area-code`、`off-street-area-name`。預設第一個 scope 是 `NEW_TAIPEI_XINZHUANG`／`65000050`／`新莊區`；未來新增板橋、中和或其他縣市時，應新增 scope，而不是複製一套新莊專用 service。

Dry run result 會包含：

- generic `CatalogImportDryRunReport`：scope rows、accepted candidates、missing source ID、duplicate source ID、invalid coordinates、unknown eligibility、existing verified mappings 與 recommendation。
- roadside fetch summary：pages、HTTP attempts、response bytes、elapsed millis、是否取齊。
- off-street catalog fetch summary：pages、HTTP attempts、response bytes、elapsed millis、是否取齊。
- `sourceTraversalComplete`：路邊與路外 catalog traversal 都取齊時才為 true；達到 `max-pages` 而最後一頁仍滿頁時，會標成 false，避免把缺頁結果偽裝成完整 catalog。

## 待驗證

- 路邊 `id`／`cellid` 長期穩定性與重複列語意。
- 路邊 `name`、開放日、開放時段、備註是否足以判斷一般汽車資格與條件式可停。
- 路外靜態目錄是否需要全量分頁取得，避免只使用前 1,000 筆造成假完整。
- 路外 TWD97 座標轉 WGS84 已有單元測試基線；仍需以更多真實點驗證誤差與入口座標策略。
- `NtpcCatalogDryRunService` 尚未對外提供 CLI 或管理端點；目前只能由程式層測試或後續管理流程呼叫。
- catalog 更新時，單筆暫時消失應標 `INACTIVE`、保留上一版或等待多輪確認。
- 新莊服務區的可信覆蓋邊界仍需定義；目前只知道行政區 scope，不足以判定任意 300 公尺圓是否完整位於已收錄範圍。

## 驗證方式

後端新增 `CatalogCoverageReportService`，使用目前資料庫內容與 S2-020/S2-021 的官方基準數產生 coverage report。測試固定目前 seed 的覆蓋率：

- 路邊：7 / 2,124 = 0.33%，`PARTIAL`。
- 路外：3 / 97 = 3.09%，`PARTIAL`。

這份報告只用於覆蓋治理與 Sprint 3 進入條件，不是使用者 API 契約，也不是正式服務承諾。

NTPC adapter 基線的自動化驗證：

- `NtpcOpenDataDtoTest`：解析路外靜態目錄欄位 `ID`、`AREA`、`NAME`、`ADDRESS`、`TW97X`、`TW97Y`、`TOTALCAR`。
- `NtpcOpenDataClientTest`：確認路外靜態目錄使用設定中的 dataset ID 組成 request。
- `NtpcCatalogRecordMapperTest`：確認路邊／路外 scope 篩選、來源 ID fallback、車種資格、座標解析失敗分流，以及路外 TWD97 轉 WGS84 的誤差範圍。
- `NtpcCatalogDryRunServiceTest`：確認 orchestration 會依設定分頁抓路邊與路外 catalog、保留 fetch measurements、套用 scope mapping、呼叫 generic dry-run，並在 page limit 截斷時標示 traversal incomplete。
