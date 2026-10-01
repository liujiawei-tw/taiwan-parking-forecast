# 主系統官方即時來源：請求量與延遲量測

更新日期：2026-10-01。狀態：S2-021 已加入主系統量測摘要基線；S2-HARDEN-003 已補低頻真實多輪量測與暫定設定建議。本文件只針對主系統的官方即時來源；離線訓練 collector 的請求量不可充作主系統結果。

## 官方已確認的介接方式

新北市[開發指引](https://data.ntpc.gov.tw/applications)列出 CSV 完整檔及壓縮檔下載，也列出 JSON／XML 的分頁進階查詢與欄位篩選。資料集頁標示[路邊格位](https://data.ntpc.gov.tw/datasets/54a507c4-c038-41b5-bf60-bbecb9d052c6)約每 2 分鐘、[公有路外剩餘位](https://data.ntpc.gov.tw/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78)約每 3 分鐘更新。這些是官方文件敘述，不等於單輪傳輸大小、每個格位／場站的觀測年齡或主系統端到端完成時間。

| 候選取得方式 | 單輪 HTTP 數如何計 | 必須核對 |
| --- | --- | --- |
| 官方 CSV 完整檔／ZIP | 理論上每來源一個下載請求；失敗重試另計 | 是否真正包含所需全部紀錄、檔案位元組、解壓／解析與驗證時間、來源內容變動時的一致性 |
| JSON／XML 分頁 | `ceil(總筆數 ÷ 每頁筆數)` 加取得總數及重試等實際請求 | 是否有可信總筆數、最後一頁判定、跨頁重複／漏列、頁面間來源更新造成的不一致 |
| 欄位篩選後分頁 | 依實際回應頁數和額外查詢逐次計 | 路邊行政區及路外場站 ID 條件是否被**該端點**支援、是否取齊新莊目標、是否真的減少傳輸與上游負載 |

路外靜態位置目錄與動態剩餘位是兩種資料集，應分開量測和排程；靜態位置不隨每次動態刷新重抓。

## 2026-09-24 本環境探測結果

曾各對路邊、路外官方 JSON 第一頁、每頁 1 筆發起一次唯讀探測。PowerShell 與系統 curl 在 TLS 憑證建立階段失敗，回報 `SEC_E_NO_CREDENTIALS`；本機 Anaconda Python 的 `urllib` 與 `requests` 憑證驗證回報 `Missing Subject Key Identifier`。網頁查閱工具也無法開啟 API 本體。**沒有成功取得 HTTP 回應**，因此沒有可信的 HTTP 狀態、來源耗時、回應大小、總筆數、分頁數或資料欄位觀測。憑證握手失敗的約 0.4 秒不能當作 API 延遲。本輪未下載全量檔，也未停用 TLS 驗證。

## 2026-09-29 S2-021 主系統量測摘要基線

已在主系統新增 `SourceMeasurementService`，並讓 `NtpcRealtimeSourceRefreshService` 使用 NTPC client 的 page metadata 產生每輪摘要。摘要會寫入 `ingestion_run.quality_summary`，包含：dataset、started/completed time、durationMs、pageSize、maxPages、pages、HTTP attempts、retries、responseBytes、分頁是否完整、normalized row 數、source ID 數、unique source ID 數、缺 source ID、重複 source ID rows、verified mapping 數、mapped/unmapped observation 數、failed observation 數、mapping coverage 與發布建議。

目前發布建議只作可解釋量測與審查證據，不是服務承諾：

| 建議碼 | 意義 |
| --- | --- |
| `REJECT_INCOMPLETE_PAGE_TRAVERSAL` | 來源頁數超過目前 `maxPages`，不可發布不完整快照。 |
| `REJECT_NO_NORMALIZED_OBSERVATIONS` | 沒有任何可正規化觀測。 |
| `REJECT_NO_MAPPED_OBSERVATIONS` | 有觀測但沒有任何 verified mapping，可保留舊快照。 |
| `PUBLISH_WITH_PARTIAL_QUALITY_REVIEW` | 至少有部分 mapping，但存在缺 ID、重複、未 mapping 或 failed observation，須看覆蓋率與範圍門檻。 |
| `PUBLISH_MEETS_CURRENT_MVP_THRESHOLD` | 目前 MVP 檢查未發現缺口；仍不代表來源長期穩定。 |

S2-020 的低頻官方核對已取得一次資料量證據：新北市路邊完整回應約 30,941 筆，其中新莊 `areacode=65000050` 有 2,124 筆；路外靜態目錄前 1000 筆中篩到 97 筆新莊汽車場，且這 97 個 ID 能在路外即時剩餘位資料中對上。以目前 `page-size=500`、`max-pages=20` 估算，路邊全量 JSON 分頁會超過上限，主系統會拒絕發布不完整快照；路外即時資料是否能在目前上限內取齊，仍須以受控完整刷新量測確認。

S2-HARDEN-003 已補多輪低頻量測；仍尚未完成：沒有量到主系統端到端 refresh 寫入與發布耗時、沒有長期跨時段穩定性、沒有建立正式 SLA，也沒有提高刷新頻率。

## 2026-09-29 單輪真實來源量測紀錄

本輪只做路邊與路外各一輪低頻官方 API 量測，沒有保存原始 JSON，沒有提交 API 回應內容，沒有使用金鑰。時間為 Asia/Taipei。

### 路邊格位即時狀態

- Dataset：`NTPC_ROADSIDE_SPOT`
- URL：`https://data.ntpc.gov.tw/api/datasets/54A507C4-C038-41B5-BF60-BBECB9D052C6/json?page=0&size=40000`
- 開始／完成：`2026-09-29T14:11:40.4993313+08:00` 至 `2026-09-29T14:11:45.1027096+08:00`
- HTTP：1 request，HTTP 200
- 頁數：1 page；`rows=30941` 小於 requested `size=40000`，本輪視為取齊
- 延遲：4600 ms
- UTF-8 回應大小：11,270,904 bytes
- 來源 ID：30,941 rows；30,653 unique source IDs；missing source ID 0
- 重複來源 ID：288 duplicate rows；例：`107898`、`118665`、`134645`、`134652`、`137220`
- 來源欄位異常：`cellstatus`／`parkingstatus` 缺值 0；負值 0
- 新莊覆蓋：`areacode=65000050` 共 2,124 rows
- 目前 seed verified mapping：7 IDs；本輪找到 7 IDs；missing seed IDs 0
- 本輪發布建議：若只以 S2-020 小樣本 mapping 發布，屬於 `PUBLISH_WITH_PARTIAL_QUALITY_REVIEW`，因為全量 30,941 筆中只有 7 筆可 mapping；不能宣稱完整路邊快照。

### 路外即時剩餘車位

第一次以 `size=40000` 呼叫時只回 1,000 rows，因此加做分頁確認，改以 `size=1000` 取到 page 0 與 page 1；page 1 只有 407 rows，本輪視為取齊。下列數字以分頁確認結果為準。

- Dataset：`NTPC_OFF_STREET_AVAILABILITY`
- URLs：
  - `https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=0&size=1000`
  - `https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=1&size=1000`
- 分頁確認時間：`2026-09-29T14:12:55.9866532+08:00`
- HTTP：2 requests，HTTP 200
- 頁數：2 pages；page 0 = 1,000 rows，page 1 = 407 rows；本輪視為取齊
- 延遲：合計 1011 ms；page 0 = 644 ms，page 1 = 367 ms
- UTF-8 回應大小：合計 50,642 bytes；page 0 = 35,989 bytes，page 1 = 14,653 bytes
- 來源 ID：1,407 rows；1,396 unique source IDs；missing source ID 0
- 重複來源 ID：11 duplicate rows；例：`040014`、`040037`、`040068`、`060021`、`060047`
- 來源欄位異常：`AVAILABLECAR`／`AVAILABLE` 缺值 0；負值 981，其中負值仍須依官方碼表分辨「暫無即時資料」等語意，不得轉成 0
- 目前 seed verified mapping：3 IDs；本輪找到 3 IDs；missing seed IDs 0
- 本輪發布建議：若只以 S2-020 小樣本 mapping 發布，屬於 `PUBLISH_WITH_PARTIAL_QUALITY_REVIEW`，因為全量 1,407 筆中只有 3 筆可 mapping；完整路外 catalog 匯入前不可宣稱全新莊覆蓋。

### 設定建議與限制

- 現行預設 `page-size=500`、`max-pages=20` 最多只允許 10,000 rows；依本輪路邊 30,941 rows，路邊全量 refresh 會被 `REJECT_INCOMPLETE_PAGE_TRAVERSAL` 擋下，這是正確保護。
- 路外依本輪 1,407 rows，現行預設理論上可在 3 pages 內取齊；但官方平台對單頁 `size=40000` 回 1,000 rows，代表不能假設 `size` 會照請求放大。
- 若要做下一輪 controlled refresh 驗收，路邊可用「大 page size 取一頁」或「`size=1000` 多頁」兩種策略比較；路外建議以 `size=1000`、`max-pages>=3` 量測。
- 本輪只有單次量測，不足以建立 p50／p95、穩定性或長期 cooldown；不得把本數字視為服務承諾。

## 2026-10-01 S2-HARDEN-003 多輪低頻真實來源量測

本輪依 #42 驗收做路邊與路外各 5 輪低頻量測。量測時間為 Asia/Taipei，呼叫新北市官方 Open Data JSON API；只保留 HTTP、頁數、bytes、延遲、row 數與品質摘要，不保存原始 JSON，不提交完整 request／response body，不使用金鑰。

### 可重現方式

路邊每輪呼叫：

```text
GET https://data.ntpc.gov.tw/api/datasets/54A507C4-C038-41B5-BF60-BBECB9D052C6/json?page=0&size=40000
```

路外每輪呼叫兩頁：

```text
GET https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=0&size=1000
GET https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=1&size=1000
```

p50／p95 以 5 筆樣本使用 nearest-rank 計算。樣本數仍少，只能作 Sprint 2 設定依據，不是服務承諾。

### 多輪摘要

| 來源 | 輪數 | HTTP 成功 | 失敗率 | HTTP／輪 | 頁數／輪 | rows | UTF-8 bytes p50 / p95 | 延遲 p50 / p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 路邊格位即時狀態 | 5 | 5 | 0% | 1 | 1 | 30,941 | 11,270,904 / 11,270,904 | 5,279 ms / 8,575 ms |
| 路外即時剩餘車位 | 5 | 5 | 0% | 2 | 2 | 1,405 | 50,592 / 50,592 | 358 ms / 667 ms |

### 每輪紀錄

| 來源 | 輪次 | 開始時間 | HTTP 狀態 | HTTP | 頁數 | rows | bytes | elapsed ms | 完整性判斷 |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 路邊 | 1 | 2026-10-01T20:38:39.4542218+08:00 | 200 | 1 | 1 | 30,941 | 11,270,904 | 5,190 | `rows < requested size`，視為取齊 |
| 路邊 | 2 | 2026-10-01T20:39:06.6225681+08:00 | 200 | 1 | 1 | 30,941 | 11,270,904 | 5,715 | `rows < requested size`，視為取齊 |
| 路邊 | 3 | 2026-10-01T20:39:32.4347414+08:00 | 200 | 1 | 1 | 30,941 | 11,270,904 | 5,279 | `rows < requested size`，視為取齊 |
| 路邊 | 4 | 2026-10-01T20:39:57.8977823+08:00 | 200 | 1 | 1 | 30,941 | 11,270,904 | 8,575 | `rows < requested size`，視為取齊 |
| 路邊 | 5 | 2026-10-01T20:40:26.4029316+08:00 | 200 | 1 | 1 | 30,941 | 11,270,904 | 4,684 | `rows < requested size`，視為取齊 |
| 路外 | 1 | 2026-10-01T20:38:53.9730010+08:00 | 200+200 | 2 | 2 | 1,405 | 50,589 | 406 | page 1 rows 405，視為取齊 |
| 路外 | 2 | 2026-10-01T20:39:20.0169577+08:00 | 200+200 | 2 | 2 | 1,405 | 50,592 | 328 | page 1 rows 405，視為取齊 |
| 路外 | 3 | 2026-10-01T20:39:45.1421411+08:00 | 200+200 | 2 | 2 | 1,405 | 50,592 | 667 | page 1 rows 405，視為取齊 |
| 路外 | 4 | 2026-10-01T20:40:13.9847035+08:00 | 200+200 | 2 | 2 | 1,405 | 50,592 | 349 | page 1 rows 405，視為取齊 |
| 路外 | 5 | 2026-10-01T20:40:38.9636140+08:00 | 200+200 | 2 | 2 | 1,405 | 50,592 | 358 | page 1 rows 405，視為取齊 |

### 品質與 mapping 覆蓋

| 來源 | 缺 source ID | 重複 source ID rows | 重複例 | 來源欄位異常 | 目前 verified mapping 覆蓋 |
| --- | ---: | ---: | --- | --- | --- |
| 路邊 | 0 | 288 | `107898`、`118665`、`134645`、`134652`、`137220` | `cellstatus`／`parkingstatus` 缺值 0；負值 0 | S2-020 seed 7/7 皆可在官方即時回應找到；全新莊仍只有 7/2,124 verified mapping |
| 路外 | 0 | 11 | `040014`、`040037`、`040068`、`060021`、`060047` | `AVAILABLECAR` 缺值 0；負值 979，仍須依官方語意處理，不得轉成 0 | S2-020 seed 3/3 皆可在官方即時回應找到；全新莊仍只有 3/97 verified mapping |

路外即時 rows 從 2026-09-29 單輪的 1,407 變成 2026-10-01 多輪的 1,405；這是資料量波動證據，不能把單日 row 數寫死成品質門檻。

### 失敗、拒絕與發布建議

- 這 10 個來源輪次皆為 HTTP 成功，失敗率 0%；但樣本量太少，不能宣稱長期成功率。
- 本輪沒有缺頁；若未來達到 `max-pages` 且最後一頁仍滿頁，仍必須記 `REJECT_INCOMPLETE_PAGE_TRAVERSAL`。
- 以目前小樣本 mapping 發布完整來源快照仍只能是 `PUBLISH_WITH_PARTIAL_QUALITY_REVIEW`，不能宣稱完整新莊覆蓋。
- 若整輪品質拒絕，既有規則仍是不覆蓋 `source_state.active_snapshot_id` 指向的 `PUBLISHED` snapshot。

### 設定建議與暫定門檻

| 設定 | 建議 | 依據 | 狀態 |
| --- | --- | --- | --- |
| 路邊 `page-size` / `max-pages` | 若沿用 JSON 全量擷取，可先以 `page-size=40000`、`max-pages=1` 作低頻驗證候選；若改小頁分頁，至少需能容納 30,941 rows | 5 輪 `size=40000` 都以 1 request 取齊 30,941 rows | 暫定；未量到官方是否長期允許大 page size |
| 路外 `page-size` / `max-pages` | `page-size=1000`、`max-pages=3` 可取齊目前 1,405 rows；保守可留 `max-pages=5` | 5 輪 page 0 + page 1 取齊，page 1 為 405 rows | 有量測依據，但仍需監控 row 數波動 |
| 最低刷新間隔 | 第一版維持 5 分鐘，不因本輪成功而調短 | 官方頁面約每 2/3 分鐘更新；主系統還未量端到端寫入、發布和多人併發 | 暫定，不是 SLA |
| 失敗冷卻 | 第一版維持 5 至 10 分鐘候選，依錯誤類型分流仍待定 | 本輪沒有 HTTP 失敗，只有品質風險與覆蓋不足 | 未量到，保持暫定 |
| 前景等待上限 | 不應只用 HTTP p95 訂定；可先保留 10 秒候選並讓背景刷新完成後更新查詢狀態 | 路邊 HTTP p95 8,575 ms，但未包含正規化、DB 寫入、品質檢查和發布 | 暫定，需端到端量測 |
| 品質拒絕門檻 | 缺頁一律拒絕；缺 source ID、重複 ID、負值或 unknown 值分開記錄；完整覆蓋前不輸出完整 300 公尺預測 | 本輪已量到路邊 288 重複 rows、路外 11 重複 rows 與 979 筆負值 | 已有方向，比例門檻仍待更多輪 |

## 在可正常驗證 HTTPS 的環境重做

1. 每來源先請求一頁少量資料，記錄時間、狀態碼、回應格式與欄位；確認資料集 ID／端點與篩選語法。
2. 在低流量時段各進行**一輪**完整取得，分別比較完整 CSV／ZIP 與分頁方式；記錄總 HTTP 次數、每次位元組、頁數、總耗時和錯誤。不要為了量測持續高頻重抓。
3. 比對同輪來源 ID 數、重複 ID、缺值及新莊覆蓋；測試單一行政區或場站 ID 篩選是否仍完整。若來源沒有版本／觀測時間，要記錄跨頁不一致風險。
4. 另量測主系統解析、標準化、驗證、MySQL 候選快照寫入及原子發布時間。**使用者等待時間是上述整段流程，不等於 HTTP 下載時間。**以多輪成功樣本計 p50／p95，並記錄失敗／重試的額外用量。
5. 以結果再定每來源最低刷新間隔、首次查詢前景等待上限、失敗冷卻、每日／每月請求預算及是否要少量預熱。TDX 額度獨立計算，不套用新北市的頻率。

## 要保存的驗收證據

每筆探測結果至少有資料集、取得方式、開始／完成時間、HTTP 次數與大小、總來源紀錄／新莊目標紀錄、重複／未知比例、解析與寫入／發布時間、成功或失敗原因。量測是架構決策證據；不能將官方頁面所列更新頻率或資料量當成上述實測欄位。
