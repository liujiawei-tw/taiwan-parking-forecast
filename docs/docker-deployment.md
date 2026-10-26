# Docker Compose 部署

更新日期：2026-10-26。狀態：唯一正式設定為根目錄的 docker-compose.yml；本機、作品展示與單機部署共用四個服務、網路隔離、同源 API 與 MySQL 持久化。沒有遠端主機與正式金鑰時，服務仍可按降級契約運行，但不代表 Routes ETA 或停車機率已驗收。

## 唯一 Compose 與網路

使用同一份 docker-compose.yml 啟動：

~~~bash
docker compose --env-file .env up -d --build
~~~

Compose project name 固定為 taiwan-parking-forecast，MySQL named volume 為 taiwan-parking-forecast_mysql-data。此名稱沿用現有主系統資料 volume；Compose stop、down 與 image 重建都不會刪除該 volume。API、MySQL、Redis 不發布主機埠，只有 Web 可從主機 loopback 連線：

- Web：http://127.0.0.1:8080
- 同源 API health：http://127.0.0.1:8080/api/health
- Web 主機埠可用 WEB_HTTP_PORT 設定，預設 8080。
- API 的 8080、MySQL 的 3306 與 Redis 的 6379 僅在 Compose 網路內可用。

Nginx 將 /api/... 原樣代理給 Spring Boot，瀏覽器使用相對 /api 路徑。Web 的查詢建立入口保留來源 IP 限流：每分鐘 5 次、額外 burst 2 次，超量回 429、RATE_LIMITED 與 Retry-After。前置代理必須傳送正確的用戶端 IP 與 HTTPS scheme；Nginx 只信任指定私有網路的 forwarded IP。限流是基本濫用保護，不取代 WAF 或帳號級配額。

代理來源資訊依下列邊界處理：Nginx 將瀏覽器原始 `Host`（含非預設 port）傳給 Spring；一般 HTTP 不額外送 `X-Forwarded-Proto`，避免 Spring 以預設 port 80 覆蓋原始 port。只有連線來源位於既有 `set_real_ip_from` 私有網段、且其 `X-Forwarded-Proto` 值恰為 `https` 時，Nginx 才將 `https` 傳給 Spring；此時若原始 Host 含明確 port，Nginx 會從 Host 合成對應的 `X-Forwarded-Port`，否則清除該標頭並由 Spring 使用 HTTPS 預設 port。`X-Forwarded-Host`、`X-Forwarded-Prefix` 與標準 `Forwarded` 一律由 Nginx 清除；用戶端提供的 `X-Forwarded-Port` 也不會轉送。`X-Forwarded-For` 只傳 Nginx 已解析的單一用戶端位址。Spring 的 `forward-headers-strategy=framework` 因此只會處理 Nginx 正規化後的 scheme／port／client IP。HTTPS 前置代理需保留瀏覽器原始 Host，並從上述可信來源送出單一 `X-Forwarded-Proto: https`；Nginx 不接受任意 client-supplied forwarded host 或 port。

Controller 仍只允許 `http://localhost:5173` 與 `http://127.0.0.1:5173` 作為 Vite 開發來源。Docker Web 的同源請求依實際 scheme、host 與 port 判斷，不需將 `8080` 加入 CORS allowlist。此處的 8080 是主機上的 Web 入口；API 的 8080 僅在 Compose 網路內使用。

主機公開網站時，讓 Caddy 或既有 HTTPS reverse proxy 終止 TLS，並只反向代理至 loopback：

