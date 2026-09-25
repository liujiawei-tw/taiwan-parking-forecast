# Redis 設計草案（Sprint 1）

更新日期：2026-09-25。狀態：第一版職責與 key 類型已決定，實際 TTL、序列化格式與容量上限待實作前量測；沒有主系統 coding。

## 設計原則

- MySQL 是停車目錄、已發布快照、來源冷卻、用量紀錄與可訓練歷史的事實來源。
- Redis 只保存短期互動狀態、共用刷新協調、短鎖與可重建快取。
- Redis 的 TTL 只代表快取或互動狀態何時失效，不代表官方資料的新鮮度。
- Redis 清空、重啟或某個 key 過期時，不得造成 MySQL 已發布快照消失，也不得讓半套資料變成可查詢資料。
- 儘量避免快取全市大量原始資料；快取內容應是經 MySQL 發布、可重建且有版本的結果。

## 第一版 Redis key 類型

| key 類型 | 範例命名 | 用途 | 可否從 MySQL 重建 | 建議 TTL 初值 |
| --- | --- | --- | --- | --- |
| 查詢狀態 | `pf:query:{queryId}` | 保存一次使用者行程查詢的短期進度、ETA 結果、區塊狀態、最近組好的 response 摘要 | 部分可重建；Google ETA 與使用者輸入若未持久化，過期後要求重新規劃 | 2 小時候選，待產品驗證 |
| 防重送 | `pf:request-token:{clientKey}:{requestToken}` | 避免連點或網路重試造成重複建立 `queryId` 和重複 Google ETA 工作 | 不重建；過期後重新規劃 | 10 至 30 分鐘候選 |
| 來源刷新鎖 | `pf:refresh-lock:{sourceType}` | 同一官方來源同一時間只允許一輪擷取；多使用者共用 | 可由 MySQL `source_state` 判斷恢復，但鎖本身不可永久存在 | 略長於來源工作上限，例如 90 秒候選 |
| 來源刷新進度 | `pf:refresh-job:{sourceType}:{jobId}` | 讓查詢／手動更新知道該來源正在刷新、開始時間、最後心跳、目前狀態 | 可由 `ingestion_run` 與 `source_state` 補判斷 | 10 至 30 分鐘候選 |
| 已組裝查詢結果快取 | `pf:nearby-result:{hash}` | 快取同一目的地圓、同一已發布快照版本下組好的附近結果，降低重複查 MySQL 與距離計算 | 可以 | 短 TTL，且 key 需含 snapshotId 或版本 |
| 來源快照摘要快取 | `pf:snapshot-summary:{sourceType}:{snapshotId}` | 快取已發布快照的摘要，例如取得時間、筆數、品質、目前可顯示數量 | 可以 | 跟隨 snapshot 版本，可較長但仍可失效 |

key prefix `pf` 是 parking forecast 的縮寫，可在實作時透過設定調整。`sourceType` 第一版至少區分 `NTPC_ROADSIDE_SPOT` 與 `NTPC_OFF_STREET_AVAILABILITY`。

## `queryId` 狀態保存內容

`pf:query:{queryId}` 不應保存完整個資或長期歷史。第一版可保存：

- 查詢生命週期：`ACCEPTED`、`RUNNING`、`PARTIAL`、`COMPLETED`、`EXPIRED`、`FAILED`。
- 使用者本次目的地座標、顯示名稱、出發模式與後端實際使用的出發時間。
- Google ETA 區塊狀態、結果或失敗原因。
- 路邊與路外區塊狀態、使用到的 `snapshotId`、來源時間與失敗原因。
- 預測區塊狀態、模型版本、目標時間、資料不足原因。
- `hasPendingWork`、`nextPollAfterSeconds`、`expiresAt` 等前端輪詢控制欄位。

Redis 中的 `queryId` 不是車位預約，也不是長期查詢記錄。過期後前端應提示重新規劃；後端不應因為 Redis 沒有舊 query，就自動重呼叫 Google Routes 或官方車位來源。

## 刷新鎖與失敗恢復

來源刷新鎖用於降低多人同時查詢造成的重複官方呼叫。取得鎖前必須先檢查 MySQL `source_state` 的 `next_allowed_refresh_at`、預算、失敗冷卻與是否已有可用已發布快照；Redis 鎖不能繞過 MySQL 的限制。

建議流程：

1. 查詢或手動更新發現某來源需要刷新。
2. 讀 MySQL `source_state`，若仍在冷卻或預算阻擋，直接回原因與可再試時間。
3. 嘗試以 Redis `SET NX PX` 類型的短鎖取得 `pf:refresh-lock:{sourceType}`。
4. 取得鎖者建立 `ingestion_run`，進行官方擷取、候選快照寫入與品質驗證。
5. 成功發布 MySQL 快照後，失效或更新相關 Redis 快取。
6. 釋放鎖；若服務中斷，鎖靠 TTL 自動過期，MySQL 未發布候選不會成為查詢資料。

若 Redis 鎖過期但擷取工作仍在跑，可能產生第二輪擷取風險。第一版可用工作最後期限與 `ingestion_run` 狀態降低風險；正式多實例前需補足 owner token、心跳或資料庫層狀態檢查。這是面試時可以說明的風險與後續改進點。

## 快取失效規則

- MySQL 快照發布成功後，才更新或刪除相關 `nearby-result` 與 `snapshot-summary` 快取。
- 快取 key 必須包含來源快照版本或能對應到版本；不能只有目的地座標，否則新快照發布後可能讀到舊結果。
- Redis 快取命中時仍要回傳 MySQL 中記錄的來源時間與快照版本，不用 Redis 寫入時間冒充資料時間。
- 快取失效或 Redis 故障時，後端可回 MySQL 已發布快照重新組裝結果；若 MySQL 資料本身過期，仍按資料新鮮度規則顯示過期。

## 不放進 Redis 的資料

- 完整官方原始 response。
- 唯一的車位真相或唯一的最新快照。
- 月度或長期官方 API 用量事實。
- 可供模型訓練的標籤資料。
- 未發布候選快照或品質未通過資料。
- Google API 金鑰、TDX Client Secret 或任何敏感憑證。

## 待實測與待定

- `queryId` 是否 2 小時足夠；未來出發查詢是否需要更長但低敏感度的恢復方式。
- 防重送 token 與使用者／瀏覽器識別如何設計，避免保存不必要個資。
- 來源刷新工作 p95 耗時，決定 lock TTL、工作最後期限與心跳需求。
- 附近結果快取的命中率、記憶體大小、key 粒度與是否值得第一版實作。
- 單機 Docker Compose 下 Redis persistence 是否開啟；第一版即使不開啟，也不能影響 MySQL 已發布快照。
