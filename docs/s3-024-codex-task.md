# Codex 交接｜S3-024 最小 DEMO 遠端部署

更新：2026-10-24。此檔已由 GPT 寫入 GitHub 的工作分支，不是產品程式實作。

- Repository：`liujiawei-tw/taiwan-parking-forecast`
- 需求 Issue：[#85](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/85)（**最終驗收範圍以該 Issue 為準**）
- 工作分支：`feature/s3-024-minimal-demo-deploy`
- 分支起點：`main@0ef5343ec602842b3de91041d462bd08325b23fa`
- 責任分工：GPT 規劃和 Code Review；Codex 實作、Commit、Push；GPT Review PASS 後開 PR；使用者人工 Merge

## 接手方式

1. 先確認 Git remote 是上方 Repository，檢查 `git status` 與現有 worktree。從遠端 fetch 最新分支後，在**乾淨的獨立 worktree** 或無未提交變更的工作目錄切換工作分支，**不要用 reset --hard、clean -fd、強制 checkout 或 force push** 破壞本機檔案。
2. 先閱讀 `AGENTS.md`、`docs/current-state.md` 最新紀錄、`docs/docker-deployment.md`、`docker-compose.yml`、`frontend/nginx.conf`、`frontend/src/App.vue`、`.github/workflows/ci.yml`，並閱讀完整 Issue #85；確認目前程式碼後直接實作，不要重做已有功能。
3. 如果已有人修改同一分支或 `main` 前進，先檢查差異並保留既有變更，不要覆寫；必要時在 GitHub Issue 留下阻礙。

## 需要交付的最小內容

- **遠端 Compose 設定**：保留本機 `docker-compose.yml` 的開發行為；單機遠端 DEMO 僅公開 Web/HTTPS 入口，不公開 API、MySQL 或 Redis；MySQL 資料持久化。
- **同源 API**：遠端前端呼叫相對 `/api`，由 Nginx 代理至 `api:8080`；確認 Spring Boot 的 `/api/v1/...` 路徑不被錯誤剝除。原本本機開發方式仍可使用。
- **HTTPS、Secrets 與最低限度防濫用**：使用最簡單的安全 HTTPS 方案（可交由部署主機既有反向代理）；真密碼與 Google Routes server key 只在部署環境，不可進 Git/image/前端 bundle；Google Maps browser key 需限制 referrer/API。對會消耗 Routes 額度的公開查詢建立 API 做基本限流，保留來源既有共享刷新與 5 分鐘冷卻。
- **維運與發布說明**：能照文件啟停、查看健康狀態／logs、回退版本、備份和隔離還原 MySQL；提供一條最小 smoke 覆蓋 500m、目前車位、ETA（有有效金鑰才實測）、手動刷新/冷卻及 `INSUFFICIENT_DATA`。同步更新相關文件的已做／未驗收狀態。
- **先不擴張**：不新增 ML、全臺資料、完整新莊 catalog、登入帳號、Kubernetes、Terraform、HA、監控平台、前端全面改版、大量截圖或新測試框架。已存在的服務只修阻擋性 bug，不做無目的重構。

## 驗證界線

只跑最有資訊價值的相關檢查（Compose 展開 config、Nginx /api 路徑語意、必要的前端 build、相關單元測試），讓現有 GitHub Actions CI 補齊後端測試、前端測試和 Docker build。不要為驗證消耗非必要的 Google／政府 API 額度。遠端主機、網域、憑證或金鑰不可得時，只交付能重現的部署設定，把遠端驗收明列為 **BLOCKED**，不要冒稱已上線。

## Skills 與平行實作

可視需要使用本機已安裝的 `impeccable`、`GPT relay`、`mattpocock-skills`；不為套用 Skills 而擴大實作。平行 Agent 僅可分配**互不重疊的檔案和責任**（例如 Compose/Nginx 與獨立審查），由主 Agent 整合；同一檔案勿同時編輯。

## GitHub 交付規則

- 在 `feature/s3-024-minimal-demo-deploy` 完成 Commit 並 Push。
- **Codex 不開 PR、不 Merge、不修改 main**。
- 回報 exact HEAD SHA、變更檔案、實際測試結果、對應 CI URL、尚未完成／需使用者資源的項目及金鑰安全檢查。
- 待 GPT 針對精確 SHA 審查。Code Review 僅阻擋：機密外洩、API/DB/Redis 不當公開、遠端瀏覽器呼叫 localhost、核心查詢失效、已發布快照保護失效、把未知補零或產生假機率，以及 CI 失敗。PASS 後才由 GPT 開 PR，最後由使用者人工 Merge。
