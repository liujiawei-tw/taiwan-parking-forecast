# S3-001 已發布觀測匯出與標籤 dry run

更新日期：2026-10-01。狀態：第一版後端 dry run service 與報告規則。本文只定義候選訓練標籤的審查摘要，不訓練模型、不輸出預測機率、不提交大型匯出檔或原始 API 回應。

## 目標

Sprint 3 的第一步是確認主系統已發布觀測能不能形成可訓練標籤。dry run 只回答：

- 有多少已發布觀測可成為候選樣本。
- 有多少樣本必須拒絕。
- 拒絕原因是覆蓋不足、沒有範圍內觀測、有效觀測比例不足、品質不可用、資料過期、車種資格不符或可用數未知。

這些結果是資料品質證據，不是模型品質，也不是服務承諾。

## 資料來源

第一版後端新增 `TrainingLabelDryRunService`，只透過 `AvailabilityObservationRepository` 讀取：

- `snapshot_status = PUBLISHED`
- 指定 `dataset_code`
- 指定 `system_fetched_at` 時間窗，起點含括、終點不含括，避免相鄰批次重複取樣

不讀 `CANDIDATE` 或 `REJECTED` snapshot。使用者查詢不會因 dry run 產生新資料；dry run 也不呼叫 Google、新北市官方 API 或 TDX。

## 路邊區域標籤規則

路邊標籤的線上語意是：目的地直線 300 公尺內，抵達時至少一格一般汽車路邊格位可停。

第一版 dry run 對每個已發布路邊 snapshot 產生一個區域候選樣本：

1. 依目的地圓心、半徑、`serviceArea`、`ON_STREET_SPOT`、`ACTIVE`、`CONFIRMED` 篩出範圍內格位。
2. 若 catalog coverage 不是 `COMPLETE`，樣本拒絕，原因 `CATALOG_COVERAGE_PARTIAL`。
3. 若範圍內沒有觀測，拒絕，原因 `NO_OBSERVATIONS_IN_RADIUS`。
4. 只把 `qualityStatus=PASS` 或 `PARTIAL_PASS`、未超過 `maxObservationAge`、且狀態為 `AVAILABLE` 或 `OCCUPIED` 的觀測列入有效比例。
5. 有效觀測比例低於 `minimumValidObservationRatio` 時拒絕，原因 `INSUFFICIENT_VALID_OBSERVATION_RATIO`。
6. 通過後，若任一有效觀測為 `availableSpot=true` 或 `availabilityStatus=AVAILABLE`，該區域標籤為正樣本；否則為負樣本。

目前新莊路邊 catalog／mapping 仍為 `PARTIAL`，因此正式訓練前不能把目前小樣本結果當成完整 300 公尺區域標籤。

## 路外單場標籤規則

路外標籤的線上語意是：對 300 公尺內每個資料條件合格的路外停車場，估計抵達時仍有汽車位的機率。

第一版 dry run 對每筆已發布路外觀測產生一個單場候選樣本：

1. 停車位置必須是 `OFF_STREET_LOT`。
2. 停車位置必須 `ACTIVE` 且汽車資格 `CONFIRMED`。
3. 觀測不可超過 `maxObservationAge`。
4. 觀測品質必須是 `PASS` 或 `PARTIAL_PASS`。
5. 可用狀態必須可判定：`availabilityStatus=AVAILABLE`／`FULL`，或 `availableCarSpaces >= 0`。
6. 通過後，若 `availableCarSpaces > 0`、`availableSpot=true` 或 `availabilityStatus=AVAILABLE`，該單場標籤為正樣本；若 `availableCarSpaces=0` 或 `availabilityStatus=FULL`，可作負樣本。

`UNKNOWN`、`NO_REALTIME_AVAILABILITY`、負值特殊碼或品質失敗不得當成 0。

## 報告欄位

`LabelDryRunReport` 會回傳：

| 欄位 | 說明 |
| --- | --- |
| `target` | `ROADSIDE_AREA` 或 `OFF_STREET_LOT` |
| `inputObservations` | 時間窗內讀到的已發布觀測列數 |
| `uniqueParkingPlaces` | 涉及的停車位置數 |
| `candidateSamples` | dry run 建立的候選樣本數 |
| `usableSamples` | 可作標籤的樣本數 |
| `rejectedSamples` | 被拒絕樣本數 |
| `usableRatio` | `usableSamples / candidateSamples` |
| `positiveLabels` | 可用樣本中的正樣本數 |
| `firstSampleAt` / `lastSampleAt` | 樣本時間覆蓋 |
| `rejectionReasons` | 各拒絕原因計數 |
| `candidates` | 每個候選樣本的時間、停車位置、有效比例、標籤與拒絕原因 |

## 拒絕原因

| 原因 | 適用 | 說明 |
| --- | --- | --- |
| `CATALOG_COVERAGE_PARTIAL` | 路邊 | 覆蓋不足，不可產生完整 300 公尺區域標籤 |
| `NO_OBSERVATIONS_IN_RADIUS` | 路邊 | 目的地 300 公尺內沒有可檢查觀測 |
| `INSUFFICIENT_VALID_OBSERVATION_RATIO` | 路邊 | 有觀測但有效比例低於門檻 |
| `PLACE_TYPE_MISMATCH` | 路外 | 觀測對應位置不是路外停車場 |
| `NOT_CAR_ELIGIBLE` | 路外 | 位置不是 active 或汽車資格未確認 |
| `OBSERVATION_STALE` | 路外 | 觀測超過 dry run 設定的最大資料年齡 |
| `QUALITY_NOT_USABLE` | 路外 | 觀測品質不是 `PASS` 或 `PARTIAL_PASS` |
| `UNKNOWN_AVAILABILITY` | 路外 | 可用數或可用狀態不可判定 |

## 第一版測試報告

本 PR 的自動測試使用合成 entity，不使用官方原始資料或大型匯出檔：

- 路邊 partial coverage 時，即使 300 公尺內有 `AVAILABLE` 格位，也會拒絕並記 `CATALOG_COVERAGE_PARTIAL`。
- 路邊 coverage complete 且有效觀測比例達門檻時，能產生正樣本。
- 路外可把可用單場列為正樣本，並分開記錄 `UNKNOWN_AVAILABILITY` 與 `QUALITY_NOT_USABLE`。
- Repository 呼叫明確使用 `SnapshotStatus.PUBLISHED`，且查詢條件為 `systemFetchedAt >= from`、`systemFetchedAt < to`。

目前沒有把 dry run 報告持久化，也沒有建立匯出檔或公開 API。若後續要做實際 MySQL 報告，可用相同 service 加上受控管理命令，輸出摘要表或小型 CSV；大型匯出檔與原始 API 回應仍不得提交 Git。
