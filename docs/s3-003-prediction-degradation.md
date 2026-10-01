# S3-003 預測推論降級與模型版本接點

更新日期：2026-10-01。狀態：第一版後端查詢契約與文件基線；尚未訓練或部署正式模型，不輸出未驗證機率。

## 目標

S3-003 的目標是在模型尚未可用時，讓線上 API 仍回傳穩定、可解釋的 `predictions` 區塊。這個區塊要清楚說明「為什麼現在不能預測」，同時不影響目前車位、Google ETA 與刷新狀態的顯示。

目前後端採保守狀態：

- `predictions.status=INSUFFICIENT_DATA`
- `modelVersion=null`
- `probability=null`
- `generatedAt` 記錄本次回應產生時間
- `targetArrivalAt` 只沿用 `eta.estimatedArrivalAt`，不另造固定時距
- `inputSnapshotVersion` 記錄已發布快照版本，例如 `NTPC_ROADSIDE_SPOT:123`
- `dataAgeSeconds` 記錄快照系統取得時間到回應產生時間的秒數
- `reasons` 說明模型、ETA、快照、覆蓋或來源品質的不足原因

## 原因碼

| 原因碼 | 使用情境 |
| --- | --- |
| `MODEL_NOT_LOADED` | 線上沒有載入可用模型產物 |
| `BACKTEST_NOT_VERIFIED` | 候選模型或 baseline 尚未通過時間切分回測門檻 |
| `ETA_NOT_READY` | Google ETA 失敗、被阻擋或沒有目標抵達時間 |
| `NO_CATALOG_RESULTS` | 300 公尺範圍內沒有可用目錄結果 |
| `CATALOG_COVERAGE_PARTIAL` | catalog 或 mapping 覆蓋不足，不能輸出完整路邊 300 公尺區域機率 |
| `NO_REALTIME_SNAPSHOT` | 該來源尚無已發布即時快照 |
| `REALTIME_SNAPSHOT_EXPIRED` | 該來源快照已超過新鮮度門檻 |
| `UNKNOWN_AVAILABILITY` | 來源有觀測但無法判定目前可用狀態 |
| `NO_OBSERVATION` | 已發布快照中缺少該停車位置觀測 |
| `MODEL_INPUT_NOT_READY` | 來源狀態未能映射成可推論輸入 |

## 降級與回滾

線上查詢只能讀取已離線產生、已版本化且通過回測門檻的模型產物；不能在使用者查詢時即時訓練。模型載入、版本切換與回滾策略如下：

1. 新模型必須有版本 ID、輸入 schema、訓練資料範圍、回測報告與接受門檻。
2. 查詢服務啟動或切換版本時，若模型檔缺失、schema 不相容、回測未驗證或推論程序失敗，回到 `modelVersion=null`。
3. 回滾到無模型狀態時，只影響 `predictions`；`eta`、`roadside`、`offStreet` 與 `refresh` 仍按各自來源狀態回覆。
4. 已發布的 `PUBLISHED` snapshot 不因模型拒絕或推論失敗而被覆蓋。
5. 未通過回測的 baseline 或實驗模型不得輸出機率；只能透過文件或內部報告呈現。

## 已知限制

- 目前仍沒有正式模型產物與線上推論程序。
- 目前 `CATALOG_COVERAGE_PARTIAL` 會保守阻擋路邊區域機率，因新莊完整 catalog／mapping 尚未正式匯入與驗證。
- `dataAgeSeconds` 只代表主系統成功取得並發布快照後到本次回應的年齡，不代表官方感測器或來源真正觀測時間。
- 實際模型接受門檻、適用預測時距、模型版本儲存位置與自動回滾機制仍待後續 Issue。
