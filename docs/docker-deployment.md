# Docker 與部署架構草案（Sprint 1）

更新日期：2026-09-25。狀態：第一版部署拓樸已決定為單台伺服器 Docker Compose 候選；尚未建立 Dockerfile 或 compose 檔，沒有主系統 coding。

## 第一版部署目標

第一版以可重現、可移到其他伺服器為目標。面試可在本機用同一套容器流程展示；日後若開放他人測試，可把同一套 Compose 部署到一台遠端伺服器，再補網域、HTTPS 與金鑰限制。

第一版不先做 Kubernetes、多台主機或自動水平擴充。這不是否定未來擴充，而是先把主系統的資料一致性、官方來源呼叫控制、MySQL 持久化與 Redis 短期狀態做好。

## 候選 Compose 服務

| 服務 | 技術 | 第一版責任 | 是否需要 volume |
| --- | --- | --- | --- |
| `web` | Nginx 或同級反向代理／靜態檔服務 | 提供 Vue build 後的靜態頁，反向代理 `/api` 到 Spring Boot；遠端部署時承接 HTTPS 或接在外部 HTTPS 入口後面 | 通常不需要；憑證若由此服務管理則需要 |
| `api` | Spring Boot Web，Maven build 後的 JVM 容器 | REST API、Google Routes 呼叫、官方停車來源 adapter、MySQL／Redis 存取、快照發布與預測協調 | 不保存業務資料；模型檔若以檔案載入，可掛唯讀 volume |
| `mysql` | MySQL | 停車目錄、來源對應、已發布快照、觀測歷史、來源冷卻、用量與擷取紀錄 | 必要，保存資料庫資料 |
| `redis` | Redis | `queryId` 短期狀態、防重送、刷新鎖／進度、可重建快取 | 第一版可不開啟持久化；若開啟也不得當作事實來源 |

開發時可以讓 Vue dev server 與 Spring Boot 在本機分開跑；但交付時要能用容器啟動完整系統。生產候選以 `web` 服務提供前端靜態檔，避免使用 Vite dev server 當正式網站。

## 網路與對外入口

- 對外只開 `web` 的 HTTP／HTTPS 入口。
- `api`、`mysql`、`redis` 僅在 Docker 內部 network 存取，不直接暴露到公開網路。
- 前端呼叫同網域 `/api/...`，由 `web` 反向代理到 `api`，降低 CORS 與金鑰暴露問題。
- 若遠端要使用瀏覽器定位，網站需要安全環境，通常是 HTTPS。
- Google Maps JavaScript API key 屬前端 key，需限制 HTTP referrer；Routes API key 屬後端使用，需用伺服器端限制與環境變數管理。

## 設定與機密

不得把任何機密寫入 Git、Docker image 或前端 bundle。候選設定來源：

- `.env` 或伺服器環境變數：只保存在部署環境，不提交。
- `GOOGLE_MAPS_BROWSER_API_KEY`：只給前端地圖／Places 使用，仍須在 Google Cloud 限制來源網域。
- `GOOGLE_ROUTES_API_KEY`：只給 Spring Boot 後端使用，不送到瀏覽器。
- `TDX_CLIENT_ID`、`TDX_CLIENT_SECRET`：若未來主系統接 TDX，僅放後端環境變數。
- `MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`：部署環境設定。
- `SPRING_PROFILES_ACTIVE`：例如 `local`、`docker`、`prod`。
- 來源刷新門檻、5 分鐘新鮮度門檻、Redis TTL、Google Routes 模式等，可用環境變數或設定檔注入，但預設值須在文件中可追溯。

`.env.example` 日後可提交，但只能放變數名稱與假值，不能放真實 key。

## 資料持久化與備份

MySQL volume 是必要資料。容器重啟後，已發布快照、歷史觀測、來源冷卻與用量紀錄必須保留。Redis 可視為短期狀態；Redis 重啟造成 `queryId` 失效時，前端提示重新規劃，但 MySQL 已發布車位資料仍可查或標示過期。

第一版至少需要規劃：

- MySQL volume 名稱與掛載位置。
- 本機開發與遠端部署不要共用同一份個人路徑。
- 備份與還原流程日後要能匯出 MySQL 資料；實作前可先列為待辦。
- 若保留大量觀測歷史，後續需規劃歸檔或清理策略，避免資料庫無限制成長。

## 啟動順序與健康檢查

建議依賴方向：`mysql` 與 `redis` 先啟動，`api` 等待資料庫可連線，`web` 代理 `api`。Compose 的 `depends_on` 只能表達啟動順序，不能保證服務已可用；實作時要加 healthcheck 或讓 Spring Boot 自行重試資料庫連線。

健康檢查候選：

- `api`：提供 actuator health 或簡化健康端點，檢查應用可回應、MySQL 可連線、Redis 可連線。
- `mysql`：用 MySQL ping 或等價檢查。
- `redis`：用 Redis ping。
- `web`：確認靜態頁可回應，且 `/api` 代理規則可達。

## 本機展示與遠端部署差異

| 項目 | 本機展示 | 遠端部署 |
| --- | --- | --- |
| 網址 | `localhost` 或本機 IP | 網域或伺服器 IP；若使用定位需 HTTPS |
| Google browser key 限制 | 可加 `localhost` referrer | 加正式網域 referrer；不要沿用本機限制 |
| Routes key | 後端環境變數 | 後端環境變數，並限制來源／配額 |
| MySQL volume | 本機 Docker volume | 遠端 Docker volume，需備份 |
| Redis | 可重啟後重查 | 同樣可重啟後重查；不能依賴 Redis 保存長期狀態 |
| 日誌 | Docker logs 即可 | 至少保留容器日誌；日後再評估集中式 log |

## 不納入第一版的部署範圍

- Kubernetes 或多台主機部署。
- 自動水平擴充 Spring Boot 多實例。
- 雲端代管資料庫或代管 Redis。
- CI/CD 自動部署。
- 公開網址長期營運與正式 SLA。
- 訓練 collector 併入主系統 compose。collector 目前仍是獨立資料收集／訓練素材流程，且資料不完整問題暫緩。

## 待定

- 目標伺服器作業系統與規格。
- 是否由 `web` 容器處理 HTTPS，或使用伺服器外層反向代理／Cloudflare／Caddy 等入口。
- MySQL 版本、Redis 版本、Java 版本、Node 版本與 Spring Boot 版本。
- `api` 是否同時提供前端靜態檔，或維持 `web` 獨立服務。第一版建議 `web` 獨立，部署分工較清楚。
- 模型產物放在 image 內、volume，或由部署流程下載。
- 備份頻率、保留週期與資料清理策略。
