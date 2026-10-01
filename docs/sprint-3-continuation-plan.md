# Sprint 3 後續規劃與模型訓練路線

更新日期：2026-10-01。狀態：規劃文件；本文件只記錄 GitHub Issue 順序與模型訓練方法，不代表已開始實作、訓練模型或輸出線上機率。

## GitHub Issue 順序

| 順序 | Issue | 目的 |
| --- | --- | --- |
| 1 | [#60 S3-004 完整新莊 catalog／mapping 正式匯入管理命令](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/60) | 把 #41 的 dry-run 策略轉為可審查、可回滾的正式 DB 匯入流程 |
| 2 | [#61 S3-005 官方來源端到端刷新寫入與發布量測](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/61) | 量測官方 API 到 PUBLISHED snapshot 的完整耗時、品質拒絕與發布保護 |
| 3 | [#62 S3-006 collector 訓練資料品質盤點與匯入規格](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/62) | 先盤點 `parking-data-collector` 的 daily ZIP／CSV 是否足以產生訓練標籤 |
| 4 | [#63 S3-007 路邊／路外訓練標籤產生器與無洩漏時間配對](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/63) | 建立與線上定義一致的路邊 300 公尺區域標籤與路外單場標籤 |
| 5 | [#64 S3-008 baseline 與第一版模型訓練實驗報告](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/64) | 用時間切分比較 baseline 與簡單模型，判斷是否值得接入 |
| 6 | [#65 S3-009 模型產物版本化、離線推論與回滾驗證](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/65) | 將可接受模型轉成可版本化、可拒絕、可回滾的離線推論產物 |

## 模型訓練路線

第一階段先使用 `parking-data-collector` 的 Google Drive daily ZIP／CSV 做離線資料品質盤點。collector 是訓練資料來源，不是主系統即時查詢來源；其 `collected_at_utc` 是收集時間，不是官方逐筆觀測時間。模型資料匯入必須保留 raw status、label rule、source response time 與 unknown／negative 狀態，不能把未知補成 0。

第二階段才建立標籤。路邊標籤須用與線上相同的目的地圓心、直線 300 公尺、一般汽車資格、有效觀測比例與抵達時間窗；路外標籤以 verified lot mapping 判斷抵達時是否仍有汽車位。特徵只能使用 `asOf` 當下及以前資料，`arrivalAt` 附近資料只能作標籤。

第三階段先做 baseline，再做簡單模型。baseline 至少包含目前狀態延續、同 entity 同 weekday/hour 歷史有位率與 rolling average。第一版 ML 候選可從 logistic regression 或 gradient boosting 開始，並用 Brier score、log loss、precision、recall、coverage、拒絕率與 calibration 檢查。若 baseline 或模型未達門檻，線上仍維持 `INSUFFICIENT_DATA`。

第四階段才處理模型產物。任何模型接線上前都必須有版本 ID、schema version、訓練資料範圍、回測摘要、拒絕原因與回滾策略。模型不可用時只降級 `predictions`，不得覆蓋目前車位、ETA 或既有 PUBLISHED snapshot。

## 不做事項

- 不直接用 collector CSV 回答使用者目前車位。
- 不在使用者查詢時即時訓練模型。
- 不把缺值、unknown、負值或過期資料當成真實 0。
- 不把未通過回測的 baseline 或模型分數包裝成正式機率。
- 不提交 collector CSV／ZIP、大型模型檔、金鑰或敏感設定。
