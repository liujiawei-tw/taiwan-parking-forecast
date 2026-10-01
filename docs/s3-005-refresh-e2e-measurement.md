# S3-005 官方來源端到端刷新寫入與發布量測

更新日期：2026-10-02。

## 目的

本文件記錄路邊與路外各一輪可重現的真實官方來源端到端量測，驗證從新北市官方 Open Data HTTP 擷取、正規化、品質量測、DB 發布交易，到查詢 `PUBLISHED` snapshot 的完整流程。

本輪量測只保存摘要，不保存官方原始 JSON、完整 request／response body、API key 或敏感設定。

## 執行方式

量測使用一次性 Docker Maven 容器執行 Spring Boot command runner；資料庫使用 local profile 的暫時 H2，未啟動或關閉既有 Docker Compose 服務。

路邊量測指令：

```powershell
docker run --rm -v "C:\Users\z0976\.m2:/root/.m2" -v "C:\Users\z0976\OneDrive\桌面\dev-side\taiwan-parking-forecast:/workspace" -w /workspace/backend maven:3.9-eclipse-temurin-21 mvn -q spring-boot:run "-Dspring-boot.run.arguments=--parking.refresh.measurement.command.enabled=true --parking.refresh.measurement.command.datasets[0]=NTPC_ROADSIDE_SPOT --parking.refresh.page-size=40000 --parking.refresh.max-pages=1 --spring.main.banner-mode=off"
```

路外量測指令：

```powershell
docker run --rm -v "C:\Users\z0976\.m2:/root/.m2" -v "C:\Users\z0976\OneDrive\桌面\dev-side\taiwan-parking-forecast:/workspace" -w /workspace/backend maven:3.9-eclipse-temurin-21 mvn -q spring-boot:run "-Dspring-boot.run.arguments=--parking.refresh.measurement.command.enabled=true --parking.refresh.measurement.command.datasets[0]=NTPC_OFF_STREET_AVAILABILITY --parking.refresh.page-size=1000 --parking.refresh.max-pages=3 --spring.main.banner-mode=off"
```

## 量測結果

| 項目 | 路邊格位即時狀態 | 路外即時剩餘車位 |
| --- | --- | --- |
| dataset | `NTPC_ROADSIDE_SPOT` | `NTPC_OFF_STREET_AVAILABILITY` |
| 執行時間 | 2026-10-02 台北時間 | 2026-10-02 台北時間 |
| page-size / max-pages | 40000 / 1 | 1000 / 3 |
| HTTP requests | 1 | 2 |
| pages | 1 | 2 |
| response bytes | 11,270,904 | 50,608 |
| normalized records | 30,941 | 1,405 |
| published | true | true |
| ingestion run ID | 1 | 1 |
| snapshot ID | 1 | 1 |
| published observations | 7 | 3 |
| unmapped observations | 30,934 | 1,402 |
| failed observations | 0 | 0 |
| HTTP latency | 4,692 ms | 872 ms |
| normalize latency | 39 ms | 8 ms |
| measurement latency | 1,577 ms | 1,544 ms |
| publish transaction latency | 3,044 ms | 577 ms |
| total refresh latency | 9,353 ms | 3,003 ms |
| verified parking place IDs | 7 | 3 |
| query read latency | 53 ms | 40 ms |
| query status | `UNKNOWN` | `READY` |
| query observations | 7 | 3 |

原始 command runner 摘要：

```text
refreshE2eMeasurement{dataset=NTPC_ROADSIDE_SPOT, published=true, ingestionRunId=1, snapshotId=1, httpRequests=1, pages=1, responseBytes=11270904, normalizedRecords=30941, message=normalized=30941, published=7, unmapped=30934, failedObservations=0, stageTimings{httpMs=4692, normalizeMs=39, measurementMs=1577, publishTransactionMs=3044, totalMs=9353}, verifiedParkingPlaceIds=7, queryReadMs=53, queryStatus=UNKNOWN, queryObservations=7}
refreshE2eMeasurement{dataset=NTPC_OFF_STREET_AVAILABILITY, published=true, ingestionRunId=1, snapshotId=1, httpRequests=2, pages=2, responseBytes=50608, normalizedRecords=1405, message=normalized=1405, published=3, unmapped=1402, failedObservations=0, stageTimings{httpMs=872, normalizeMs=8, measurementMs=1544, publishTransactionMs=577, totalMs=3003}, verifiedParkingPlaceIds=3, queryReadMs=40, queryStatus=READY, queryObservations=3}
```

## 可分開記錄的拒絕與失敗

| 類型 | 目前證據 |
| --- | --- |
| 發布成功 | 本文件兩輪真實 E2E 量測皆 `published=true`，並可讀回 `PUBLISHED` snapshot。 |
| 缺頁／來源超過頁數上限 | `NtpcRealtimeSourceRefreshServiceTest.refusesToPublishWhenConfiguredPageLimitWouldTruncateTheSource` 覆蓋 `pageTraversalCompleted=false` 時拒絕發布，且訊息含 pages、HTTP requests、page size、max pages 與 response bytes。 |
| 重複與來源欄位異常 | #42 的 `docs/realtime-source-measurement.md` 已分開記錄重複 source ID、缺 source ID、來源欄位異常、負值與 mapping 覆蓋。 |
| 部分 mapping | 本文件兩輪量測分別記錄 `published` 與 `unmapped`，路邊 7／30,941 已 mapping、路外 3／1,405 已 mapping。 |
| 品質拒絕不覆蓋既有 PUBLISHED | `AvailabilitySnapshotPublishingServiceTest.rejectionDoesNotReplaceExistingActiveSnapshotWhenNoObservationCanBeMapped` 覆蓋品質拒絕保留既有 active snapshot。 |
| 一輪來源失敗不覆蓋既有 PUBLISHED | 來源刷新在擷取或缺頁階段丟出例外時不呼叫 publishing service；既有 PUBLISHED snapshot 不會被該輪失敗替換。 |

## 設定依據與暫定門檻

- 路邊低頻驗證可先使用 `page-size=40000`、`max-pages=1`：本輪真實來源 30,941 rows 可在 1 頁取完，HTTP 約 4.7 秒、總刷新約 9.4 秒。但這只是單輪量測，不是 SLA。
- 路外低頻驗證可先使用 `page-size=1000`、`max-pages=3`：本輪真實來源 1,405 rows 以 2 頁取完，HTTP 約 0.9 秒、總刷新約 3.0 秒。
- 5 分鐘資料過期與刷新 cooldown 仍維持 MVP 暫定門檻；本輪量測不足以宣稱正式服務承諾。
- 前景頁等待上限、失敗冷卻時間、品質比例門檻與正式 p95 目標仍未量到，必須維持待驗證狀態。

## 已知限制

- 本輪使用暫時 H2 DB，因此 ingestion run ID 與 snapshot ID 只代表該次量測容器，不代表正式環境。
- 目前 verified mapping 仍是小樣本：路邊 7 筆、路外 3 筆；不能宣稱完整新莊 catalog 或完整 300 公尺區域覆蓋。
- command runner 直接呼叫 refresh service 做一次量測，不代表已驗證多實例 Redis 鎖與 cooldown 的端到端競爭情境。
- 路邊 query status 為 `UNKNOWN`，代表目前可讀回已發布觀測，但這批 mapped observations 的可用狀態不足以構成 `READY`；不可把它解讀為抵達時仍有位。
- 本輪不訓練模型、不輸出預測機率，也不把來源觀測冒充模型估計。
