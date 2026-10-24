# Codex 任務交接：S3-023 最小 DEMO 驗收（2026-10-24）

## 工作定位
- Issue：[#84](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/84)
- Repository：`liujiawei-tw/taiwan-parking-forecast`
- 工作分支：`feature/s3-023-minimal-demo-acceptance`
- 起點：`main@c3822d2931f7ad33ab5180f64fd36f82031fc82e`（PR #89 已 Merge）
- 優先：檢查合併後可展示的本機正常流程，有問題才修，**不要為了寫程式而寫程式**。

## 接手
1. 先 `git status`、`git remote -v`；fetch origin 並在乾淨的獨立 worktree／工作目錄使用指定分支。不要 reset --hard、git clean、force push、刪除資料庫或原本 Docker volume。
2. 讀 `AGENTS.md`、本文件、GitHub Issue #84、`docs/s3-023-demo-e2e-acceptance.md`、`docs/current-state.md` 最新部分、`docs/docker-deployment.md`、前後端查詢／刷新程式及 Compose。對照現有證據，不重做 97 個測試已覆蓋的完整故障矩陣。
3. 首先確認本機 Docker 運作與現有資料。不要假設歷史上 `s3022demo` 的容器今天仍在運行；如果沒有既有 verified mapping 與有效 API keys，記錄阻礙，**不可用空資料假稱 DEMO 已驗收**。

## 本機 Git 同步與分支清理（使用者新增的必要要求）

**最終本機狀態只能保留一條本地分支 `main`，且 `main` HEAD 必須與 GitHub `origin/main` 的 HEAD 完全一致。** 此要求只適用於本機 local branches／worktrees，不要求刪除 GitHub 上供 PR 使用的遠端 feature 分支。

1. 開工前先 `git status`、`git branch -vv`、`git worktree list`、`git remote -v`、`git fetch origin --prune`。逐一辨識原有 worktree、未提交變更與**未 push 的獨有 commit**。
2. 開發中可以暫時使用此 feature branch 或獨立 worktree；如果有 bug，先完成 commit 並 push 到 GitHub 的 `feature/s3-023-minimal-demo-acceptance`，驗證遠端已收下該 SHA，再清理本機分支。Codex **不能自己開 PR／Merge 或刪除 GitHub 遠端分支**。
3. 交付後將工作目錄切回 `main`、更新 `origin/main`。只允許在可安全 fast-forward 時執行 `git merge --ff-only origin/main`，**不得使用 `git reset --hard` 強制對齊**。如果 `main` 分歧或有未提交變更，先保護資料並明確回報阻礙。
4. 清理由本輪建立的臨時 worktree，再清除其他**本地** feature／舊分支，最後只留下本地 `main`。在刪除任一舊分支之前，檢查它是否有未合併／未 push 的獨有 commit、正在其他 worktree 使用、或包含尚未備份的修改；有的話**不能直接刪除**，先完成安全備份或回報讓使用者處置。不要使用 `git worktree prune`、`git branch -D` 或批次清除動作來掩蓋未保存的資料。
5. 最後實際執行並回報 `git status --short --branch`、`git branch --list`、`git worktree list`、`git rev-parse main`、`git rev-parse origin/main`；兩個 SHA 要相同，local branches 清單只能有 `main`。若有安全阻礙，列出未達成事項及原因，不能假稱清理完成。

**注意：GitHub 遠端 feature 分支保留供 GPT Code Review 與建立 PR 使用；不因本機只留 main 而提前 Merge。**

## 最小驗收內容
- 使用一般 Chrome／Edge 或本機可控瀏覽器，完成一次主流程：選擇 Places 目的地或手動座標 → 查詢半徑 500m → ETA（Google key 實際有效才標 READY）→ 路邊／路外現在的資料狀態與時間 → 手動刷新一次及冷卻。
- 若環境允許，用桌面與手機 viewport 各確認一次版面；僅需要簡短紀錄，可保存必要的 1～2 張截圖，**不要求每個錯誤情境截圖**。
- 再挑**一種容易重現且安全的降級情境**：例如 ETA 失敗但車位資料仍可看，或 query 過期後能重新查詢。盡量使用隔離環境與已存在的腳本；不追加整套來源故障注入，也不要耗用非必要的真 Google／政府 API 額度。
- 保持 `predictions.status=INSUFFICIENT_DATA`、`probability=null`、`modelVersion=null`；已審查子集是部分覆蓋，未知不可改成 0，不宣稱全新莊。
- **只有驗收發現實際會阻擋基本 DEMO 的 bug 才修改應用程式**。沒有 bug 則回報 PASS、已知限制與驗收證據，無須為了交付而建立 PR。
- 如果需要程式修正：最小改動、必要相關測試、commit 並 push 到此分支；不自行開 PR。回報 exact HEAD SHA 給 GPT Review，PASS 後由 GPT 開 PR、使用者人工 Merge。

## 獨立且重要：CI 執行環境
- PR #89 Merge 後 `main` 的 Actions run [#40084871540](https://github.com/liujiawei-tw/taiwan-parking-forecast/actions/runs/40084871540) 仍顯示 failure；先前 runner_id=0、steps=[]，不能把它算作程式測試 failure 或 pass。
- 如本機有權限，可快速確認 Actions／帳單／Runner 的實際拒絕訊息並回報；**不要猜測原因、不要隨意修改 .github/workflows/ci.yml 來碰運氣**。需要 GitHub 管理者操作時，列為外部阻礙。
- 上個 PR 的 `npm ci` 回報兩項 high vulnerabilities；若容易取得 `npm audit` 明細，記錄是否影響 production runtime 或僅 dev dependencies。除非能證明和 DEMO 直接相關，**不要順便大規模升級套件**。

## 完成回覆
- 已確認的主流程與降級情境（實際做的才寫），桌面／手機證據；
- bug、修正及相關測試結果；若沒有 bug，明確說明無需新 PR；
- CI runner 狀態與尚需管理者／真實憑證處理的限制；
- 若有 commit，提供分支最新完整 SHA、變更檔案與 CI URL；不要 Merge。
