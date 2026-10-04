# Sprint 3 後續規劃與模型訓練路線

更新日期：2026-10-04。狀態：規劃文件；本文件只記錄 GitHub Issue 順序與模型訓練方法，不代表已開始實作、訓練模型或輸出線上機率。

## GitHub Issue 順序

| 順序 | Issue | 目的 |
| --- | --- | --- |
| 1 | [S3-016 資料來源覆蓋決策與跨來源狀態對照收斂](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/77) | 先整理 TDX／新北市 API 的覆蓋證據、ID 對照與狀態碼差異，決定 MVP 資料來源優先序 |
| 2 | [S3-017 完整新莊 catalog／mapping 匯入驗收與覆蓋邊界](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/78) | 用完整新北市官方資料做新莊路邊／路外 dry-run，確認 accepted、rejected、unknown 與覆蓋邊界 |
| 3 | [S3-018 PUBLISHED 觀測歷史匯出與訓練資料橋接規格](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/79) | 把主系統 MySQL 已發布快照整理成可訓練／回測的匯出契約，並區分 collector 離線資料與主系統歷史 |
| 4 | [S3-019 ETA-aware 訓練樣本產生器與 query-like 回測資料](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/80) | 產生接近真實查詢的訓練樣本：出發地、目的地、出發時間、ETA、抵達時間與 500 公尺／單場 label |
| 5 | [S3-020 保守模型評估 gate、假陽性風險與近滿場景門檻](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/81) | 建立 false positive、near-full、calibration、coverage、abstention 等模型准入門檻 |
| 6 | [S3-009 模型產物版本化、離線推論與回滾驗證](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/65) | 只有在 S3-019／S3-020 證明模型值得接入後，才將模型轉成可版本化、可拒絕、可回滾的離線推論產物 |

已完成或可收尾的前置任務：[S3-004](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/60)、[S3-005](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/61)、[S3-006](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/62)、[S3-007](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/63)、[S3-008](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/64)。這些提供了 catalog command、E2E refresh measurement、collector 品質盤點、label dry-run 與 baseline 實驗基礎；後續不應直接跳到 S3-009，否則會缺 ETA-aware 樣本與保守准入門檻。

## 2026-10-04 規劃更新說明

本輪已將下一批任務記錄到 GitHub Issue，對外文件統一以 S3-016 至 S3-020 標示。規劃重點是先把「資料來源可信度」與「模型是否值得接入」分開驗證：

- S3-016／S3-017 先解決來源與覆蓋：新莊 MVP 目前以新北市官方 API 為主，TDX 保留為全臺擴充待驗證來源。
- S3-018 連接主系統 PUBLISHED snapshot 與 ML workspace，避免只依賴 collector ZIP。
- S3-019 把訓練資料改成 query-like／ETA-aware，符合「使用者抵達目的地時」這個產品問題。
- S3-020 在模型接線上前先建立保守 gate，特別處理假陽性與快滿場景。
- S3-009 只處理通過 gate 後的模型版本化與回滾，不再承擔資料來源、樣本產生與評估門檻的工作。

2026-10-04 進度：S3-016 已新增 `s3-016-data-source-coverage-decision.md`，確認新莊 MVP 即時來源以新北市官方 Open Data 為主，TDX 暫作全臺擴充與交叉驗證。S3-017 已完成一輪真實官方 catalog／mapping dry-run，新增 `s3-017-catalog-mapping-dry-run.md`；結果為 scoped rows 2,224、accepted candidates 2,037、unknown eligibility 174、verified mapping coverage 0.45%，recommendation 仍是 `REVIEW_ELIGIBILITY_RULES`。下一步不是直接寫入完整 catalog，而是先審查 eligibility 規則，讓 dry-run 可進入 `READY_FOR_IMPORT_REVIEW`。

## 模型訓練路線

第一階段先使用 `parking-data-collector` 的 Google Drive daily ZIP／CSV 做離線資料品質盤點。collector 是訓練資料來源，不是主系統即時查詢來源；其 `collected_at_utc` 是收集時間，不是官方逐筆觀測時間。模型資料匯入必須保留 raw status、label rule、source response time 與 unknown／negative 狀態，不能把未知補成 0。

第二階段才建立標籤。路邊標籤須用與線上相同的目的地圓心、直線 500 公尺、一般汽車資格、有效觀測比例與抵達時間窗；路外標籤以 verified lot mapping 判斷抵達時是否仍有汽車位。特徵只能使用 `asOf` 當下及以前資料，`arrivalAt` 附近資料只能作標籤。現有 30／60 分鐘 lead 是低成本壓力測試；正式產品模型需改為 ETA-aware sample，讓 `arrivalAt = departureAt + etaDuration`。

第三階段先做 baseline，再做簡單模型。baseline 至少包含 ETA-aware 目前狀態延續、同 entity 同 weekday/hour 歷史有位率與 rolling average。第一版 ML 候選可從 logistic regression 開始，資料天數與區域增加後加入 gradient boosting；再往後才評估 survival／hazard model 來處理長車程下的車位退縮曲線。評估除 Brier score、log loss、precision、recall、coverage、拒絕率與 calibration 外，還要新增 false positive rate 與 near-full subset precision。若 baseline 或模型未達門檻，線上仍維持 `INSUFFICIENT_DATA`。

## ETA-aware 模型調整重點

- 車程時間是核心特徵：5 分鐘內目前狀態可能已足夠，40 分鐘以上則必須檢查目前狀態的衰減與歷史同時段模式。
- 時間週期需進入特徵：weekday/weekend、arrival hour、尖峰／離峰與 holiday。商圈、辦公區與景點的模式不可假設相同。
- 外部事件與環境列為中後期特徵：天氣、活動、百貨週年慶或大型場館事件可提升長 ETA 預測，但要先有穩定來源與回測證據。
- 懲罰要不對稱：假陽性比假陰性更傷害使用者；near-full 狀態下應提高顯示「有位」的門檻。
- 產品顯示不直接等於模型分數：模型可輸出機率，線上還要依 ETA、資料年齡、near-full、覆蓋狀態與 calibration 套用保守門檻或拒絕預測。

第四階段才處理模型產物。任何模型接線上前都必須有版本 ID、schema version、訓練資料範圍、回測摘要、拒絕原因與回滾策略。模型不可用時只降級 `predictions`，不得覆蓋目前車位、ETA 或既有 PUBLISHED snapshot。

## 不做事項

- 不直接用 collector CSV 回答使用者目前車位。
- 不在使用者查詢時即時訓練模型。
- 不把缺值、unknown、負值或過期資料當成真實 0。
- 不把未通過回測的 baseline 或模型分數包裝成正式機率。
- 不提交 collector CSV／ZIP、大型模型檔、金鑰或敏感設定。