~~~caddy
parking.example.com {
    reverse_proxy 127.0.0.1:8080
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
- VITE_GOOGLE_MAPS_BROWSER_API_KEY：只作 Web image build argument，會出現在可下載的前端 bundle。只用受限 browser key，限制正式網域 referrer 與實際使用的 Google API。留空時 Places 搜尋顯示不可用；起點沒有預設座標，使用者可明確使用瀏覽器「我的位置」，畫面不提供座標輸入。
- GOOGLE_ROUTES_SERVER_API_KEY：只注入 API runtime，不傳給 Web build。
- GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED：預設 false。確認 Google 額度、預算與 server key 限制後才可手動設 true；停用或無有效 key 時 ETA 應明確顯示失敗／不可估算，機率仍維持 null 與 INSUFFICIENT_DATA。
- WEB_HTTP_PORT：Web loopback HTTP port，預設 8080。
- GOOGLE_ROUTES_TIMEOUT、GOOGLE_ROUTES_ROUTING_PREFERENCE：後端 Routes 設定。

Compose 解析可能包含環境變數值，因此檢查只使用 quiet 模式：

~~~bash
docker compose --env-file .env config --quiet
~~~

不要把 docker compose config 的完整輸出貼到 CI、Issue、PR 或聊天。

## 從舊版 MySQL volume 升級

舊版根目錄 Compose 曾以 `change-me-locally` 初始化 `parking_app`，並以 `change-root-locally` 初始化 root。MySQL image 只在空資料目錄套用 `MYSQL_PASSWORD` 與 `MYSQL_ROOT_PASSWORD`；若 volume 已存在，改 `.env` 不會自動輪替資料庫內的密碼。舊版也會建立 `root@'%'`；新版 Compose 明確設定 `MYSQL_ROOT_HOST=localhost`，只影響新建的空資料目錄，不會替既有帳號移除遠端 root。新版沿用 `taiwan-parking-forecast_mysql-data`，因此不可直接把新的隨機密碼當成已完成遷移。

先確認 project 名稱、Compose volume 名稱與資料掛載用途：本專案預設 project 是 `taiwan-parking-forecast`，主系統 volume 是 `taiwan-parking-forecast_mysql-data`。先備份目前仍可登入的資料庫，並在隔離 project 還原核對資料表及紀錄可讀。舊 MySQL 若仍在執行，使用該資料庫目前有效的帳密備份；不可先用新 `.env` 重建 MySQL，因為 MySQL 內的舊密碼尚未改變。

若舊 MySQL 已停止，先在 repo 以外的受保護路徑建立私有 `.env.legacy`，只填入該既有 volume 目前有效的 `PARKING_DB_PASSWORD` 與 `MYSQL_ROOT_PASSWORD`。若從未輪替，使用該部署版本的初始帳密；不要假設新版 `.env` 的值已套用，也不要將帳密貼入終端記錄或 CI。不要提交此檔案。再次檢查上述 project 與 volume 身分後，只啟動 MySQL：

~~~bash
docker compose --env-file /secure/path/.env.legacy up -d mysql
docker compose --env-file /secure/path/.env.legacy ps mysql
~~~

等 MySQL healthcheck 通過後，以 `.env.legacy` 中目前有效的帳密完成下方備份；備份成功並在隔離 project 還原核對前，不要開始密碼輪替。如此即使舊服務容器已停止，備份仍會由使用正確舊認證的 MySQL 執行。還原隔離資料庫時使用包含新隨機密碼的另一份私有環境檔，不能把正式 volume 掛入 restore project。

完成備份和還原核對後，建立包含彼此不同新隨機密碼的私有 `.env`。若原 MySQL 使用 `.env.legacy` 啟動，以新 `.env` 只重建 MySQL service，API 仍保持停止：

~~~bash
docker compose --env-file .env up -d mysql
~~~

容器會沿用原 volume；切換 `.env` 後，在輪替完成前 healthcheck 可能尚未通過。透過本機 socket 使用舊 root 密碼登入，並將 `REPLACE_...` 替換為新 `.env` 的值。關閉 MySQL client history，避免將輪替語句寫入 client history：

~~~bash
docker compose --env-file .env exec -e MYSQL_HISTFILE=/dev/null mysql mysql -uroot -p
~~~

先盤點所有 root 帳號 Host：

~~~sql
SELECT User, Host FROM mysql.user WHERE User = 'root' ORDER BY Host;
~~~

執行帳密輪替並移除不需要的遠端 root 帳號；正式 Compose 不發布 MySQL 主機埠，API 使用 `parking_app`，不需要 `root@'%'`：

~~~sql
ALTER USER 'parking_app'@'%' IDENTIFIED BY 'REPLACE_WITH_PARKING_DB_PASSWORD';
ALTER USER 'root'@'localhost' IDENTIFIED BY 'REPLACE_WITH_MYSQL_ROOT_PASSWORD';
DROP USER IF EXISTS 'root'@'%';
~~~

如盤點到其他非 localhost 的 root 帳號，也需在繼續前移除，或依實際管理需求改為唯一的新強密碼並限制來源；不得保留仍可使用舊密碼的 root。退出 client 後以新 root 本機認證、應用帳號的新 TCP 認證、服務 healthcheck 及同源 API 驗證輪替結果；再啟動其他服務。若輪替中斷，不要刪除或重建原 volume，先檢查實際帳號與已驗證備份，再安排復原。新建的空 volume 不需執行此遷移步驟。

## 狀態、記錄與停止

~~~bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=100 web api mysql redis
docker compose --env-file .env logs -f api
~~~

以 Web 同源路徑檢查 API：

~~~bash
curl -fsS http://127.0.0.1:8080/api/health
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
COMPOSE_ENV_FILE=.env
# 舊 MySQL 已停止並以目前有效舊帳密重新啟動時，改用受保護路徑的 .env.legacy。
# COMPOSE_ENV_FILE=/secure/path/.env.legacy
umask 077
mkdir -p "$BACKUP_DIR"
docker compose --env-file "$COMPOSE_ENV_FILE" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --single-transaction --routines --triggers -uroot taiwan_parking_forecast' > "$BACKUP_DIR/taiwan-parking-$(date +%Y%m%d%H%M%S).sql"
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

資料庫密碼輪替會保留在 MySQL volume，Git 回退不會反轉帳密。已輪替密碼後，不能直接啟動仍寫死舊帳密的舊版 Compose；回退前需先確認回退版能使用目前 `.env`，或完成有備份的資料庫帳密回復，並驗證後再啟動。

## Smoke 與目前限制

本機 smoke 以唯一 Web URL 的同源 /api 執行：

~~~powershell
./tools/parking_query_flow_smoke.ps1
./tools/parking_catalog_smoke.ps1
./tools/parking_query_proxy_smoke.ps1
~~~

Smoke 會建立停車查詢並檢查 500 公尺、來源快照、資料不足、預測機率為 null、限流／刷新契約。Routes 停用或缺少有效 key 時 ETA 降級屬預期結果，不會單獨判定 Compose 部署失敗；有意啟用真 Routes 時才將 ETA READY 列為額外驗收。Smoke 可能觸發官方來源的受控刷新，請勿密集重複執行。

`parking_query_proxy_smoke.ps1` 專門對照不帶 Origin 與同源瀏覽器 Origin 的建立 POST，並驗證兩個 Vite origin 的 preflight、外部 Origin 拒絕、偽造 Forwarded 標頭無法改寫來源，以及同源 refresh 路徑可通過 CORS。另以可信上游 forwarded 標頭模擬 HTTPS 非預設 port，檢查 Nginx 對 Host port 和 client-supplied forwarded port 的處理；這不取代實際 TLS 入口驗收。它只建立兩筆短效 query、不發起官方來源 refresh 或 Google Routes；請確認 Routes 外部呼叫維持停用。

Catalog smoke 會在每次建立查詢間隔至少 13 秒，以符合 Nginx 每分鐘 5 次及 burst=2 的正式限流設定；完整資料 smoke 因此需要較長時間。

這兩支 smoke 需要審查後的目錄 mapping 及已發布 snapshot；全新空資料庫尚無這些前置資料時，snapshot 斷言會失敗。應將其報告為「來源資料前置條件未滿足」，不要混同 Web／API／MySQL／Redis health 或同源代理失敗。不得為了通過 smoke 而沿用未盤點的舊 Demo named volume。

以 `.env.example` 建置的隔離 image 當時未配置 Google browser key，可驗證預設位置、查詢 API、Places 不可用提示與手機無水平溢出；本機既有 key 尚未確認為受限測試 key，真實 Places 候選及選取未執行。2026-10-26 起點預設已依新決策移除，因此上述預設位置驗證是舊版本證據，不能代表未選起點時的新行為；新行為尚未以隔離 image 或瀏覽器驗收。遠端 DNS／TLS、主機反向代理、正式 key 限制、真 ETA、官方來源連線、備份還原演練及實際版本回退都不能只靠 Compose build 宣稱通過。
