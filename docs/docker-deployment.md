# Docker Compose 部署

更新日期：2026-10-24。狀態：本機開發設定保留；S3-024 已提供可重現的單機 DEMO 部署設定與 smoke 步驟。沒有實際主機、網域與部署金鑰，因此遠端 HTTPS、正式 ETA、備份還原及外部瀏覽器驗收仍為 **BLOCKED／未驗收**，不可視為已上線。

## 本機開發

沿用既有開發 Compose，不需建立 DEMO 環境檔：

~~~bash
docker compose up --build
~~~

- Web：http://localhost:5173
- API：http://localhost:18080；前端 local build 保留此直連 fallback。
- MySQL：主機埠 3307，資料在 mysql-data volume。
- Redis：主機埠 6379。

本機 Compose 的映射埠與占位密碼只供開發，勿用於遠端。既有 docker-compose.yml 未因 S3-024 改變。

## 單機 DEMO 架構

遠端使用獨立 docker-compose.demo.yml，避免覆蓋本機設定：

- 只有 Nginx Web 對主機發佈埠，且綁定 127.0.0.1:18088（可用 DEMO_HTTP_PORT 更換）。部署主機上的 Caddy 或既有 HTTPS 反向代理轉送至此 loopback 入口；TLS 憑證與自動續期由主機反向代理管理。
- 建議 Caddy 設定：

~~~caddy
demo.example.com {
    reverse_proxy 127.0.0.1:18088
}
~~~

  將 demo.example.com 換成實際網域，DNS 指向主機，防火牆只允許必要的 80／443 入口。不要把 Compose 的 loopback 埠改成 0.0.0.0 公開映射。反向代理需正確設定／覆寫 X-Forwarded-For 與 X-Forwarded-Proto；Nginx 只信任私有網路來源的 forwarded IP。
- API、MySQL、Redis 沒有主機埠映射，只能由 Compose 網路內部存取。web 的 /api/... 代理使用不帶 URI 的 proxy_pass http://api:8080，完整保留 /api/v1/... 路徑。
- MySQL 資料保存在 Compose named volume parking-demo_mysql-data（專案名固定為 parking-demo 時）。Redis 僅供短期查詢狀態與共享鎖；來源冷卻仍由既有 MySQL／Redis 協調邏輯控制為五分鐘，不另建排程。
- 查詢建立入口以來源 IP 套用 Nginx 限流：每分鐘 5 次，另允許最多 2 次立即 burst；超過回 HTTP 429、JSON RATE_LIMITED 與 Retry-After: 60。這是 DEMO 的基本濫用底線，不是帳號級配額或完整 WAF。不要把前置代理設定成共用錯誤的 client IP，否則所有用戶會共用同一限額。

## 機密與啟動

在部署主機的 repo checkout 目錄建立私有環境檔。不要將 .env.demo、資料庫備份或含密碼的 Compose 輸出提交或貼到 Issue／CI log。

~~~bash
cp -n .env.demo.example .env.demo
chmod 600 .env.demo
$EDITOR .env.demo
~~~

至少將兩組資料庫密碼替換成不同的長隨機值（可分別執行 openssl rand -hex 32 產生）。cp -n 會保留既有環境檔；若已有 .env.demo，請只編輯需要調整的值。變數使用方式：

- PARKING_DB_PASSWORD 與 MYSQL_ROOT_PASSWORD：只在部署環境；API 容器取得應用帳號密碼，MySQL 容器取得初始化密碼。
- GOOGLE_ROUTES_SERVER_API_KEY：只注入 API 容器，不傳入前端 build。沒有有效 key 時留空並維持 GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=false；只有完成預算／配額確認及 key 的 server-side 限制後才啟用 Routes 呼叫。若密碼或 key 含美元符號，請依 Compose dotenv 語法用單引號包住整個值。
- VITE_GOOGLE_MAPS_BROWSER_API_KEY：瀏覽器可見且會包含在 web image／bundle。於 Google Cloud 僅允許正式 HTTPS 網域 referrer，並限制到實際使用的 Maps JavaScript／Places API；不可把它當成秘密。
- DEMO_HTTP_PORT：主機 loopback 使用的 HTTP 埠，不是公開 TLS 埠。

