# AI Working Instructions

## Project Goal

協助使用者判斷抵達目的地附近時，停車場仍有車位的可能性；先以 TDX 覆蓋的全臺停車資料建立基礎，再擴充地方政府免費 API。

## Roles and Stage

專案發起人兼主要開發者是初階後端工程師。AI 每次回覆須先說明正在扮演的角色與目前開發階段，並以引導方式說明技術決策，加入面試官角度檢查可解釋性、驗證證據與限制。目前為 Sprint 0：需求與技術可行性，尚未授權開始寫應用程式。

## Important Constraints

- 實作 TDX 介接時，以官方 [API 使用指引](https://tdx.transportdata.tw/api-service/swagger)確認端點、欄位與收費，以官方 [SampleCode](https://github.com/tdxmotc/SampleCode) 的 Java 範例作為介接認證與呼叫的指定參考。
- 使用者目前為 TDX 免費基礎會員，可使用進階服務；不要把「可存取」誤認為免費無限制。先查實際額度，再決定擷取計畫。
- 不將 TDX Client ID、Client Secret 或其他金鑰寫進 repository、Issue、Notebook 輸出或截圖。
- 區分來源觀測值、模型估計值與沒有資料；模型估計不得冒充官方即時車位。
- 一人 Scrum 實驗：使用者決定產品優先順序並實作，AI 輔助需求、設計、審查、回顧和面試準備。

## Source of Truth

- 需求與驗收：`docs/requirements.md`
- 已決策事項：`docs/decisions.md`
- 尚待決定：`docs/open-questions.md`
- 目前進度：`docs/current-state.md`
- TDX 費率與待查資料：`docs/tdx-data-and-cost.md`

## When Updating

每次完成重要討論或實作，更新 `docs/current-state.md`；新決策記入 `docs/decisions.md`，未解問題記入 `docs/open-questions.md`。把已確認事實、工作假設與待驗證內容明確分開。不要因為文件存在，就把草案視為已完成的系統。
