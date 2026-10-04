# S3-016 資料來源覆蓋決策與跨來源狀態對照

更新日期：2026-10-04。狀態：Sprint 3 資料來源治理文件；本文件只收斂來源選擇、覆蓋證據與狀態對照，不代表已完成完整 catalog 匯入、模型訓練或線上預測。

## 結論

新莊 MVP 的即時停車來源以新北市官方 Open Data 為主。TDX 保留為未來全臺擴充與交叉驗證來源，但目前不作新莊 MVP 的主要即時來源，也不作 2026-09-17 至 2026-09-23 新莊訓練資料的完整下載來源。

理由：

- 新北市官方 API 已能取得新莊路邊與路外資料，且可用官方欄位本地篩出新莊範圍。
- TDX 即時路邊可用單格位 ID 查詢，但路外 NewTaipei 基礎 endpoint 目前回空集合，無法與新北市路外做同場站比較。
- TDX 歷史路邊 NewTaipei 只能確認 city-level metadata；`ParkingSpotID` server-side filter 實測未生效，若下載全新北再本地篩新莊，成本遠高於免費基礎會員額度。
- TDX 歷史路外 City endpoint 的官方 OAS 不含 `NewTaipei`，實測 NewTaipei 回 400。
- TDX 與新北市 raw status code 不能直接互換；必須經 source-specific mapping 轉成 canonical state。

## 來源優先序

| 用途 | 第一優先 | 第二優先 | 不採用／暫緩 |
| --- | --- | --- | --- |
| 新莊 MVP 即時路邊 | 新北市官方路邊停車空位查詢，全量擷取後以 `areacode=65000050` 篩選 | TDX 基礎路邊即時 API 作交叉驗證 | 不用 TDX 作新莊路邊主要來源，直到確認覆蓋、費率與欄位對照 |
| 新莊 MVP 即時路外 | 新北市公有路外停車場靜態 catalog 以 `AREA=新莊區` 篩 ID，再 join 即時剩餘位 | 後續若 TDX NewTaipei 路外有資料，再建立對照 | TDX NewTaipei 路外 endpoint 目前回空集合，不能當 MVP 來源 |
| 新莊訓練歷史 | 主系統 `PUBLISHED` snapshot 歷史與 collector 離線 ZIP，後續由 S3-018 銜接 | 少量 TDX 歷史樣本作 ID／狀態交叉檢查 | 不下載 TDX 2026-09-17..2026-09-23 全新北 raw data，除非 Sponsor 另行確認大量點數消耗 |
| 全臺擴充 | TDX 作主要候選 | 各地方政府免費 API 作補強 | 不把新北市規格客製成全臺通用資料模型 |

## 已驗證證據

| 項目 | 結果 | 工程判讀 |
| --- | --- | --- |
| 新北市路邊全量即時 | 30,941 rows，約 11.27 MB；新莊 `areacode=65000050` 有 2,124 rows | 可作新莊 MVP 路邊即時來源；仍需 S3-017 做完整 catalog／mapping 驗收 |
| 新北市路外 catalog | 全量 1,000 rows，約 390 KB；`AREA=新莊區` 100 rows | 可取得新莊路外候選場站 ID |
| 新北市路外 availability | 全量 1,000 rows，約 36 KB；join catalog 後新莊 100 rows | 可作新莊 MVP 路外即時來源 |
| TDX 即時路邊單格位 | `ParkingSpotID=153854` 可查到單筆，與新北市 `id=153854`／`roadid=R27` 對上 | ID 可交叉驗證，但狀態碼不同，不能直接共用 raw code |
| TDX 即時路外同場站 | 新北市 `050193` 有 `AVAILABLECAR=55`；TDX `CarParkID=050193` 靜態與即時皆回空集合 | 目前 TDX NewTaipei 路外無可比較資料 |
| TDX 歷史路邊 metadata | 2026-09-17..2026-09-23 全市 35,480,193 rows／5,403,073,037 bytes | 只能作資料量與成本評估；本輪未下載 raw data |
| TDX 歷史路邊 filter | `$filter=ParkingSpotID eq '153854'` 回未篩選前幾筆 | 目前不能靠此 endpoint 低成本只取新莊 ID |
| TDX 歷史路外 NewTaipei | City enum 不含 `NewTaipei`，實測回 400 | 不能用此 TDX historical city endpoint 取得新莊路外歷史 |

