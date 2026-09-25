# Sprint 2 第一組 GitHub Issues 草案

更新日期：2026-09-25。狀態：PM／Scrum Master／Tech Lead 草案；尚未建立正式 GitHub Issues，尚未 coding。

## 使用方式

這份文件是 Sprint 2 開始實作前的第一批任務草案。建議先在 GitHub Issues 建立對應 Issue，再依照順序逐張處理。每張 Issue 都包含範圍、驗收標準、測試方式與 Human-in-the-loop 檢查點。

Sprint 2 第一組的目標不是完成完整停車預測，而是建立可維護、可測、可部署的主系統骨架，並用 mock API 先跑通查詢生命週期。第一個可展示版本先不等待 ML。


## GitHub Issues

| Issue | GitHub 連結 |
| --- | --- |
| S2-001 | [#1](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/1) |
| S2-002 | [#2](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/2) |
| S2-003 | [#3](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/3) |
| S2-004 | [#4](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/4) |
| S2-005 | [#5](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/5) |
| S2-006 | [#6](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/6) |
| S2-007 | [#7](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/7) |
## 第一組 Issues 總覽
| 建議 Issue | 標題 | 主要角色 | 依賴 | 產出 |
| --- | --- | --- | --- | --- |
| S2-001 | 建立 Sprint 2 實作分支與專案工作規則 | PM／Scrum Master | 無 | 分支、Issue 管理規則、環境變數範本規則 |
| S2-002 | 建立 Spring Boot Maven 後端骨架與健康檢查 | Tech Lead／Backend | S2-001 | 可啟動 API、health endpoint、測試骨架 |
| S2-003 | 建立 Vue 3 + Vite 前端骨架與 Google Maps 設定入口 | Tech Lead／Frontend | S2-001 | 可啟動前端、環境變數入口、基本頁面 |
| S2-004 | 建立 Docker Compose 本機開發環境 | Tech Lead／DevOps | S2-002、S2-003 可並行後整合 | web／api／mysql／redis 基礎服務 |
| S2-005 | 建立後端 profile、H2 測試邊界與 MySQL 連線設定 | Backend／QA | S2-002、S2-004 | dev/test/docker profiles、H2 測試規則 |
| S2-006 | 建立查詢 API 契約與 mock 查詢生命週期 | Backend／Frontend | S2-002、S2-003、S2-005 | POST /parking-queries、GET /parking-queries/{id} mock flow |
| S2-007 | 建立第一版文件同步與驗收檢查清單 | PM／QA | S2-001 | 任務完成檢查清單、狀態頁更新規則 |

---

# S2-001 [PM] 建立 Sprint 2 實作分支與專案工作規則

## 背景

Sprint 2 將從討論進入主系統實作。開始前要先建立可追蹤的工作方式，避免程式、文件、Issue 與 Git 歷史彼此脫節。

## 範圍

- 建立或確認 Sprint 2 實作分支命名。
- 將本文件的 Issue 草案轉成 GitHub Issues 或等效 Markdown 任務清單。
- 確認 `.env.example` 只放變數名稱與範例值，不放任何 Google、TDX 或政府 API 金鑰。
- 確認每張 Issue 的 Done 條件：測試、文件、狀態頁與 Git commit。
- 確認 Pull Request 或本地合併前的檢查規則。

## 不包含

- 不建立 Spring Boot 或 Vue 專案。
- 不串接 Google、TDX 或新北市 API。
- 不建立資料庫 schema。

## 驗收標準

- [ ] Sprint 2 實作分支存在，且分支名稱可讀。
- [ ] 第一組 Issues 已建立在 GitHub Issues，或暫時以 Markdown 追蹤並可一對一轉成 Issues。
- [ ] 專案明確記錄金鑰不進 Git 的規則。
- [ ] 任務流程明確要求：完成重要任務時更新 `docs/current-state.md`；影響進度展示時更新 `docs/project-status.html`。
- [ ] 每張 Issue 都有範圍、不包含、驗收標準、測試方式與 Human-in-the-loop。

## 測試方式

- 文件檢查：確認 Issue 或任務文件欄位完整。
- Git 檢查：確認分支狀態乾淨或清楚列出未提交變更。
- 安全檢查：確認沒有 API key、secret、token 被寫入文件或範例設定。

## Human-in-the-loop

- Sponsor 確認第一組 Issues 的順序是否符合預期。
- Sponsor 確認是否要立即建立正式 GitHub Issues，或先用 repo Markdown 管理。
- Sponsor 確認分支命名，例如 `sprint-2-main-system-scaffold`。

## 工程審查重點

這張 Issue 用來證明你有基本工程流程，不是直接在 main 上亂寫。對外說明時可以說明：先把需求轉成可驗收任務，再小步提交，避免 MVP 開發失控。

---

# S2-002 [Backend] 建立 Spring Boot Maven 後端骨架與健康檢查

## 背景

主系統後端已決定使用 Java、Spring Boot Web／MVC、Maven。第一步要先建立能啟動、能測試、能被 Docker 包裝的 API 骨架。

## 範圍

- 建立 Spring Boot Maven 專案骨架。
- 加入 Spring Web、Validation、Actuator 或等效健康檢查能力。
- 建立基本 package 結構，例如 `api`、`application`、`domain`、`infrastructure`、`config`。
- 建立 health endpoint，例如 `/actuator/health` 或 `/api/health`。
- 建立基本測試骨架，至少能驗證 application context 或 health endpoint。

## 不包含

- 不建立正式停車資料 entity。
- 不串接 MySQL、Redis、Google 或新北市 API。
- 不實作真正的查詢邏輯或預測邏輯。

## 驗收標準

- [ ] 可以用 Maven 啟動後端。
- [ ] health endpoint 回傳成功狀態。
- [ ] 基本測試可以執行並通過。
- [ ] package 結構符合模組化單體方向。
- [ ] README 或 setup 文件有後端本機啟動方式。

## 測試方式

- 單元／啟動測試：執行 Maven test。
- 手動驗收：啟動後端並呼叫 health endpoint。
- 文件驗收：確認 setup 指令可被初階工程師照著操作。

## Human-in-the-loop

- Sponsor 確認 Maven 而非 Gradle。
- Sponsor 確認健康檢查路徑與本機啟動方式能理解。

## 工程審查重點

這張 Issue 要能說明為什麼先做 health endpoint：它是 Docker、部署、監控與後續整合測試的最小可信訊號。

---

# S2-003 [Frontend] 建立 Vue 3 + Vite 前端骨架與 Google Maps 設定入口

## 背景

前端已決定使用 Vue 3 + Vite。第一版 UI 是地圖優先，後續會接 Google Maps JavaScript API、Places API (New) 與後端查詢 API。開始時先建立骨架與設定入口，不急著串 Google。

## 範圍

- 建立 Vue 3 + Vite 專案骨架。
- 建立單頁入口：搜尋列區、地圖區 placeholder、結果摘要區 placeholder。
- 建立前端環境變數範例，例如 Google Maps API key 的變數名稱，但不放真 key。
- 建立 API client 的 placeholder 結構，準備後續接後端 mock API。
- 建立基本 lint／format 或至少 build 檢查。

## 不包含

- 不載入 Google Maps JavaScript API。
- 不接 Places autocomplete。
- 不實作真地圖、真搜尋、真停車結果。
- 不做複雜路由或全域狀態管理。

## 驗收標準

- [ ] 前端可以本機啟動。
- [ ] 前端可以 build 成功。
- [ ] 首頁可看到搜尋、地圖 placeholder、結果摘要 placeholder。
- [ ] `.env.example` 僅包含變數名稱與假值。
- [ ] 版面符合「地圖與結果同頁可見」方向。

## 測試方式

- Build 測試：執行前端 build。
- 手動驗收：開啟本機頁面檢查 placeholder 是否清楚。
- 安全檢查：確認沒有 Google API key 被提交。

## Human-in-the-loop

- Sponsor 看第一版 placeholder 畫面，確認資訊區塊順序是否符合 Google Maps 類似體驗。
- Sponsor 確認先不加路由與大型狀態管理。

## 工程審查重點

這張 Issue 要說明你雖然前端較不熟，但能先用簡單、可維護的結構完成 MVP 所需畫面，不過度引入工具。

---

# S2-004 [DevOps] 建立 Docker Compose 本機開發環境

## 背景

Docker 是必要需求，因為未來會部署到其他 server。第一組任務要先建立本機可重現環境，支援後端、前端、MySQL 與 Redis。

## 範圍

- 建立 Dockerfile 或開發用 Docker 設定候選。
- 建立 Docker Compose，包含至少 `api`、`web`、`mysql`、`redis`。
- 建立 MySQL volume，避免容器重啟後資料消失。
- 建立 `.env.example` 說明必要環境變數。
- 確認容器間 service name 與連線方式。

## 不包含

- 不做正式 production hardening。
- 不申請網域、不設定 HTTPS。
- 不上雲端 server。
- 不建立 CI/CD pipeline 或 Harness。

## 驗收標準

- [ ] `docker compose up` 可啟動基礎服務。
- [ ] API container 可回 health。
- [ ] MySQL container 有持久化 volume 設定。
- [ ] Redis container 可被 API 設定引用。
- [ ] 文件說明本機啟動、停止與清除資料的方式。

## 測試方式

- Docker 手動驗收：啟動全部服務。
- API 驗收：從 host 呼叫 health endpoint。
- 持久化驗收：重啟 MySQL container 後確認 volume 未被意外清除。

## Human-in-the-loop

- Sponsor 確認本機 Docker Desktop 或 Docker 環境可用。
- Sponsor 確認未來 server 部署仍先以 Docker Compose 為第一階段，不先導入 Kubernetes。

## 工程審查重點

這張 Issue 要能說明 Docker 對本專案的價值：展示可重現、部署可移植、MySQL／Redis 可被明確管理。

---

# S2-005 [Backend/QA] 建立後端 profile、H2 測試邊界與 MySQL 連線設定

## 背景

使用者希望開發端測試可用 H2，但正式資料庫仍是 MySQL + JPA。這張 Issue 要把測試與正式資料庫邊界先定清楚。

## 範圍

- 建立 Spring profiles，例如 `local`、`test`、`docker`。
- `test` profile 使用 H2 做快速測試。
- `docker` 或本機整合環境使用 MySQL。
- 建立資料庫連線設定範例，真密碼只走環境變數。
- 在文件中說明 H2 可驗證什麼、不能代表什麼。

## 不包含

- 不建立完整停車 schema。
- 不做大量資料效能測試。
- 不把 H2 當正式環境。

## 驗收標準

- [ ] 測試環境可使用 H2 跑基本測試。
- [ ] Docker／local 設定可指向 MySQL。
- [ ] 文件明確寫出 H2 與 MySQL 的分工。
- [ ] 真實資料庫密碼與 API key 不進 Git。
- [ ] 若尚未建立 schema，也要保留後續 migration 接入點。

## 測試方式

- H2 測試：執行 Maven test。
- MySQL 連線驗收：在 Docker Compose 啟動後確認 API 能連到 MySQL，或至少設定可被啟動讀取。
- 設定檢查：確認 secrets 沒有出現在 Git diff。

## Human-in-the-loop

- Sponsor 確認 H2 僅作開發測試，不作正式資料庫。
- Sponsor 確認未來技術說法：H2 快速回饋，MySQL 整合驗證才代表正式行為。

## 工程審查重點

這張 Issue 可以展示你知道 H2 與 MySQL 行為可能不同，會把快速測試與正式資料庫驗證分層，而不是把測試通過誤認為 production ready。

---

# S2-006 [Backend/Frontend] 建立查詢 API 契約與 mock 查詢生命週期

## 背景

第一個可展示版本要先跑通「搜尋／座標 → 建立查詢 → 查詢狀態 → 顯示 ETA 與停車資料區塊」的生命週期。還沒接真 Google 與真政府 API 前，先用 mock/stub 讓前後端契約穩定。

## 範圍

- 定義 `POST /parking-queries` 的 request／response DTO 草案。
- 定義 `GET /parking-queries/{queryId}` 的狀態回應。
- 定義錯誤格式與資料不足格式。
- mock 回應至少包含：目的地、300 公尺半徑、資料區塊狀態、路邊摘要 placeholder、路外結果 placeholder、ETA placeholder。
- 前端可呼叫 mock API，並依狀態顯示 placeholder 或 mock 結果。

## 不包含

- 不接 Google Routes 真 ETA。
- 不接 Google Places 真搜尋。
- 不接新北市官方 API。
- 不實作 ML 預測。
- 不建立正式 Redis query state；可先用記憶體或 stub，但要保留未來 Redis 接口。

## 驗收標準

- [ ] `POST /parking-queries` 可回傳 `queryId`。
- [ ] `GET /parking-queries/{queryId}` 可回傳一致的 mock 查詢狀態。
- [ ] 回應能區分 pending、ready、failed、insufficient data 類型。
- [ ] 前端能顯示查詢結果的基本框架。
- [ ] API 契約文件已更新，欄位名稱與資料狀態清楚。

## 測試方式

- 後端測試：controller/service 測試驗證 DTO 與狀態。
- 前端手動驗收：送出 mock 查詢後可看到結果框架。
- API 手動驗收：用 curl 或等效工具呼叫 POST／GET。
- 文件驗收：`docs/api-contract-v1-draft.md` 或相關文件同步更新。

## Human-in-the-loop

- Sponsor 確認 mock 回應欄位是否符合未來畫面需要。
- Sponsor 確認資料不足、過期、有效 0 位等狀態的語意是否清楚。
- Sponsor 確認「先看到 ETA 與位置、車位完成後自動補上」的流程是否在契約中可表達。

## 工程審查重點

這張 Issue 是第一個垂直切片。對外說明時可以說明：先用 mock 穩定契約，降低前後端與外部 API 同時變動的風險。

---

# S2-007 [PM/QA] 建立第一版文件同步與驗收檢查清單

## 背景

本專案需要作為作品集專案，文件是專案記憶，也是技術說明素材。開始 coding 後，每張任務都要能回溯為什麼這樣做、怎麼測、限制是什麼。

## 範圍

- 建立 Sprint 2 任務完成檢查清單。
- 定義哪些變更需要更新 `docs/current-state.md`。
- 定義哪些變更需要更新 `docs/project-status.html`。
- 定義哪些決策需要更新 `docs/decisions.md`。
- 定義 PR／commit 前的基本自檢項目。

## 不包含

- 不導入 Harness CI/CD 平台。
- 不建立完整 GitHub Actions pipeline；可列為後續 Issue。
- 不替代正式測試。

## 驗收標準

- [ ] 有一份清楚的任務完成檢查清單。
- [ ] 能區分文件、測試、Git、技術說明四種驗收。
- [ ] 文件規則與 `AGENTS.md` 不衝突。
- [ ] Sponsor 能用此清單判斷任務是否 Done。

## 測試方式

- 文件檢查：以 S2-002 或 S2-006 模擬一次任務完成，確認清單可用。
- 人工驗收：Sponsor 確認清單不會太繁瑣，但足以支撐作品集專案。

## Human-in-the-loop

- Sponsor 確認是否接受「沒有文件更新就不算 Done」的規則。
- Sponsor 確認任務完成時是否需要每次都 commit，或可依小批次 commit。

## 工程審查重點

這張 Issue 展示你知道作品集專案不只是跑得動，還要能說明設計、驗證與限制。這會比只展示畫面更有說服力。

---

# S2-008 [CI/CD] 建立 GitHub Actions 基礎 CI

## 背景

Sprint 2 第一組 Issues 已建立後端與前端骨架，後續需要基本 CI 來避免每次推送後才發現 Maven 或 Vite build 壞掉。這張 Issue 補上 GitHub Actions 基礎檢查，不導入 Harness，也不做正式部署。

## 範圍

- 建立 GitHub Actions workflow。
- 後端檢查：執行 `backend/mvnw.cmd test` 或 Linux 等效 Maven Wrapper 測試。
- 前端檢查：在 `frontend/` 執行 `npm ci` 與 `npm run build`。
- 若 S2-004 Dockerfile 已完成，加入 Docker build 檢查；若尚未完成，先保留 TODO。
- workflow 不需要任何真實 API key。

## 不包含

- 不部署到遠端 server。
- 不導入 Harness CI/CD 平台。
- 不建立 production release 流程。
- 不設定雲端主機、網域、HTTPS。

## 驗收標準

- [ ] Pull request 或 push 會觸發 GitHub Actions。
- [ ] 後端 Maven 測試會執行。
- [ ] 前端 npm build 會執行。
- [ ] workflow 不需要真實 Google、TDX、新北市 API key。
- [ ] CI 失敗時能從 GitHub Actions log 看出是哪一段失敗。

## 測試方式

- GitHub Actions：推送分支後確認 workflow 有執行。
- 本機對照：確認本機 `backend/mvnw.cmd test` 與 `frontend/npm run build` 仍通過。

## Human-in-the-loop

- Sponsor 確認第一版 CI 只做測試與 build，不做部署。
- Sponsor 確認 GitHub Actions 用量與 repo 設定可接受。

## 工程審查重點

這張 Issue 用來證明專案有基本自動化品質閘門。第一版先檢查 Maven test 與 Vite build，等 Docker 與部署成熟後再擴充成 Docker build、image push 或正式部署。
