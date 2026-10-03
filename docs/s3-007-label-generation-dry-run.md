# S3-007 路邊／路外訓練標籤產生器與無洩漏時間配對

更新日期：2026-10-03。狀態：第一版 label generation dry-run；尚未訓練模型、尚未輸出線上機率。

## 目的

#63 的目標是把 collector 原始觀測轉成可審查的訓練 label dry-run 摘要，同時證明特徵與標籤沒有時間洩漏。這份工作只產生 aggregate report，不提交 row-level label、不產生模型檔、不接線上 API。

## Label Definition

| 類型 | 定義 | 正例 | 拒絕原因 |
| --- | --- | --- | --- |
| roadside | 候選目的地圓心 300 公尺內的格位集合，對 `asOf + leadTime` 附近時間窗配對 | 抵達時間窗內至少一格 `is_available=true` | provisional roadside label、沒有抵達觀測、`asOf` lineage 缺 `source_api_response_at_utc`、抵達 label unknown |
| offstreet | 同一 `lot_id`，對 `asOf + leadTime` 附近時間窗配對 | 抵達時間窗內 `available_car > 0` | unknown／負值、沒有抵達觀測、`asOf` lineage 缺 `source_api_response_at_utc` |

目前 dry-run 設定：

- `leadMinutes`: 30、60
- `labelToleranceMinutes`: 15
- roadside radius: 300m
- roadside candidate centers: 50 個 deterministic sampled spot centers
- `allowProvisionalRoadsideLabels=false`
- split: train=`2026-09-24..2026-09-30`，test=`2026-10-01..2026-10-02`

## 無洩漏時間配對

- `asOf` 是模型做出預測的時間，特徵只能讀取 `asOf` 當下及以前的觀測。
- `arrivalAt = asOf + leadTime`，`arrivalAt ± labelTolerance` 的資料只能用作 label。
- dry-run 程式若找不到 `arrivalAt` 附近觀測，樣本直接拒絕，不用過期或缺值冒充真實 label。
- split 採日期順序切分，不使用 row-random split，避免同日或同輪收集資料同時出現在 train/test。

## 真實 dry-run 摘要

來源：`data/private/collector-samples/` 中 2026-09-24 至 2026-10-02 共 9 天 collector daily ZIP。私有完整報告：`data/private/collector-samples/label-dry-run-2026-09-24_2026-10-02.md`，不提交 Git。

### Source Rows

| Dataset | Rows | Skipped rows |
| --- | ---: | ---: |
| roadside | 728,532 | 0 |
| offstreet | 50,516 | 0 |

### Roadside 300m Candidate Coverage

| Candidate centers | Min spots | Avg spots | Max spots |
| ---: | ---: | ---: | ---: |
| 50 | 10 | 54.7 | 97 |

### Dry-run Summary

| Dataset | Split | Lead | Candidate samples | Usable labels | Rejected | Positive | Negative | Entity coverage |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| roadside | train | 30 | 12,800 | 0 | 12,800 | 0 | 0 | 50 |
| roadside | train | 60 | 12,800 | 0 | 12,800 | 0 | 0 | 50 |
| roadside | test | 30 | 4,350 | 0 | 4,350 | 0 | 0 | 50 |
| roadside | test | 60 | 4,350 | 0 | 4,350 | 0 | 0 | 50 |
| offstreet | train | 30 | 37,640 | 8,516 | 29,124 | 7,853 | 663 | 149 |
| offstreet | train | 60 | 37,640 | 8,638 | 29,002 | 7,968 | 670 | 149 |
| offstreet | test | 30 | 12,876 | 3,189 | 9,687 | 2,958 | 231 | 148 |
| offstreet | test | 60 | 12,876 | 3,148 | 9,728 | 2,916 | 232 | 148 |

### 主要拒絕原因

| Dataset | Split | Lead | Reason | Count |
| --- | --- | ---: | --- | ---: |
| roadside | train | 30 | `provisional_roadside_label` | 10,750 |
| roadside | train | 30 | `no_arrival_observation` | 1,800 |
| roadside | train | 30 | `as_of_missing_source_response` | 250 |
| roadside | train | 60 | `provisional_roadside_label` | 10,900 |
| roadside | train | 60 | `no_arrival_observation` | 1,700 |
| roadside | train | 60 | `as_of_missing_source_response` | 200 |
| roadside | test | 30 | `provisional_roadside_label` | 3,900 |
| roadside | test | 30 | `no_arrival_observation` | 450 |
| roadside | test | 60 | `provisional_roadside_label` | 3,850 |
| roadside | test | 60 | `no_arrival_observation` | 500 |
| offstreet | train | 30 | `arrival_unknown_or_negative` | 22,722 |
| offstreet | train | 30 | `no_arrival_observation` | 5,657 |
| offstreet | train | 30 | `as_of_missing_source_response` | 745 |
| offstreet | train | 60 | `arrival_unknown_or_negative` | 23,044 |
| offstreet | train | 60 | `no_arrival_observation` | 5,362 |
| offstreet | train | 60 | `as_of_missing_source_response` | 596 |
| offstreet | test | 30 | `arrival_unknown_or_negative` | 8,355 |
| offstreet | test | 30 | `no_arrival_observation` | 1,332 |
| offstreet | test | 60 | `arrival_unknown_or_negative` | 8,248 |
| offstreet | test | 60 | `no_arrival_observation` | 1,480 |

## 判定

- 路外可產生可靠 label 候選：test split 在 30 分鐘 lead 有 3,189 usable labels，60 分鐘 lead 有 3,148 usable labels。
- 路邊 300 公尺語意已用候選目的地圓建立，不以固定路段機率冒充任意目的地圓形機率；但所有可靠 label 仍因 provisional status rule 被拒絕。
- 目前仍不得輸出線上機率，也不得開始 #64 模型訓練；下一步是人工確認 roadside raw status 語意，或先以 offstreet 做 baseline 實驗候選。
