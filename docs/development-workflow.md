# 實作工作流程、任務與驗收（Sprint 2 起）

更新日期：2026-09-25。狀態：實作前流程確認；尚未開始 coding。

## 角色分工

| 角色 | 由誰擔任 | 責任 |
| --- | --- | --- |
| Sponsor／Product Owner | 使用者 | 決定優先順序、接受或退回成果、提供金鑰與外部帳號設定狀態、不需親自寫所有程式 |
| PM／Scrum Master | AI 協助 | 拆任務、維護 backlog、定義驗收標準、追蹤阻礙、提醒文件與 Git 更新 |
| Tech Lead／Developer | AI 實作，使用者審閱 | 實作、測試、提交、說明取捨與風險 |
| QA／Reviewer | AI 先自檢，使用者做關鍵驗收 | 驗證功能、失敗情境、文件、面試可說明性 |

一人 Scrum 的重點不是假裝有多人團隊，而是把不同責任明確化。AI 可以協助 PM 與開發，但 Sponsor 對產品方向與最終驗收仍有決策權。

## 任務由誰開

PM 會根據 `implementation-plan.md` 拆出任務草案。每個任務在開始實作前至少要有：

- 任務名稱。
- 背景與目的。
- 範圍內工作。
- 範圍外工作。
- 驗收標準。
- 測試方式。
- Human-in-the-loop 檢查點。
- 相關文件連結。

實作時可先在 Markdown 裡維護任務清單；若 GitHub 遠端與 Projects 準備好，再轉成 GitHub Issues。第一版不必為了流程工具而延遲 coding。

## 任務模板

```md
# [類型] 任務名稱

## 背景
為什麼要做這件事。

## 範圍
- 要完成的項目。

## 不包含
- 明確不做的項目。

## 驗收標準
- [ ] 可由使用者或測試明確判斷的結果。
- [ ] 錯誤或資料不足情境有處理。
- [ ] 文件或設定已更新。

## 測試
- 單元測試：
- H2 測試：
- Docker MySQL／整合驗證：
- 手動驗收：

## Human-in-the-loop
- 需要 Sponsor 確認的畫面、行為、外部帳號設定或風險。
```

## Human-in-the-loop 檢查點

不是每一行程式都要人工確認。建議把人工確認放在會影響產品方向、外部成本、部署或面試展示的地方。

| 檢查點 | 需要 Sponsor 確認什麼 | 沒確認前是否可繼續 |
| --- | --- | --- |
| 任務進入實作前 | 任務範圍與驗收標準 | 可先做不依賴該決策的準備工作 |
| 外部服務介接前 | Google／TDX／政府 API key 與權限已由 Sponsor 設好，不把金鑰交給文件或 Git | 可先做 mock 與設定欄位 |
| UI 主要流程完成後 | 畫面是否符合預期、文案是否容易懂 | 可先修技術問題，產品文案等確認 |
| Docker 或遠端部署前 | 部署目標、環境變數、網域／HTTPS 是否準備好 | 可先在本機 Compose 驗證 |
| 預測機率上線前 | 回測結果是否足夠展示，資料不足時是否接受降級 | 可先顯示資料不足，不輸出機率 |
| PR／合併前 | 任務驗收是否通過，文件是否更新 | 未通過就不視為 Done |

## 驗收標準層級

每個任務的驗收要分三層：

1. **功能驗收**：使用者或 API 是否得到預期結果。
2. **工程驗收**：測試、Docker、資料庫、Redis、錯誤處理是否符合設計。
3. **面試驗收**：是否能說明為什麼這樣設計、如何驗證、還有什麼限制。

例如第一個端到端版本的高層驗收：

- 可啟動 Docker Compose 的基本服務。
- 後端可回健康檢查。
- 查詢 API 可用 mock 或小樣本回傳目的地 300 公尺內已收錄位置。
- 有效 0、未知、過期、無資料不混淆。
- H2 測試與 MySQL 整合驗證的邊界有說明。
- 文件與 project status 已更新。

## Harness 是否導入

若此處的 Harness 指的是 Harness CI/CD 平台，第一版建議**先不導入**。

理由：

- 目前最大風險是資料契約、外部 API、快照發布與前後端整合，不是複雜部署流水線。
- 專案第一版可用 GitHub Issues／Projects 管理任務，用 GitHub Actions 做基本 CI，例如 Maven test、前端 build、Docker build 檢查。
- Harness 適合在需要較完整的部署環境、審批流程、環境管理或多環境 release 時再導入。
- 面試時可以說明：先用輕量工具交付 MVP，等部署複雜度上升再評估 Harness，這是合理的工程取捨。

如果 Sponsor 之後明確要求使用 Harness，建議放在 Docker 可穩定 build 並有基本測試後，再建立 pipeline；不要放在第一個可展示版本之前。


## Branch、Issue 與 PR 規則

- `main` 保存已確認的文件與可展示成果。
- Sprint 2 實作分支為 `sprint-2-main-system-scaffold`。
- 每個實作增量以 GitHub Issue 追蹤；commit 訊息要能對應到 Issue 或清楚說明變更目的。
- 建議每張 Issue 使用小步 commit；同一張 Issue 可有多個 commit，但每個 commit 要保持可解釋。
- 合併回 `main` 前，至少要完成該 Issue 的驗收標準、測試方式、文件更新與 Sponsor 需要的 Human-in-the-loop 檢查。
- 若某張 Issue 尚未完成但需要保存進度，可推到 Sprint 分支，不把它視為 Done。

## 金鑰與環境變數規則

- `.env.example` 只放變數名稱與假值，用來說明需要哪些設定。
- 真實 `.env`、Google API key、TDX Client ID／Secret、資料庫密碼與 token 不進 Git。
- 前端只使用受網頁來源限制的 browser key；後端 Routes／政府資料等 server side key 只由後端環境變數讀取。
- 若文件或範例需要提到外部服務，只記錄服務名稱、用途與變數名稱，不記錄真實憑證。
- commit 前要檢查 `git diff`，確認沒有 secrets、下載的大型資料檔或本機 IDE 設定。

## Definition of Done 檢查清單

每張 Issue 完成前，至少檢查以下項目：

- [ ] 功能或文件變更符合該 Issue 的範圍。
- [ ] 範圍外項目沒有被偷偷混入。
- [ ] 驗收標準逐項可判斷，未完成項目有明確說明。
- [ ] 必要測試已執行；若未執行，原因已記錄。
- [ ] 沒有真實金鑰、密碼、token 或大型原始資料進 Git。
- [ ] 需要更新的 Markdown 已更新。
- [ ] 影響人類進度視圖時，`docs/project-status.html` 已更新。
- [ ] Git commit 訊息可讀，且工作樹狀態清楚。
- [ ] 需要 Sponsor 檢查的項目已提出或已完成。

## 每次任務完成的固定收尾
- 跑必要測試或明確說明未跑原因。
- 更新相關 Markdown。
- 更新 `docs/current-state.md`。
- 若影響人類可視化進度，更新 `docs/project-status.html`。
- Git commit 使用可讀訊息。
- 回報完成內容、測試結果、風險與下一步。
