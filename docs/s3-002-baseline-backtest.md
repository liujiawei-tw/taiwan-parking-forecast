# S3-002 基準模型與時間切分回測報告

更新日期：2026-10-01。狀態：第一版後端 baseline backtest service 與審查規則。本文只定義離線基準回測摘要，不接線上推論、不輸出正式預測機率、不提交大型匯出檔或原始 API 回應。

## 目標

S3-002 的目的不是證明模型已可上線，而是先回答：

- S3-001 產生的 usable labels 是否足以做時間切分回測。
- 最簡單、可解釋的 baseline 在 validation／test split 上表現如何。
- 是否仍應維持 `INSUFFICIENT_DATA`，避免把目前車位或小樣本結果包裝成可信預測。

## 輸入資料

第一版後端新增 `BaselineBacktestService`，直接接收 `TrainingLabelDryRunService.LabelDryRunReport`：

- 只使用 `usable=true`、`sampleTime` 不為空、`positiveLabel` 不為空的候選樣本。
- `rejectedSamples` 只進入限制說明，不納入指標計算。
- 樣本依 `sampleTime` 排序；同一時間下以 `parkingPlaceId` 排序。

這代表 baseline backtest 不讀 `CANDIDATE`／`REJECTED` snapshot，不呼叫 Google、新北市官方 API 或 TDX，也不建立訓練模型產物。

## 時間切分

`BacktestCommand` 要在回測前固定：

| 欄位 | 第一版測試候選值 | 說明 |
| --- | --- | --- |
| `trainingRatio` | `0.5` | 最早 50% usable samples 作訓練窗 |
| `validationRatio` | `0.25` | 接續 25% 作 validation |
| test ratio | `0.25` | 剩餘最新樣本作 test |
| `minimumTotalSamples` | 測試中以 8 驗證 | 實務門檻仍待真實報告後決定 |
| `minimumTrainingSamples` | 測試中以 4 驗證 | 訓練窗不足時不產生 baseline |
| `minimumEvaluationSamples` | 測試中以 2 驗證 | validation 或 test 不足時不產生 baseline |
| `maximumAcceptedBrierScore` | `0.30` | 先定義的審查門檻，不能看完結果再挑 |

真實 MySQL 報告若樣本數不足、test split 只有單一 label class，或最佳 baseline Brier score 高於預先設定門檻，報告必須建議維持 `INSUFFICIENT_DATA`。

## Baseline

第一版固定三個可解釋 baseline：

| 名稱 | 說明 |
| --- | --- |
| `TRAIN_POSITIVE_RATE` | 對 validation／test 的每筆樣本，都預測訓練窗正樣本比例 |
| `TRAIN_MAJORITY_CLASS` | 對 validation／test 的每筆樣本，都預測訓練窗多數類別 |
| `LATEST_TRAINING_LABEL` | 路外若有同一停車場的訓練窗最後標籤，使用該標籤；否則使用整體訓練窗最後標籤。路邊區域樣本沒有 `parkingPlaceId` 時使用整體訓練窗最後標籤 |

這三個 baseline 只使用訓練窗可得的標籤，不使用 validation／test 的未來答案作特徵。它們不是正式模型，只是用來檢查資料是否有基本可預測性。

## 指標

每個 baseline 對 validation 與 test 各產生一組 `BaselineEvaluation`：

| 指標 | 用途 |
| --- | --- |
| `positiveRate` | 檢查 split 正負樣本比例，避免單看 accuracy 誤導 |
| `brierScore` | 機率預測主要審查指標，越低越好 |
| `logLoss` | 懲罰過度自信錯誤，作輔助審查 |
| `accuracy` | 只作直覺參考，不單獨作接受依據 |
| `precision` / `recall` | 檢查 threshold 0.5 下的正樣本行為 |
| `truePositive` / `falsePositive` / `falseNegative` | 方便人工審查錯誤類型 |

第一版接受決策以 test split 的最佳 Brier score 與是否單一 label class 作保守判斷。即使回 `BASELINE_CANDIDATE`，也只代表離線候選可繼續研究，不代表可接線上服務。

## 資料不足輸出

以下情況報告狀態必須是 `INSUFFICIENT_DATA`：

- usable samples 少於 `minimumTotalSamples`。
- training／validation／test 任一 split 少於對應最低樣本數。
- test split 只有正樣本或只有負樣本。
- 最佳 test Brier score 高於 `maximumAcceptedBrierScore`。

資料不足時，線上 `predictions` 仍應維持資料不足或模型未就緒，不得把 baseline 分數當作正式機率。

## 第一版測試報告

本 PR 的自動測試使用合成 label candidates，不使用官方原始資料或大型匯出檔：

- 路邊樣本可產出 training／validation／test split 與三個 baseline 的 validation／test 指標。
- 路外樣本可驗證 `LATEST_TRAINING_LABEL` 會優先使用同一停車場的訓練窗最後標籤。
- usable labels 不足時不產生 baseline，報告建議維持 `INSUFFICIENT_DATA`。

目前沒有把 backtest 報告持久化，也沒有建立匯出檔或公開 API。後續若接實際 MySQL 報告，應先用相同 service 輸出小型審查摘要，再決定是否需要 notebook、CSV 或模型產物版本化。
