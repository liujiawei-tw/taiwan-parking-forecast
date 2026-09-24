# ML 來源資料統一規格（Sprint 0 草案）

更新：2026-09-23。此文件定義來源到訓練資料的語意，不代表模型已訓練或欄位品質已通過驗收。

## 目標與來源

| 預測對象 | 第一階段資料 | 訓練目標 | 狀態 |
| --- | --- | --- | --- |
| 路邊 | 新北市政府新莊即時格位日 CSV；TDX 新北市格位歷史檔供交叉檢查 | 抵達時，指定範圍內可觀測且適用的空格數，並衍生「至少一格」 | TDX 狀態碼已查到官方定義；新北市碼表、靜態格種、合法性及覆蓋率仍待驗證 |
| 路外 | 新北市政府新莊公有路外停車場即時資料，由 collector 累積日 CSV | 抵達時指定停車場的汽車剩餘位數，並衍生「至少一位」 | 可做資料探索；來源更新時間、其他負值及場站對應仍待驗證 |

截至 2026-09-23，TDX 歷史服務停車資訊清單有 18 項，列出新北市路邊格位與路邊路段，但未列「新北市停車場剩餘位歷史資料」。因此路外新莊試點先用官方即時資料累積歷史，不申請不存在的 TDX 新北市路外歷史項目。桃園機場路外歷史檔保留作格式與流程實驗，不混入新莊模型。

## 統一觀測層 v0.1

每筆來源觀測先保留原始欄位與來源檔，再映射成共同欄位。`null` 代表未提供或無法判定；不可當成 0。

| 欄位 | 定義／規則 |
| --- | --- |
| `source_system`, `source_dataset`, `source_file` | 明確記錄新北市政府／TDX、資料集及原始檔，保留資料血緣 |
| `entity_type`, `source_entity_id` | `on_street_spot`、`on_street_segment` 或 `off_street_lot`；來源 ID 保留原樣，不用名稱代替 |
| `canonical_entity_id`, `mapping_version` | 核對後才填跨來源統一 ID；無法對應時保持空值並記錄版本 |
| `observed_at`, `collected_at` | 來源觀測／產製時間與收集器取得時間分開，均帶時區；缺來源時間時不可假稱擷取時間就是觀測時間 |
| `district_code`, `latitude`, `longitude`, `location_source` | 行政區及位置由可信靜態資料或來源提供；原始動態檔通常不足以獨立判斷新莊 |
| `space_type_raw`, `vehicle_class`, `eligibility` | 保留來源車位類型，核對後才映射一般汽車等適用性；特殊／保留／收費限制另標記 |
| `service_status_raw`, `availability_raw`, `available_count`, `capacity` | 原值完整保存；統一數量只在語意、單位及異常值通過驗證後填入 |
| `observation_state`, `quality_reason` | 區分 `valid`、`unknown`、`stale`、`closed`、`invalid`；記錄不能用於標籤的原因 |

原始資料、統一觀測、訓練樣本各是不同層。統一欄位對應成功，也不表示該來源能產生可靠的 ML 標籤。

## 已查到的來源對應

| 來源欄位 | 統一語意 | 使用限制 |
| --- | --- | --- |
| TDX 格位 `ParkingSpotID`、collector `spot_id` | 格位來源 ID | 目前 2,124 個新莊即時格位 ID 可對上 8/27 歷史檔，但現有 ID 未必涵蓋已消失的歷史格位 |
| TDX 格位 `SpotStatus` | `1=使用中`、`2=空位`、`3=已預約`、`254=其他`、`255=未知` | 只有營業中、設備狀態可信、格種適用的 `2` 才是候選空位；不能僅憑動態資料保證合法可停 |
| TDX 格位 `ServiceStatus` | `1=營業中`、`2=暫停營業` | 停業資料不標為「已滿」；屬不可服務／未知狀態 |
| collector 路邊 `parking_status_raw`、`cell_status_raw` | 新北市政府來源原始碼 | 官方資料集頁列出欄位名稱，但沒有列出 `parkingstatus`、`cellstatus` 的數值對照。collector 契約目前將 `parkingstatus 0/1` 映射可用／不可用；Sponsor 提供 `cellstatus 0/1` 的說明，仍需找到可引用的官方碼表或來源確認，不能套用 TDX 的 `SpotStatus 1/2` |
| TDX 路段 `ParkingSegmentID`、collector `road_id` | 路段來源 ID | 目前 81 個非空新莊 ID 可對上七日歷史檔；還有 `RA1` 與空 ID，故不是完整歷史範圍 |
| TDX 路段 `SpaceType` | 官方標準 `1=自小客車位`、`2=機車位` 等 | 使用車種碼時需對照最新 TDX 規格與實際來源 |
| TDX 路段 `AvailableSpaces`、`SpaceTypeAvailableSpaces` | 前者為原始整體欄位，後者為車種列剩餘位欄位 | 在已對上的 341,473 列中，後者全為 0、前者多數大於 0；車種欄位暫停作標籤，跨 `SpaceType` 列不可重複加總 |
| collector 路外 `lot_id`、`available_car_raw`、`total_car` | 場站 ID、汽車剩餘位原值及總位數 | 新北市官方頁明確表示 `-9` 是部分場站因更換營運廠商而未提供即時車位，不是 0。collector 目前把所有負值當未知，這是保守規則；其他負值意義仍待查。官方頁描述寫 `AVAILABLE`，主要欄位列 `AVAILABLECAR`，需以實際 API 結構核對來源欄位；車場靜態與動態 ID 也需核對 |

