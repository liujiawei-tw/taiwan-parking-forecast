# Sprint 3 下一步工作計畫

更新日期：2026-09-29。狀態：規劃文件；尚未開始實作、訓練模型或變更線上行為。

## 規劃原則

Sprint 3 的主題是「可驗證的抵達時預測」，但目前還不能直接開始輸出機率。下一批工作先補足人工驗收、完整目錄覆蓋、多輪來源量測、標籤 dry run、基準回測與推論降級契約。任何未通過資料品質或回測門檻的情境，仍以 `INSUFFICIENT_DATA` 或明確失敗原因呈現。

## 建議順序

| 順序 | Issue | 目的 | 為什麼先做 |
| --- | --- | --- | --- |
| 1 | [#40 S2-HARDEN-001 補齊真實 Places 與可見頁刷新人工驗收](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/40) | 補足 S2-023／S2-024 的真實 browser key 與可見頁刷新人工驗收 | 先確認展示路徑真的可用，避免後續預測接上錯的查詢流程 |
| 2 | [#41 S2-HARDEN-002 完整新莊 catalog／source mapping 匯入策略與覆蓋報告](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/41) | 釐清完整目錄與 mapping 覆蓋界線 | 沒有覆蓋界線，就不能建立可靠路邊 300 公尺區域標籤 |
| 3 | [#42 S2-HARDEN-003 多輪官方來源量測與刷新品質門檻](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/42) | 取得多輪 p50／p95、失敗率與品質門檻證據 | 單輪量測只能作設定初值，不能支撐模型或服務門檻 |
| 4 | [#43 S3-001 已發布觀測匯出與標籤 dry run](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/43) | 從主系統已發布觀測產生路邊／路外 dry run 標籤報告 | 先看有多少可用標籤與拒絕原因，再決定模型是否值得做 |
| 5 | [#44 S3-002 基準模型與時間切分回測報告](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/44) | 建立可解釋 baseline 與時間切分回測 | 先證明資料有基本可預測性，再考慮更複雜模型 |
| 6 | [#45 S3-003 預測推論契約降級與模型版本接點](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/45) | 補齊 `predictions` 的拒絕原因、模型版本與回滾語意 | 讓無模型、資料不足與模型可用三種狀態都能被前端穩定呈現 |

## 進入實作前檢查

- #40 完成前，不宣稱前端真實 Places 與可見頁刷新已端到端驗收。
- #41 完成前，不宣稱新莊 catalog 完整，也不輸出完整 300 公尺路邊區域機率。
- #42 完成前，不把單輪 API 成功視為來源穩定性或正式刷新門檻。
- #43 完成前，不進入模型訓練。
- #44 完成前，不宣稱預測品質已達展示或服務標準。
- #45 完成前，即使有離線模型結果，也不接入使用者查詢回應。

## 不做事項

- 不高頻呼叫官方來源。
- 不提交原始 API 回應、Google key、TDX key、`.env` 或敏感設定。
- 不把 collector 的 CSV 或排程當成主系統即時資料契約。
- 不使用 `CANDIDATE`、`REJECTED`、未知、過期或品質不足觀測作真實標籤。
- 不以目前車位、人工估計或隨機值輸出抵達時機率。

## 完成口徑

本次規劃完成後，只能宣稱「下一批 GitHub Issues 與 Sprint 3 工作順序已建立」。不能宣稱 Sprint 3 已實作、模型已訓練、預測已可用或人工驗收已補齊。
