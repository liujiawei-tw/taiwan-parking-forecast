# AI Working Instructions

## Project Goal

協助使用者判斷抵達目的地附近時，停車場仍有車位的可能性；先以 TDX 覆蓋的全臺停車資料建立基礎，再擴充地方政府免費 API。

## Roles and Stage

專案發起人兼主要開發者是初階後端工程師。AI 每次回覆須先說明正在扮演的角色與目前開發階段，並以引導方式說明技術決策，加入工程審查重點以檢查可解釋性、驗證證據與限制。目前已進入 Sprint 2 主系統實作；已完成第一組骨架、Docker、CI 與 mock 查詢流程，正在進行第二組資料基礎與 300 公尺查詢。

## Important Constraints

- 實作 TDX 介接時，以官方 [API 使用指引](https://tdx.transportdata.tw/api-service/swagger)確認端點、欄位與收費，以官方 [SampleCode](https://github.com/tdxmotc/SampleCode) 的 Java 範例作為介接認證與呼叫的指定參考。
- 使用者目前為 TDX 免費基礎會員，可使用進階服務；不要把「可存取」誤認為免費無限制。先查實際額度，再決定擷取計畫。
- 不將 TDX Client ID、Client Secret 或其他金鑰寫進 repository、Issue、Notebook 輸出或截圖。
- 區分來源觀測值、模型估計值與沒有資料；模型估計不得冒充官方即時車位。
- `parking-data-collector` 是獨立的離線 ML 訓練資料收集器，只產出歷史訓練／回測素材；不服務使用者、不提供主系統即時快照或 API，也不決定主系統刷新頻率。主系統須獨立介接官方即時來源。兩者只可能在日後透過經驗證的模型產物形成離線訓練到線上推論的關係；共用官方平台的流量僅在營運層面合計評估。不要把訓練收集器的程式、CSV、時間戳或排程當成主系統的即時資料契約。
- 一人 Scrum 實驗：使用者決定產品優先順序並實作，AI 輔助需求、設計、審查、回顧和面試準備。
- 建立、移動或刪除檔案前，必須先讀取或檢查目前 repo 架構與相關文件，確認檔案位於既有架構內。後端程式放在 `backend/src/...`，前端程式放在 `frontend/src/...`，文件放在 `docs/...`；不得產生 `backend/backend/...`、`frontend/frontend/...` 這類巢狀專案目錄。

## Source of Truth

- 需求與驗收：`docs/requirements.md`
- 已決策事項：`docs/decisions.md`
- 尚待決定：`docs/open-questions.md`
- 目前進度：`docs/current-state.md`
- 主系統技術棧與多來源統一化：`docs/backend-stack-and-normalization.md`
- TDX 費率與待查資料：`docs/tdx-data-and-cost.md`
- 歷史資料取得與 ML 試驗：`docs/historical-data-plan.md`

## When Updating

每次完成重要討論或實作，更新 `docs/current-state.md`；新決策記入 `docs/decisions.md`，未解問題記入 `docs/open-questions.md`。把已確認事實、工作假設與待驗證內容明確分開。不要因為文件存在，就把草案視為已完成的系統。
當 Sprint 階段、實際完成狀態或重要阻礙改變時，同步更新 `docs/project-status.html` 的狀態、時間與待辦。該 HTML 是給人看的進度快照，需求與決策仍以 Markdown 文件為準；不可用主觀百分比冒充驗收結果。
