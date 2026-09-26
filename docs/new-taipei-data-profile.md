# 新北市路邊格位歷史資料：首次盤點

盤點日期：2026-09-23。來源：Sponsor 自 TDX 購買「新北市路邊停車格位動態歷史資料」，日期 2026-08-09。以下為本機原始檔的初步統計；狀態碼的業務意義尚待依 TDX 官方資料字典核對。

## 取得與檔案

- TDX 購買頁：申請時間 2026-09-23 15:46、資料時間區間 2026-08-09、產製進度 1/1、顯示檔案大小 9.2 MB、**實扣點數 0.56**、狀態「已購買」。實扣點數由 Sponsor 確認。
- 原始 ZIP：9,683,618 bytes；解壓 CSV：145,290,246 bytes。TDX 頁面的 9.2 MB 與 ZIP 大小約相符；不要拿解壓後容量直接推算扣點。
- Sponsor 提供 ZIP MD5：`833985e44efcf0ddd6adcdd882905387`；本機核對一致。
- 原始 ZIP 和 CSV 均放在 `data/raw/tdx/new-taipei/on-street-spot-availability/`，由 Git 忽略。

## CSV 概況

- 一個 CSV，**2,938,401 列**，31,328 個不同 `ParkingSpotID`，964 個非空 `ParkingSegmentID`。
- 欄位：`ParkingSpotID`、`ParkingSegmentID`、`ServiceStatus`、`SpotStatus`、`DeviceStatus`、`ChargeStatus`、`Remark`、`DataCollectTime`。
- `DataCollectTime` 覆蓋 2026-08-09 00:00:30 至 23:56:20（+08:00）；所有列都有格位 ID 與時間，716 列的路段 ID 為空白。
- `SpotStatus`：代碼 1 有 2,219,606 列、2 有 454,562 列、254 有 264,233 列；`ServiceStatus`：代碼 1 有 2,674,168 列、2 有 264,233 列。這些只是原始代碼分布，**不能在核對官方定義前把 1／2／254 稱為有位、占用或故障**。
- `DeviceStatus` 與 `ChargeStatus` 在這份資料中皆為代碼 1；`Remark` 全空。檔案第一列是 17:00，末列是 01:00，顯示資料列未按時間排序。

## 建模提醒

1. 先核對 TDX 狀態碼、格位與路段 ID 定義，排除不可服務或未知狀態。
2. 依 `ParkingSpotID`、`DataCollectTime` 排序，檢查相同鍵是否重複、每格位觀測間隔、資料缺口與異常狀態轉換。
3. 第一版以指定格位或路段的到達時可停機率作試點，明確區分觀測到的真實狀態與模型預測。一天資料只適合資料管線和最初基準試驗；若要驗證跨日泛化，需增加日期並用未來日期作測試。


## 2026-09-26 主系統即時來源 Client 初版

S2-014 已在主系統後端建立新北市官方即時來源 client 邊界。此 client 只負責呼叫與解析官方原始 JSON，不做資料正規化、不寫入 MySQL、不發布快照，也不提供前端直接使用。

### 官方資料集與預設設定

| 類型 | 官方資料集頁面 | 預設 dataset id | 主系統用途 |
| --- | --- | --- | --- |
| 路邊停車空位 | https://data.ntpc.gov.tw/datasets/54a507c4-c038-41b5-bf60-bbecb9d052c6 | `54A507C4-C038-41B5-BF60-BBECB9D052C6` | 解析格位層級原始即時資料，供 S2-015 正規化。 |
| 公有路外停車場即時剩餘車位 | https://data.ntpc.gov.tw/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78 | `e09b35a5-a738-48cc-b0f5-570b67ad9c78` | 解析停車場層級原始即時資料，供 S2-015 正規化。 |

預設 API path 為 `/api/datasets/{datasetId}/json`，並支援 `page`、`size` 查詢參數。實際部署時可用環境變數覆蓋 base URL、dataset id、JSON path、timeout、最大嘗試次數與是否允許外部呼叫。

### 目前保留的原始欄位

路邊 DTO 先保留：`id`、`cellid`、`name`、`day`、`hour`、`pay`、`paycash`、`memo`、`roadid`、`roadname`、`cellstatus`、`isnowcash`、`parkingstatus`、`latitude`、`longitude`、`countycode`、`areacode`。這些欄位仍是原始資料，不能在 S2-014 直接推論為可停、不可停或汽車適用。

路外 DTO 先保留：`ID` 與 `AVAILABLECAR`；測試也接受 `AVAILABLE` 作為 alias，因為目前討論中曾出現不同欄位名稱。S2-015 需要再用官方欄位說明確認最終映射規則。

### 已知邊界

- 自動測試使用 fixture 與 mock HTTP，不會真的呼叫新北市 API。
- S2-014 的 client 失敗時會丟出明確例外，後續 S2-016／S2-018 才會決定如何記錄 ingestion run、冷卻與 UI 降級。
- S2-014 不判斷 `cellstatus`、`parkingstatus`、`AVAILABLECAR=-9` 等業務語意；這些留到 S2-015 正規化與資料品質規則。
