# Docker Compose 部署

更新日期：2026-10-24。狀態：唯一正式設定為根目錄的 docker-compose.yml；本機、作品展示與單機部署共用四個服務、網路隔離、同源 API 與 MySQL 持久化。沒有遠端主機與正式金鑰時，服務仍可按降級契約運行，但不代表 Routes ETA 或停車機率已驗收。

## 唯一 Compose 與網路

使用同一份 docker-compose.yml 啟動：

~~~bash
docker compose --env-file .env up -d --build
~~~

Compose project name 固定為 taiwan-parking-forecast，MySQL named volume 為 taiwan-parking-forecast_mysql-data。此名稱沿用現有主系統資料 volume；Compose stop、down 與 image 重建都不會刪除該 volume。API、MySQL、Redis 不發布主機埠，只有 Web 可從主機 loopback 連線：

- Web：http://127.0.0.1:18088
- 同源 API health：http://127.0.0.1:18088/api/health
- Web 主機埠可用 WEB_HTTP_PORT 設定，預設 18088。
- API 的 8080、MySQL 的 3306 與 Redis 的 6379 僅在 Compose 網路內可用。

Nginx 將 /api/... 原樣代理給 Spring Boot，瀏覽器使用相對 /api 路徑。Web 的查詢建立入口保留來源 IP 限流：每分鐘 5 次、額外 burst 2 次，超量回 429、RATE_LIMITED 與 Retry-After。前置代理必須傳送正確的用戶端 IP 與 HTTPS scheme；Nginx 只信任指定私有網路的 forwarded IP。限流是基本濫用保護，不取代 WAF 或帳號級配額。

主機公開網站時，讓 Caddy 或既有 HTTPS reverse proxy 終止 TLS，並只反向代理至 loopback：

~~~caddy
parking.example.com {
    reverse_proxy 127.0.0.1:18088
}
~~~

DNS 應指向主機，防火牆只開放必要的 80／443。不要把 Compose 的 Web port 改綁 0.0.0.0；HTTP port 是主機內部入口，不是公開 TLS port。正式網站使用 HTTPS，因瀏覽器定位等能力需要安全環境。

## 環境變數與機密

在 repo 根目錄建立私有 .env。Windows PowerShell 可在檔案不存在時複製範例：

~~~powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
~~~

Linux 主機可使用：

~~~bash
cp -n .env.example .env
chmod 600 .env
$EDITOR .env
~~~

Compose 會要求 PARKING_DB_PASSWORD 與 MYSQL_ROOT_PASSWORD 非空；沒有提供時 config／up 會立即失敗。將兩者改成彼此不同的長隨機值，例如各自執行 openssl rand -hex 32。範例檔的資料庫字串只供本機 config／CI 測試，不能用於部署。不要提交 .env、資料庫備份或含完整設定展開值的 log。

變數用途：

- PARKING_DB_PASSWORD：API 與 MySQL 應用帳號共用的資料庫密碼。
- MYSQL_ROOT_PASSWORD：MySQL root 初始化密碼。
- VITE_GOOGLE_MAPS_BROWSER_API_KEY：只作 Web image build argument，會出現在可下載的前端 bundle。只用受限 browser key，限制正式網域 referrer 與實際使用的 Google API。留空時 Places 搜尋顯示不可用；已驗證的預設起點與目的地仍可送出查詢，畫面不提供座標輸入。
- GOOGLE_ROUTES_SERVER_API_KEY：只注入 API runtime，不傳給 Web build。
- GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED：預設 false。確認 Google 額度、預算與 server key 限制後才可手動設 true；停用或無有效 key 時 ETA 應明確顯示失敗／不可估算，機率仍維持 null 與 INSUFFICIENT_DATA。
- WEB_HTTP_PORT：Web loopback HTTP port，預設 18088。
- GOOGLE_ROUTES_TIMEOUT、GOOGLE_ROUTES_ROUTING_PREFERENCE：後端 Routes 設定。

Compose 解析可能包含環境變數值，因此檢查只使用 quiet 模式：

~~~bash
docker compose --env-file .env config --quiet
~~~

不要把 docker compose config 的完整輸出貼到 CI、Issue、PR 或聊天。

## 狀態、記錄與停止

~~~bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=100 web api mysql redis
docker compose --env-file .env logs -f api
~~~

以 Web 同源路徑檢查 API：

~~~bash
curl -fsS http://127.0.0.1:18088/api/health
~~~

停止與移除容器／網路時，MySQL volume 仍保留：

