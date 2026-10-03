# 模型訓練架構

更新日期：2026-10-03。狀態：第一版離線訓練 workspace 骨架；尚未訓練、評估或發布任何模型。

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
| `ml-training/src/parking_ml/` | 後續 label generation、feature、baseline、ML experiment 共用程式 |
| `ml-training/tests/` | 輕量單元測試，先守住日期切分與安全設定 |

以下路徑只作本機工作區，已加入 `.gitignore`：

- `ml-training/data/`
- `ml-training/outputs/`
- `ml-training/models/`
- `ml-training/reports/private/`

## 第一版資料切分

目前 #62 的 collector 樣本使用 2026-09-24 至 2026-10-02，共 9 天；`2026-09-23` 因資料不齊先排除。第一版採時間順序切分，不做 row-random split：

| Split | 日期範圍 | 用途 |
| --- | --- | --- |
| train | 2026-09-24 至 2026-09-30 | label rule、feature、baseline fit |
| test | 2026-10-01 至 2026-10-02 | holdout 評估，不回流訓練 |

此切分約 75/25。等資料天數更多後，再改成 chronological 80/20 或 rolling-window evaluation。

## 後續 Issue 對應

- #62：資料品質盤點與匯入規格，提供可用日期、schema 差異與拒絕原因。
- #63：在 `ml-training/src/parking_ml/` 補 label generation dry run，輸出 label usable ratio 與 rejection report。
- #64：加入 baseline 與第一版 ML experiment，至少比較可解釋 baseline 與簡單模型。
- #65：只有通過門檻後，才建立版本化模型產物、推論輸入 schema 與回滾驗證。

## 驗證

目前可跑：

```powershell
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config.example.json
python ml-training/scripts/generate_label_dry_run.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/label-dry-run-2026-09-24_2026-10-02.md
python -m unittest discover -s ml-training/tests
```

這些命令只驗證訓練架構、切分規格與 label dry-run，不代表模型已開始訓練。
