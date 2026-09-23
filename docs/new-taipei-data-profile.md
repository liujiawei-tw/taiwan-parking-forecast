# 新北市路邊格位歷史資料：首次盤點

盤點日期：2026-09-23。來源：Sponsor 自 TDX 購買「新北市路邊停車格位動態歷史資料」，日期 2026-08-09。以下為本機原始檔的初步統計；狀態碼的業務意義尚待依 TDX 官方資料字典核對。

## 取得與檔案

- TDX 購買頁：申請時間 2026-09-23 15:46、資料時間區間 2026-08-09、產製進度 1/1、顯示檔案大小 9.2 MB、**預估點數 0.56**、狀態「已購買」。預估點數不等於已核對的實扣點數。
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