Compose 展開設定會包含環境變數值，執行 config 時不要把完整輸出公開。先以範例假值確認設定，再在已填好機密的主機執行：

~~~bash
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml config --quiet
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml up -d --build
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml ps
~~~

Compose build 會把瀏覽器 Maps key 編入 web 靜態 bundle；它只能是已限制 referrer／API 的 browser key。Routes server key 與資料庫密碼是 runtime environment，沒有傳給 Docker build args、Dockerfile、Git 或前端。Redis／MySQL 官方來源的既有刷新協調保持不變。

## 健康狀態、log 與停止

~~~bash
# 透過 HTTPS 入口檢查 Spring API 健康端點
curl -fsS https://demo.example.com/api/health

# 看服務 health 與個別服務 log
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml ps
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml logs --tail=100 web api mysql redis
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml logs -f api

# 暫停或移除容器／網路；named volume 會保留
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml stop
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml down
~~~

不要在正式 DEMO 使用 down -v，它會刪除 MySQL volume。

## MySQL 備份與隔離還原

先用唯讀資料庫傾印備份到受保護目錄。備份含資料，不得提交 Git；依部署主機的磁碟與法規政策另行加密／異地保存。

~~~bash
umask 077
mkdir -p backups
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml exec -T mysql sh -c 'mysqldump --single-transaction --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" taiwan_parking_forecast' > "backups/parking-demo-$(date +%Y%m%d%H%M%S).sql"
~~~

還原演練使用另一個 Compose project name，確保建立 parking-demo-restore_mysql-data 隔離 volume，不會掛到正式資料：

~~~bash
BACKUP=backups/選擇一份備份.sql
RESTORE_PROJECT=parking-demo-restore
docker compose -p "$RESTORE_PROJECT" --env-file .env.demo -f docker-compose.demo.yml up -d mysql
until docker compose -p "$RESTORE_PROJECT" --env-file .env.demo -f docker-compose.demo.yml exec -T mysql sh -c 'mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD"' >/dev/null 2>&1; do sleep 2; done
docker compose -p "$RESTORE_PROJECT" --env-file .env.demo -f docker-compose.demo.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" taiwan_parking_forecast' < "$BACKUP"
# 完成隔離驗證後才移除 restore 專案與其專用 volume
docker compose -p "$RESTORE_PROJECT" --env-file .env.demo -f docker-compose.demo.yml down -v
~~~

檢查還原資料時，不要把 restore project 改名為 parking-demo，也不要對正式專案使用 down -v。S3-024 尚未取得遠端主機，以上備份／還原程序僅有文件與 Compose volume 設計，尚無實際演練證據。

## 版本發布與回退

先記錄目前部署的 commit SHA 並在升版前備份。部署某個已審查 SHA 的步驟：

~~~bash
git fetch origin
git checkout <要部署的完整 commit SHA>
docker compose -p parking-demo --env-file .env.demo -f docker-compose.demo.yml up -d --build
~~~

回退時 checkout 上一個已知可用 SHA，再以相同 Compose 命令重建；named volume 會保留。若新版 Flyway migration 已改變資料結構，舊版程式未必能讀新 schema；需先評估 migration 相容性。必要時將升版前備份還原至隔離 volume，驗證後再規劃資料回復窗口，不能直接把隔離 volume 指向正式服務。

## 最小遠端 Smoke

只對已設定 TLS、API 與資料來源的 demo 網域執行。此流程建立一筆查詢並送出兩次人工刷新；刷新沿用既有官方來源呼叫與五分鐘冷卻，請勿為了 smoke 重複建立查詢。需要 curl、jq；若 Routes server key 與外部呼叫已由部署者啟用，單次建立查詢會消耗 Routes 額度。

