# 模型訓練架構

更新日期：2026-10-25。狀態：已有 baseline、calibrated persistence 對照與三段時間切分的 Logistic Regression 離線候選；正式模型尚未發布。

## DEMO 優先停點

S3-021 已完成舊實驗重現與限制交接，見 s3-021-model-experiment-handoff.md。2026-10-25 另依使用者指示，在 31 日 collector 樣本上完成一輪 chronological 80/10/10 日期切分、Logistic Regression 訓練與留出測試；結果見 `current-state.md` 及私有報告。該實驗修正了 split 跨界標籤與 train-only roadside circle 建立，但仍是固定 30／60 分鐘 lead、單一區域資料。來源逐筆觀測語意、ETA-aware 樣本、coverage 與 S3-020 gate 尚未完成；線上維持空機率與資料不足。DEMO 發布驗收 S3-025 後，正式資料工作仍依 S3-018 → S3-019 → S3-020 → S3-026 → S3-009 → S3-027 推進。

## 目的

`ml-training/` 是 `taiwan-parking-forecast` 內專門放離線模型訓練與回測程式的工作區。它和主系統 `backend/`、前端 `frontend/` 分開，避免訓練程式誤接線上 API 或使用者查詢流程。

## 邊界

- `parking-data-collector` 仍是獨立資料收集器，只產生離線訓練資料。
- `ml-training/` 可讀取已下載到本機 ignored 目錄的 collector daily ZIP／CSV 或日後主系統匯出的合格歷史觀測。
- `ml-training/` 不呼叫新北市、TDX、Google Routes 或主系統刷新 API。
- `ml-training/` 不服務使用者查詢，不寫入 `PUBLISHED` snapshot。
- 線上查詢只可讀取已版本化、已通過回測門檻且可回滾的模型產物；現在仍沒有可用模型。

## 目錄

| 路徑 | 職責 |
| --- | --- |
| `ml-training/README.md` | 訓練 workspace 使用方式與限制 |
| `ml-training/config/training-config.example.json` | 第一版訓練資料來源、label policy、日期切分與輸出位置設定範例 |
| `ml-training/scripts/plan_training_run.py` | 只列印訓練計畫，不訓練、不發布 |
| `ml-training/scripts/generate_label_dry_run.py` | 產生 aggregate label dry-run 報告，不輸出 row-level labels |
| `ml-training/scripts/run_baseline_experiment.py` | 產生 baseline 與第一版簡單模型 aggregate 回測報告，不輸出模型檔 |
| `ml-training/src/parking_ml/` | 後續 label generation、feature、baseline、ML experiment 共用程式 |
| `ml-training/tests/` | 輕量單元測試，先守住日期切分與安全設定 |

以下路徑只作本機工作區，已加入 `.gitignore`：

- `ml-training/data/`
- `ml-training/outputs/`
- `ml-training/models/`
- `ml-training/reports/private/`

## 目前 baseline 定位

目前 30／60 分鐘 lead baseline 是資料與流程壓力測試，不是最終產品模型定義。它用固定 `arrivalAt = asOf + leadTime` 先確認 collector 資料能否產生未來 label、時間切分是否無洩漏、目前狀態 persistence 是否足以成為比較尺，並避免一開始就把 Google Routes 額度、快取與失敗率混進資料品質盤點。

正式產品問題是「使用者從出發地到目的地時，目的地 500 公尺內或指定停車場仍有車位的機率」。因此後續訓練樣本應改為 ETA-aware：`arrivalAt = departureAt + etaDuration`，其中 ETA 由主系統的 Google Routes 或經快取／抽樣的離線路線資料提供。固定 30／60 分鐘可保留作比較桶與壓力測試，但不能宣稱等同真實行程預測。

## ETA-aware 模型方向

第一版正式候選應把模型目標拆成兩類：

| 類型 | 預測目標 | 粒度 |
| --- | --- | --- |
| Roadside | 抵達目的地時，500 公尺內至少一格一般汽車路邊格位 confirmed available | 目的地圓形區域 |
| Offstreet | 抵達目的地時，單一 verified 停車場仍有汽車位 | 單一停車場 |

核心特徵方向：

- 車程時間：`eta_minutes`、lead time bucket、ETA 越長目前狀態權重越低。
- 時間週期：出發與抵達的 hour、weekday、weekend/holiday、尖峰／離峰 bucket。
- 目前狀態：目前可用數、可用比例、near-full flag、資料年齡、觀測品質。
- 變化趨勢：過去 5／15／30 分鐘可用數變化、下降速度、波動程度。
- 地點脈絡：目的地圓心或停車場的歷史同時段可用率、區域類型；未驗證前不直接使用 raw 地址文字。
- 外部事件與環境：天氣、活動、商圈促銷等可列為中後期特徵；未建立穩定來源前先不作上線必要條件。

## 演算法路線

短期資料仍少，先以可解釋模型和強 baseline 為主：

1. **ETA-aware persistence baseline**：目前狀態依 ETA bucket 遞減權重，作為正式模型必須超越的比較尺。
2. **歷史同時段 baseline**：同一停車場或目的地圓心在 weekday/hour/holiday 的歷史有位率。
3. **Logistic regression**：第一版可解釋 ML 候選，用於檢查 ETA、時間週期、near-full 等特徵方向是否有效。
4. **Gradient boosting**：資料天數與區域增加後，作為第一個較有戰力的非線性模型候選，處理尖峰、長 ETA、near-full 風險與互動特徵。

資料更齊全後，再評估：