~~~bash
docker compose --env-file .env stop
docker compose --env-file .env down
~~~

正式資料庫不可使用 down -v，也不要對沒有辨識 project 與資料用途的 volume 執行刪除。若曾使用舊 Demo project，先用 docker volume ls、docker volume inspect 與 container mounts 核對 project／volume 標籤。舊 parking-demo 或 s3022demo 的 volume 不會自動遷移至新的 taiwan-parking-forecast_mysql-data；未確認備份有效與資料已移轉／可捨棄前，必須保留。

## MySQL 備份與隔離還原

先將備份寫入 Git 工作目錄以外的受保護目錄；備份含資料與個資風險，應依部署環境政策加密及異地保存。以下命令供 Linux 主機或 Git Bash 使用，請先將路徑改成部署主機的私有備份位置：

~~~bash
BACKUP_DIR=/srv/private-backups/taiwan-parking-forecast
umask 077
mkdir -p "$BACKUP_DIR"
docker compose --env-file .env exec -T mysql sh -c 'mysqldump --single-transaction --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" taiwan_parking_forecast' > "$BACKUP_DIR/taiwan-parking-$(date +%Y%m%d%H%M%S).sql"
~~~

還原演練指定全新 project name，使用獨立的 taiwan-parking-forecast-restore_mysql-data volume，不會連到預設主系統 volume：

~~~bash
BACKUP=/srv/private-backups/taiwan-parking-forecast/選擇一份備份.sql
RESTORE_PROJECT=taiwan-parking-forecast-restore
docker compose -p "$RESTORE_PROJECT" --env-file .env up -d mysql
until docker compose -p "$RESTORE_PROJECT" --env-file .env exec -T mysql sh -c 'mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD"' >/dev/null 2>&1; do sleep 2; done
docker compose -p "$RESTORE_PROJECT" --env-file .env exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" taiwan_parking_forecast' < "$BACKUP"
docker compose -p "$RESTORE_PROJECT" --env-file .env exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES" taiwan_parking_forecast'
~~~

人工核對還原結果後，以 docker volume inspect 確認 restore volume 標籤及名稱。不要將 restore project 改名為 taiwan-parking-forecast，不要將 restore volume 掛入正式服務；完成後可 stop/down 隔離容器，但保留資料 volume 直到明確確認可刪除。備份／還原目前只有可重現步驟，實際演練須另外記錄。

## 版本發布與回退

部署前記錄目前完整 commit SHA 並先備份資料。以已審查的 SHA 部署：

~~~bash
git fetch origin
git checkout <已審查的完整 commit SHA>
docker compose --env-file .env up -d --build
~~~

回退時 checkout 上一個可用 SHA，再以相同 Compose project name、.env 與啟動命令重建。MySQL volume 會保留；若新版本包含 Flyway schema migration，舊版程式可能無法讀取新版 schema，必須先檢查 migration 相容性，必要時還原至隔離 volume 後再安排資料回復。回退不等同把舊 volume 或 restore volume 直接掛入服務。

## Smoke 與目前限制

本機 smoke 以唯一 Web URL 的同源 /api 執行：

~~~powershell
./tools/parking_query_flow_smoke.ps1
./tools/parking_catalog_smoke.ps1
~~~

Smoke 會建立停車查詢並檢查 500 公尺、來源快照、資料不足、預測機率為 null、限流／刷新契約。Routes 停用或缺少有效 key 時 ETA 降級屬預期結果，不會單獨判定 Compose 部署失敗；有意啟用真 Routes 時才將 ETA READY 列為額外驗收。Smoke 可能觸發官方來源的受控刷新，請勿密集重複執行。

這兩支 smoke 需要審查後的目錄 mapping 及已發布 snapshot；全新空資料庫尚無這些前置資料時，snapshot 斷言會失敗。應將其報告為「來源資料前置條件未滿足」，不要混同 Web／API／MySQL／Redis health 或同源代理失敗。不得為了通過 smoke 而沿用未盤點的舊 Demo named volume。

以 `.env.example` 建置的本次隔離 image 未配置 Google browser key，可驗證預設位置、查詢 API、Places 不可用提示與手機無水平溢出；本機既有 key 尚未確認為受限測試 key，真實 Places 候選及選取未執行，待取得受限測試 key 後在可連線瀏覽器人工驗收。遠端 DNS／TLS、主機反向代理、正式 key 限制、真 ETA、官方來源連線、備份還原演練及實際版本回退都不能只靠 Compose build 宣稱通過。
