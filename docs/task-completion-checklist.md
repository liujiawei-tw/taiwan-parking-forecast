# 任務完成檢查清單

更新日期：2026-09-25。用途：每張 GitHub Issue 完成前，用這份清單確認功能、文件、測試、Git 與工程說明都到位。

## 使用方式

每張 Issue 完成前，先依序檢查下列項目。不是每一項都一定要做，但不做時要在 Issue 或回報中說明原因。

## 1. 範圍檢查

- [ ] 本次變更符合該 Issue 的範圍。
- [ ] 沒有把後續 Issue 的功能偷偷混進來。
- [ ] 若有範圍調整，已記錄原因與影響。
- [ ] Human-in-the-loop 檢查點已提出，或 Sponsor 已確認。

## 2. 功能驗收

- [ ] 使用者或 API 可以得到預期結果。
- [ ] 載入中、失敗、資料不足、有效 0、未知與過期狀態沒有混淆。
- [ ] mock、stub、真實資料來源的界線有明確標示。
- [ ] 若尚未接外部服務，畫面與文件沒有暗示已完成真實介接。

## 3. 測試驗收

- [ ] 後端變更已執行必要 Maven 測試，或說明未執行原因。
- [ ] 前端變更已執行 `npm run build`，或說明未執行原因。
- [ ] Docker／部署相關變更已執行 Docker build 或 runtime 驗證，或說明未執行原因。
- [ ] H2 測試與 MySQL／Docker 驗證的邊界有說明。
- [ ] 手動驗證的 URL、結果與限制已記錄。

## 4. 文件驗收

- [ ] 重要進度已更新 `docs/current-state.md`。
- [ ] 新決策已更新 `docs/decisions.md`。
- [ ] 未決問題已更新 `docs/open-questions.md`。
- [ ] 影響使用者流程、架構或 API 時，相關設計文件已同步。
- [ ] 影響人類可讀進度時，已更新 `docs/project-status.html`。
- [ ] README 中的專案狀態沒有過時描述。

## 5. Git 驗收

- [ ] `git diff --check` 沒有尾端空白或格式錯誤。
- [ ] `git status` 清楚，沒有不該提交的檔案。
- [ ] 沒有真實 API key、密碼、token、私密 `.env` 或大型原始資料進 Git。
- [ ] commit 訊息能說明本次變更目的。
- [ ] 已 push 到目前工作分支。

## 6. 工程說明驗收

任務回報至少包含：

- [ ] 做了什麼。
- [ ] 為什麼這樣做。
- [ ] 怎麼驗證。
- [ ] 還有哪些限制或下一步。
- [ ] 是否可以關閉 Issue，或還需要 Sponsor 確認什麼。

## 7. 何時更新哪些文件

| 變更類型 | 必須更新 |
| --- | --- |
| 任務完成、阻礙、驗證結果 | `docs/current-state.md` |
| 架構、技術選型、資料規則、流程規則定案 | `docs/decisions.md` |
| 還沒決定、需要量測、需要 Sponsor 回覆 | `docs/open-questions.md` |
| API request／response、錯誤碼、資料狀態 | `docs/api-contract-v1-draft.md` 或對應 API 文件 |
| 前端流程、狀態文案、版面 | 對應 UI 文件與 `docs/project-status.html` |
| 開發流程、驗收規則、Git／PR 規則 | `docs/development-workflow.md` |
| Sprint 狀態、Issue 完成度、人類展示進度 | `docs/project-status.html` |

## 8. S2-006 範例套用

S2-006 mock 查詢生命週期完成時，清單應能得到以下結論：

- 功能：三個 mock endpoint 可建立、讀取、刷新查詢；前端可顯示 mock 結果。
- 測試：Maven test、前端 build、本機 HTTP、Docker build、Docker runtime endpoint 都已驗證。
- 文件：`current-state.md` 與 `project-status.html` 已同步。
- Git：commit 已 push，工作樹乾淨。
- 限制：尚未接 Google、新北市官方 API、Redis query state 或正式預測模型。