交通部停車標準提供 `SpotStatus`、`ServiceStatus`、`SpaceType` 的代碼說明；TDX OData 說明只解釋查詢過濾，不能當作狀態碼字典。`ParkingXMLValidation` 是來源單位產製與驗證 XML 的範例程式，不是目前 CSV 的實際品質證明。

## 兩種訓練樣本

1. **路邊區域樣本**：一個版本化的目的地圓心與直線 300 公尺範圍 × 一個觀測時間為一列。訓練與線上使用同一套範圍／汽車資格／空位規則；只統計範圍內「有觀測且適用」的格位，保留預期格位數、實際觀測數、有效比例及空格數。有效比例不足時不產生 `available_count=0` 標籤。產品可由空格數推得「至少一格」，但這不是保證抵達時能占到格位。訓練圓心如何涵蓋任意新莊目的地仍待設計。
2. **路外停車場樣本**：一個停車場 × 一個觀測時間為一列。保存汽車剩餘位、總位數、資料時間及異常狀態。不同場站不混為同一標籤；數值缺漏／負值不標為零。

特徵只能使用發出預測當時已取得的觀測。依時間順序分訓練、驗證與測試，另檢查不同圓心的空間泛化；以「維持目前狀態」及「同時段歷史平均」作基準。區域半徑已定為目的地直線 300 公尺；預測時距、容許觀測間隔、最低有效比例及驗收指標仍待決，先不寫死。

## ML 暫停點與進入下一階段條件

本階段先停在資料契約：暫不選模型或宣稱準確度。開始訓練前至少需要：

- 完成新北市政府 `parkingstatus`／`cellstatus` 數值碼表核對，以及 TDX／新北市 ID 與靜態格種對照；官方資料集頁目前僅列欄位名稱，未列數值枚舉。
- 量測 collector 跨日成功率、時間缺口、格位覆蓋率及路外有效車位比例；只用「部分列」或單日資料不能驗證跨日泛化。
- 定義固定區域與時間對齊規則，檢查相同時間重複列、資料延遲、特殊／保留格與無法定位的場站。
- 對 TDX 路段車種剩餘位全零問題取得來源說明或排除該欄位。

可並行進入下一個產品／後端設計階段：目的地查詢、即時資料流、額度控制、統一觀測資料儲存及 API 契約。這些工作不依賴先完成模型訓練。

## 依據

- [TDX 歷史服務清單](https://tdx.transportdata.tw/data-service/historical)（2026-09-23 查核）
- [交通部停車資料標準說明](https://traffic.transportdata.tw/ParkingStandard)及[交通部停車資料標準文件](https://www.motc.gov.tw/uploaddowndoc?file=bussiness%2F201901091449481.pdf&filedisplay=201901091449481.pdf&flag=doc)
- [TDX OData 查詢說明](https://motc-ptx.gitbook.io/tdx-xin-shou-zhi-yin/api-te-se-shuo-ming/zhi-yuan-odata-cha-xun-yu-fa)
- [ParkingXMLValidation](https://github.com/ptxmotc/ParkingXMLValidation)
- [新北市路邊停車空位查詢資料集](https://data.ntpc.gov.tw/datasets/54a507c4-c038-41b5-bf60-bbecb9d052c6)及[新北市公有路外停車場即時賸餘車位數資料集](https://data.ntpc.gov.tw/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78)
- collector repository 的 `docs/data-contract.md` v0.2 與本專案 `docs/new-taipei-historical-inventory.md`