~~~bash
BASE_URL=https://demo.example.com
curl -fsS "$BASE_URL/api/health" | jq .
REQUEST_TOKEN="smoke-$(date +%s)"
QUERY=$(curl -fsS -X POST "$BASE_URL/api/v1/parking-queries" \
  -H 'Content-Type: application/json' \
  --data-binary "{\"requestToken\":\"$REQUEST_TOKEN\",\"origin\":{\"label\":\"測試起點\",\"latitude\":25.04,\"longitude\":121.45},\"destination\":{\"label\":\"新莊體育館\",\"latitude\":25.036,\"longitude\":121.4505},\"departure\":{\"type\":\"NOW\"}}")
echo "$QUERY" | jq -e '.area.radiusMeters == 500 and .predictions.status == "INSUFFICIENT_DATA" and .predictions.roadside.probability == null and all(.predictions.offStreet[]; .probability == null)'
echo "$QUERY" | jq '{eta, roadside: {status: .roadside.status, currentlyAvailableSpaces: .roadside.currentlyAvailableSpaces, systemCollectedAt: .roadside.systemCollectedAt}, offStreet: {status: .offStreet.status, systemCollectedAt: .offStreet.systemCollectedAt}, predictions: {status: .predictions.status, roadside: .predictions.roadside, offStreet: .predictions.offStreet}}'
if [ "${EXPECT_ETA:-false}" = true ]; then echo "$QUERY" | jq -e '.eta.status == "READY"'; else echo "ETA 未驗收：未啟用有效 Routes key。"; fi
QUERY_ID=$(echo "$QUERY" | jq -r '.query.queryId')
curl -fsS -X POST "$BASE_URL/api/v1/parking-queries/$QUERY_ID/refresh?trigger=MANUAL_REFRESH" | jq '{roadside: .refresh.roadside.action, offStreet: .refresh.offStreet.action}'
curl -fsS -X POST "$BASE_URL/api/v1/parking-queries/$QUERY_ID/refresh?trigger=MANUAL_REFRESH" | jq -e '(.refresh.roadside.action == "COOLDOWN" or .refresh.roadside.action == "JOINED_RUNNING_REFRESH") and (.refresh.offStreet.action == "COOLDOWN" or .refresh.offStreet.action == "JOINED_RUNNING_REFRESH")'
~~~

驗收時記錄：API health 為 UP、查詢半徑 500m、兩來源目前狀態及時間（若來源尚未發布快照，應如實顯示未知／無資料）、預測狀態為 INSUFFICIENT_DATA 且機率為 null。Routes key 有效且已啟用時須另外確認 eta.status=READY；無 key 或 Routes 呼叫停用時，記為 ETA 未驗收，不可宣稱真 ETA 成功。第一次刷新會嘗試官方來源更新；第二次應顯示共享 COOLDOWN（併入既有刷新時可見 JOINED_RUNNING_REFRESH）。HTTP 429 的重試提示由 Nginx 提供，勿為驗證刻意大量呼叫。

## 實際狀態與限制

- 已實作但未經遠端主機驗收：隔離 Compose、MySQL named volume、loopback web 入口、同源 API proxy、查詢建立 IP 限流、資料庫密碼必要環境變數與 Routes key 僅後端注入。
- 本機開發 Compose 行為保留；本機 Docker UI、Google Routes 與來源刷新既有證據詳見 docs/current-state.md 舊紀錄。
- 尚未驗收／BLOCKED：真實主機與網域、HTTPS 憑證與反向代理轉送、browser key referrer/API restriction、Routes server key 與真 ETA、遠端來源 API 網路連線、MySQL 備份／隔離還原實測、遠端 smoke、外部瀏覽器及發佈回退。
- S3-024 交付不代表上線；正式公開 demo 前仍需部署資源與人工驗收。