- **Survival／hazard model**：適合處理「目前有位能撐到 X 分鐘後」或「剩餘車位退縮曲線」。
- **Time-series features + boosting**：先用 rolling features，不急著導入 LSTM／Transformer。
- **Sequence model**：只有在資料量、跨區域覆蓋、特徵品質與可解釋性門檻都成熟後才評估。

## 保守評估與不對稱懲罰

使用者體驗上，假陽性比假陰性嚴重：模型說「有位」但使用者抵達後客滿，傷害最大。因此模型評估不能只看 accuracy，必須納入：

- Precision：預測有位時實際有位的比例。
- False positive rate：預測有位但實際無位或不確認有位。
- Brier score 與 calibration：70% 機率是否真的接近 70%。
- near-full subset precision：目前剩餘少於門檻（例如 3 格）時的 precision。
- Coverage／abstention rate：模型願意輸出機率的比例，以及拒絕原因。

線上產品可採模型機率加決策門檻：一般情境可能要求 `p >= 0.65` 才顯示偏正向文案；near-full、資料較舊或 ETA 較長時提高門檻，例如 `p >= 0.85`。這些數值目前只是待回測的門檻候選，不是服務承諾。

## 第一版資料切分

目前 S3-006 的 collector 樣本使用 2026-09-24 至 2026-10-02，共 9 天；`2026-09-23` 因資料不齊先排除。第一版採時間順序切分，不做 row-random split：

| Split | 日期範圍 | 用途 |
| --- | --- | --- |
| train | 2026-09-24 至 2026-09-30 | label rule、feature、baseline fit |
| test | 2026-10-01 至 2026-10-02 | holdout 評估，不回流訓練 |

此切分約 75/25。等資料天數更多後，再改成 chronological 80/20 或 rolling-window evaluation。

## 首輪三段時間切分（2026-10-25）

對本機 2026-09-24 至 2026-10-24 的 31 天 collector 樣本，使用 train 09-24 至 10-18（25 天）、validation 10-19 至 10-21（3 天）、test 10-22 至 10-24（3 天）。日期比例為 80.6/9.7/9.7；樣本比例會因 unknown 與 label 配對拒絕而變動。每個樣本的 arrival label 必須和 as-of feature 落在相同 split；roadside 500m candidate circles 只用 train 結束前可知座標建立。週期特徵以 Asia/Taipei 時間計算。

本輪每個 dataset／lead 在 train 擬合 Logistic Regression，validation 依 Brier 選擇比較方法，test 只作最後評估。路外兩個 lead 與路邊 30m 在 validation 選 current-state persistence；路邊 60m 選 Logistic Regression，test Brier 為 0.1094，相較 persistence 0.1169。路外 Logistic Regression 未超越 persistence。因 test 僅三天、資料單一區域且尚未完成 ETA-aware／S3-020 gate，全部產物仍是 `EXPERIMENT_ONLY`，不得接入線上。

## 評估可信度補強與 Calibrated Persistence

新增的固定日期實驗在同一 dataset／lead／coverage cohort 內比較 train 正例率、原固定 0.95／0.05 persistence、`calibrated_state_persistence_v1` 與 Logistic Regression。校準率只由 train split 估計；alpha 預設 1.0，每個 True／False／Unknown 狀態至少 20 筆才使用條件率，否則使用平滑後 train 全域正例率。Validation Brier 用來選比較方法；預設 train/validation/test 最低樣本數為 500/100/100，且每 split 要有正、負例。這些門檻只阻止資料量不足時產生看似有效的結論，不是模型通過或統計充分性的證明。

Roadside legacy 規則 `roadside_partial_observation_v0993b17` 留作舊結果重現；新規則 `roadside_complete_circle_v1` 只以 train split 建立凍結圈，as-of、arrival 與共同格位都必須完整觀測。報告分政策呈現候選、接受、拒絕、正負例及原因，coverage 是離線樣本准入比例，不是使用者查詢的預測覆蓋率。缺少觀測不會轉成 occupied label。

Calibration 分 10 個等寬機率桶，小於 20 筆標示 `LOW_SAMPLE`；near-full 維持 `current_ratio <= 0.10` 的實驗定義；狀態轉換只作事後診斷。新 Logistic artifact 使用 `parking-logistic-experiment-v2` 與 `parking_features_as_of_v2`，週期時間特徵名稱及計算都指向 Taipei as-of hour。全部新舊產物仍為 `EXPERIMENT_ONLY`，不得接入線上 predictions。

## 後續 Issue 對應

- S3-006：資料品質盤點與匯入規格，提供可用日期、schema 差異與拒絕原因。
- S3-007：在 `ml-training/src/parking_ml/` 補 label generation dry run，輸出 label usable ratio 與 rejection report。
- S3-008：加入 baseline 與第一版 ML experiment，至少比較可解釋 baseline 與簡單模型。
- S3-010：先驗證新北市 roadside `parkingstatus`／`cellstatus` 語意與 relabel 計畫，再重跑 roadside label dry-run。
- S3-011：建立 ML canonical schema 與 source-specific mapping contract，避免不同地方政府 raw status code 直接混入模型。
- S3-009：只有通過門檻後，才建立版本化模型產物、推論輸入 schema 與回滾驗證。

## 驗證

目前可跑：

```powershell
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config.example.json
python ml-training/scripts/generate_label_dry_run.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/label-dry-run-2026-09-24_2026-10-02.md
python ml-training/scripts/run_baseline_experiment.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/baseline-experiment-2026-09-24_2026-10-02.md
python -m unittest discover -s ml-training/tests
```

這些命令只驗證訓練架構、切分規格、label dry-run 與離線 baseline／簡單模型回測，不代表模型已可發布或已接入線上查詢。