## 狀態碼對照原則

跨來源狀態要先映射為 canonical availability state，再進 API response、訓練 label 或模型 feature。禁止在模型與前端直接混用 `parkingstatus`、`SpotStatus`、`AVAILABLECAR` 等來源原碼。

| 來源 | raw code | canonical state | label 使用 | 證據等級 |
| --- | --- | --- | --- | --- |
| 新北市路邊 | `parkingstatus=1` | `OCCUPIED` | negative | TDX `SpotStatus=1`、官方前台紅色有車語意、collector 分布交叉支持 |
| 新北市路邊 | `parkingstatus=2` | `AVAILABLE` | positive | TDX `SpotStatus=2`、官方前台綠色空位語意、collector relabel 驗證支持 |
| 新北市路邊 | `parkingstatus=3` | `RESTRICTED` 或 `CLOSED` | no | 官方前台非收費／特殊狀態圖例與實測分布支持保守拒絕 |
| 新北市路邊 | `parkingstatus=5` | `UNKNOWN` 或 `TRANSITION` | no | 尚無官方 enum；保守拒絕 |
| TDX 路邊 | `SpotStatus=1` | `OCCUPIED` | negative | 交通部停車資料標準 |
| TDX 路邊 | `SpotStatus=2` | `AVAILABLE` | positive | 交通部停車資料標準 |
| TDX 路邊 | `SpotStatus=254/255` | `UNKNOWN` | no | 交通部停車資料標準與本機樣本 |
| 新北市路外 | `AVAILABLECAR > 0` | `AVAILABLE` 或 count feature | positive candidate | 需搭配場站 active、汽車資格、資料新鮮度 |
| 新北市路外 | `AVAILABLECAR = 0` | `FULL` | negative | 需搭配場站 active、汽車資格、資料新鮮度 |
| 新北市路外 | `AVAILABLECAR < 0` | `UNKNOWN` | no | `-9` 已知為暫無即時資料；其他負值保守拒絕 |

## S3-017 交接條件

S3-016 不寫入資料庫，也不宣稱完整新莊覆蓋。下一步 S3-017 應基於本文件做完整 catalog／mapping dry-run：

- 路邊以新北市官方路邊全量資料為 catalog 候選，篩 `areacode=65000050`。
- 路外以新北市官方靜態 catalog 篩 `AREA=新莊區`，再和 availability ID join。
- dry-run 必須分開記錄 accepted、duplicate source ID、invalid coordinate、unknown eligibility、missing dynamic pair、unmapped、existing verified mapping。
- coverage boundary 必須能回答：目的地 500 公尺圓內哪些區域是已確認覆蓋、部分覆蓋或未知覆蓋。
- TDX 只作交叉驗證欄位，不作 S3-017 的完整新莊 catalog 來源。

## 待保留問題

- 新北市官方是否有公開 `parkingstatus`／`cellstatus` 完整 enum 字典；目前只能用官方前台圖例、TDX 標準與實測分布做保守推論。
- TDX NewTaipei 路外 endpoint 是否只是暫時無資料，或服務長期不覆蓋；需日後重新小量驗證。
- TDX 歷史 API 是否有其他能依行政區、座標或 ID 降低資料量的正式查詢方式；目前 `ParkingSpotID` filter 實測無效。
- 全臺擴充時，其他縣市是否具備 district 欄位、座標品質與可比對 ID；不得套用新北市 `areacode`／`AREA` 規則。
