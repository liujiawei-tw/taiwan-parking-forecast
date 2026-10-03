# S3-008 baseline 與第一版模型訓練實驗報告

更新日期：2026-10-03。狀態：第一版離線 baseline／簡單模型回測，已包含 roadside 離線 re-label 後 300 公尺候選圓 baseline；尚未發布模型、尚未接入線上 API、尚未輸出正式機率。

## 目的

#64 的目標是用 #63 產生的無洩漏 label pairing 規則，先檢查 collector 訓練資料是否具備基本可預測性。這份工作只輸出 aggregate 回測報告，不提交 row-level label、不輸出模型檔、不改主系統查詢結果。

## 實驗設定

- 來源：`data/private/collector-samples/` 中 2026-09-24 至 2026-10-02 共 9 天 collector daily ZIP。
- 私有完整報告：`data/private/collector-samples/baseline-experiment-2026-09-24_2026-10-02.md`，不提交 Git。
- Split：train=`2026-09-24..2026-09-30`，test=`2026-10-01..2026-10-02`。
- Lead time：30／60 分鐘。
- 評估指標：Brier score、log loss、accuracy、precision、recall、coverage。
- Baseline／模型候選：`train_positive_rate`、`entity_positive_rate`、`current_state_persistence`、`logistic_regression_v1`。

## Dataset Status

| Dataset | Lead | Status | Train labels | Test labels | Reason |
| --- | ---: | --- | ---: | ---: | --- |
| offstreet | 30 | `EXPERIMENT_ONLY` | 8,516 | 3,189 | baseline_experiment_only |
| offstreet | 60 | `EXPERIMENT_ONLY` | 8,638 | 3,148 | baseline_experiment_only |
| roadside | 30 | `EXPERIMENT_ONLY` | 10,750 | 3,900 | baseline_experiment_only |
| roadside | 60 | `EXPERIMENT_ONLY` | 10,900 | 3,850 | baseline_experiment_only |

## Offstreet Metrics

| Lead | Experiment | Train positive | Test positive | Brier | Log loss | Accuracy | Precision | Recall |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 30 | `train_positive_rate` | 0.9221 | 0.9276 | 0.0672 | 0.2601 | 0.9276 | 0.9276 | 1.0000 |
| 30 | `entity_positive_rate` | 0.9221 | 0.9276 | 0.0213 | 0.1039 | 0.9683 | 0.9834 | 0.9824 |
| 30 | `current_state_persistence` | 0.9221 | 0.9276 | 0.0152 | 0.0928 | 0.9859 | 0.9926 | 0.9922 |
| 30 | `logistic_regression_v1` | 0.9221 | 0.9276 | 0.0220 | 0.1018 | 0.9859 | 0.9926 | 0.9922 |
| 60 | `train_positive_rate` | 0.9224 | 0.9263 | 0.0683 | 0.2632 | 0.9263 | 0.9263 | 1.0000 |
| 60 | `entity_positive_rate` | 0.9224 | 0.9263 | 0.0218 | 0.0813 | 0.9679 | 0.9825 | 0.9829 |
| 60 | `current_state_persistence` | 0.9224 | 0.9263 | 0.0182 | 0.1028 | 0.9825 | 0.9897 | 0.9914 |
| 60 | `logistic_regression_v1` | 0.9224 | 0.9263 | 0.0247 | 0.1108 | 0.9825 | 0.9897 | 0.9914 |

## Roadside Metrics

Roadside baseline 使用 deterministic 300 公尺候選目的地圓，feature side 只看 `asOf` 當下 circle 內 confirmed available 比例與是否至少一格 confirmed available，label side 只看 `arrivalAt` 附近是否至少一格 confirmed available。這仍是離線實驗，不是可上線模型。

| Lead | Experiment | Train positive | Test positive | Brier | Log loss | Accuracy | Precision | Recall |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 30 | `train_positive_rate` | 0.0800 | 0.0800 | 0.0736 | 0.2788 | 0.9200 | 0.0000 | 0.0000 |
| 30 | `entity_positive_rate` | 0.0800 | 0.0800 | 0.0000 | 0.0000 | 1.0000 | 1.0000 | 1.0000 |
| 30 | `current_state_persistence` | 0.0800 | 0.0800 | 0.0025 | 0.0513 | 1.0000 | 1.0000 | 1.0000 |
| 30 | `logistic_regression_v1` | 0.0800 | 0.0800 | 0.0119 | 0.0706 | 1.0000 | 1.0000 | 1.0000 |
| 60 | `train_positive_rate` | 0.0800 | 0.0800 | 0.0736 | 0.2788 | 0.9200 | 0.0000 | 0.0000 |
| 60 | `entity_positive_rate` | 0.0800 | 0.0800 | 0.0000 | 0.0000 | 1.0000 | 1.0000 | 1.0000 |
| 60 | `current_state_persistence` | 0.0800 | 0.0800 | 0.0025 | 0.0513 | 1.0000 | 1.0000 | 1.0000 |
| 60 | `logistic_regression_v1` | 0.0800 | 0.0800 | 0.0119 | 0.0706 | 1.0000 | 1.0000 | 1.0000 |

## Roadside Rejection Summary

| Lead | Reason | Count |
| ---: | --- | ---: |
| 30 | `no_arrival_observation` | 2,250 |
| 30 | `as_of_missing_source_response` | 250 |
| 60 | `no_arrival_observation` | 2,200 |
| 60 | `as_of_missing_source_response` | 200 |

## 判定

- 路外資料可支撐第一版 baseline 與簡單 logistic regression 的離線時間切分回測；目前最好的候選是 `current_state_persistence`，但這仍是實驗證據，不是服務承諾。
- 路邊 300 公尺語意沒有被固定路段機率取代；離線 re-label 後已可跑 baseline。結果非常好看，但目前只有 9 天、50 個 deterministic circles，且 positive rate 約 8%，必須補更多日期、區域泛化與 calibration／門檻審查。
- 現在不產生版本化模型檔，也不接入線上 `predictions`。若要進 #65，必須先補模型版本、schema、門檻、產物保存與回滾驗證。
