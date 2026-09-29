# 主系統官方即時來源：單輪請求量與延遲量測

更新日期：2026-09-29。狀態：S2-021 已加入主系統量測摘要基線；低頻真實多輪量測仍待 Sponsor 確認頻率後執行。本文件只針對主系統的官方即時來源；離線訓練 collector 的請求量不可充作主系統結果。

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

尚未完成：沒有執行多輪真實 refresh、沒有建立 p50/p95、沒有提高刷新頻率，也沒有把單次成功視為穩定性證據。

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

## 在可正常驗證 HTTPS 的環境重做

1. 每來源先請求一頁少量資料，記錄時間、狀態碼、回應格式與欄位；確認資料集 ID／端點與篩選語法。
2. 在低流量時段各進行**一輪**完整取得，分別比較完整 CSV／ZIP 與分頁方式；記錄總 HTTP 次數、每次位元組、頁數、總耗時和錯誤。不要為了量測持續高頻重抓。
3. 比對同輪來源 ID 數、重複 ID、缺值及新莊覆蓋；測試單一行政區或場站 ID 篩選是否仍完整。若來源沒有版本／觀測時間，要記錄跨頁不一致風險。
4. 另量測主系統解析、標準化、驗證、MySQL 候選快照寫入及原子發布時間。**使用者等待時間是上述整段流程，不等於 HTTP 下載時間。**以多輪成功樣本計 p50／p95，並記錄失敗／重試的額外用量。
5. 以結果再定每來源最低刷新間隔、首次查詢前景等待上限、失敗冷卻、每日／每月請求預算及是否要少量預熱。TDX 額度獨立計算，不套用新北市的頻率。

## 要保存的驗收證據

每筆探測結果至少有資料集、取得方式、開始／完成時間、HTTP 次數與大小、總來源紀錄／新莊目標紀錄、重複／未知比例、解析與寫入／發布時間、成功或失敗原因。量測是架構決策證據；不能將官方頁面所列更新頻率或資料量當成上述實測欄位。
