# Google 地圖、搜尋與 ETA 用量規劃（Sprint 1 草案）

更新日期：2026-09-24。狀態：Sponsor 已回報獨立停車 Google Cloud 專案建立完成、三項所需 API 已啟用、帳單連結有效，且預算通知已設定。帳單與費用規劃由 Sponsor 自行管理，本專案討論先跳過；金鑰限制與配額尚未回報，不把它們記為已完成。主系統尚未 coding。

**停車專案識別**：顯示名稱 `Taiwan Parking Forecast`；專案 ID `taiwan-parking-forecast`；專案編號 `1031986609157`。這是 Sponsor 回報的專案資訊，尚未由本專案直接讀取 Cloud Console 驗證；專案 ID／編號不是 API 金鑰。

**已啟用服務（Sponsor 回報）**：Maps JavaScript API、Places API (New)、Routes API。先前只列 Gemini API 與 Telemetry API 的是 Sponsor 當時檢視的另一個專案，不是新的停車專案。

## 已選服務與計量事件

| 功能 | 第一版服務 | 何時可能產生 Google 用量 | 主系統可控制的事 |
| --- | --- | --- | --- |
| 互動地圖 | Google Maps JavaScript API | 載入動態地圖時；Dynamic Maps 以地圖載入計量 | 單一結果頁重用同一張地圖，不因車位輪詢或標記更新重建地圖 |
| 地點候選 | Place Autocomplete Widget (New) | 使用者輸入及選擇目的地／手動起點時；候選與地點詳情有各自計費規則 | 限定台灣／合理搜尋範圍、只取必要地點欄位；記錄規劃次數並觀察 Google 實際 SKU 用量 |
| 駕車 ETA | Routes API `computeRoutes`、`TRAFFIC_AWARE_OPTIMAL` | 使用者明確送出或重新送出行程規劃時；依目前官方文件觸發 Compute Routes Pro | 一次規劃只發起一個邏輯路線工作；進度輪詢、車位刷新、地圖標記更新均不再請求 Routes；重試也必須計入用量 |

這三類與新北市／TDX 車位來源分開估算。沒有實際月使用人數、每人開圖次數、地點輸入與選取次數前，不填造每月金額；單次規劃也不能假設必然只有一筆 Google 計費事件。未來公開測試會改變流量假設，需要重估。

## Sponsor 預算與 2026-09-24 官方價目快照

Google One／雲端硬碟 2TB 是 Drive、Gmail、Photos 的儲存訂閱，**不能用來判定 Google Cloud Maps Platform 專案已開通帳單**。Sponsor 要求 Maps Platform 每月實付最多新臺幣 200 元。Cloud Billing 的預算警示無法保證「絕不超過 200 元」；設計時要以保守配額、用量監測與超限停止功能降低風險。若 Sponsor 的 200 元是不可超支的硬限制，啟用正式計費前須確認可接受的實際控制方式，不能把警示當保證。

Google Cloud 另有預覽中的「Spend cap budget」，但截至本日官方列出的適用服務是 Gemini API、Agent Platform、Cloud Run 與 Cloud Run functions，**沒有列 Maps Platform**；而且該功能仍可能有處理延遲與超額。不能因 Console 看得到一般「預算通知」就認為本專案 Maps 費用可由它硬性封頂。

Sponsor 先前確認已有 Gemini API 的帳單，當時檢視的舊專案已啟用服務只有 Gemini API 與 Telemetry API；這不能單憑 API 清單判斷帳單連結狀態。現在已另建上述停車專案並啟用三項 Maps 服務，Sponsor 另回報帳單連結有效；尚未確認它是否與 Gemini 共用同一帳單。Google 官方說明 Gemini API 預付點數只用於 Gemini API，其他 Google Cloud 服務另按其計費規則處理；不得把 Gemini 餘額算入本專案 200 元 Maps 預算。建立新專案不會自動增加同帳單的免費用量。

| 目前選型所涉 SKU | 官方每月免費用量 | 超過免費量後的第一級標價（美元／1,000 次） | 備註 |
| --- | ---: | ---: | --- |
| Dynamic Maps | 10,000 次地圖載入 | US$7 | 一張互動地圖載入屬獨立事件 |
| Autocomplete Requests (New) | 10,000 次 | US$2.83 | 實際會話結束方式可能改變計費，不能拿按鍵數直接當發票 |
| Place Details Pro | 5,000 次 | US$17 | 若選取地點後要求 `displayName` 等 Pro 欄位；只取不同欄位會改變 SKU |
| Routes: Compute Routes Pro | 5,000 次 | US$10 | 目前 `TRAFFIC_AWARE_OPTIMAL` 選型；完整請求若啟用其他功能仍須核對 SKU |

以上免費用量與標價是本日官方網頁的查核快照，不代表帳戶現況或保證每月只會產生這些 SKU。面試本機少量試用若每個 SKU 都在免費用量內，Google Maps Platform 用量費很可能是 0；因此新臺幣 200 元對**受控本機展示**可作合理起始預算。公開給多人測試時不能照此推定。匯率、稅、其他專案共用帳單及服務用量需以 Google Cloud Billing 實際帳單為準。

## 如何檢查帳單是否已啟用

1. 使用打算開發的 Google 帳號登入 [Google Cloud Console 帳單頁](https://console.cloud.google.com/billing)。在「我的專案」（My projects）確認 Gemini 使用的專案與所連結的帳單帳戶；此時不用建立或提供金鑰。
2. 在上方專案選擇器切到預計供停車專案使用的 Cloud 專案，確認它有連結有效帳單。然後於「API 和服務」→「已啟用的 API 和服務」檢查 Maps JavaScript API、Places API (New)、Routes API。已有 Gemini API 不代表這三項也已啟用。
3. 停車專用 Cloud 專案、三項 API、有效帳單連結與預算通知均已由 Sponsor 回報完成。Sponsor 自行管理帳單規劃，本階段先轉回主系統 API 契約。網頁／後端憑證限制和可調配額尚未回報；正式介接前需核對，不要把金鑰、付款資訊貼到專案或聊天。

## 控制與觀察

### 停車專案設定順序（待 Sponsor 在 Cloud Console 執行）

1. **已回報完成**：建立獨立停車 Cloud 專案並記下專案名稱／ID／編號，且停車專案帳單連結有效。**尚未確認**：Gemini 專案是否共用該帳單；操作前核對專案 ID，避免在錯誤專案設定費用與憑證。
2. **已回報完成**：Sponsor 已設定預算通知，帳單規劃自行管理；具體通知範圍與金額不由本專案另行推定。此通知**不是硬性費用上限**。
3. **已回報完成**：Maps JavaScript API、Places API (New)、Routes API 已啟用。仍可在停車專案的「已啟用的 API」清單複核；Google 的入門文件指出啟用 Places 可能連帶啟用 Maps JavaScript 等整合項目，不假設清單只有三項。
4. 建立分開且受限的網頁端與伺服器端憑證：網頁端只允許開發／正式網站來源及必要的地圖／Places API；Routes 憑證只供 Spring Boot 伺服器使用，並按部署環境採適用的限制。不得沿用 Gemini 金鑰，亦不得提交憑證到 Git。
5. 在各服務配額頁確認可下調的用量限制，先以小規模本機展示為前提設定保守值，並記錄每一種限制的計量單位與是否可調。**不在尚未量測每次展示用量前，憑空宣稱某個固定配額必然符合 NT$200。**配額設太低會造成搜尋、地圖或 ETA 無法使用；調整後要做小量驗證。
6. 首次小量測試後，分別查 Google Maps／Places／Routes 的實際用量、計費 SKU 和 Cloud Billing 帳單，再依事件量調整配額。帳務資料可能延遲，使用者公開測試前另行重估；不能用即時配額畫面代替最終帳單。

**完成判定**：能在停車專案看到正確帳單連結、所需服務狀態、兩類受限憑證、預算通知及可用的配額設定；小量測試後能說明一次規劃產生哪些 Google 計費事件。這是設定與驗收計畫，非目前完成狀態。

1. 在 Google Cloud 專案分別查看地圖、Places、Routes 的實際請求與計費 SKU，核對當期價格及免費用量。面試本機展示先用少量實測建立每次操作的事件清單，再推算月情境。
2. 網頁端金鑰與伺服器端 Routes 憑證分開，依實際使用環境設網站／伺服器來源及 API 限制；憑證不提交 Git。遠端 Docker 上線時重新核對網域、出口 IP 和限制方式。
3. Google Cloud 設各 API 適當配額及帳單預算通知；應用端限制不必要的 Routes 重算。預算通知**不會自動停止費用**，Google 的配額與帳務資料也可能有時間差，不能把通知當精確硬性金額上限。
4. API 配額耗盡或 Google 服務暫時失敗時，清楚保留有效的政府車位觀測；沒有本次 ETA 就不顯示抵達時機率。地圖或搜尋不可用時說明服務狀態，不以舊地點候選冒充新結果。

## 待確認

- Sponsor 已回報獨立停車專案、有效帳單連結、Maps JavaScript API、Places API (New)、Routes API 及預算通知均已完成；帳單規劃由 Sponsor 自行管理，本階段不再追問。受限憑證與配額在正式介接前仍需確認；不要在文件或聊天貼 API 金鑰。
- 新臺幣 200 元／月的目標是否同時涵蓋日後公開測試？若公開測試使用人數上升，須重新估算並由 Sponsor 決定是否維持同一上限。
- 一次完整展示通常開啟幾次地圖、選幾個目的地及起點、重新規劃幾次？用實際試用數據校正估算。
- Google 來源內容的顯示及短期保存須依服務條款實作；政府停車觀測歷史與 Google 地點／路線內容分開管理。

## 官方依據

- [Google Maps JavaScript API 用量與計費](https://developers.google.com/maps/documentation/javascript/usage-and-billing)
- [Google Maps Platform 入門與啟用順序](https://developers.google.com/maps/get-started)
- [Places Autocomplete 會話計費](https://developers.google.com/maps/documentation/places/web-service/session-pricing)
- [Routes API 用量與計費](https://developers.google.com/maps/documentation/routes/usage-and-billing)
- [Google Maps Platform 費用管理](https://developers.google.com/maps/billing-and-pricing/manage-costs)
- [Google Cloud 一般預算與通知](https://docs.cloud.google.com/billing/docs/how-to/budgets)
- [Google Cloud Spend cap budget 適用服務與限制](https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps)
- [Google Maps Platform 金鑰安全建議](https://developers.google.com/maps/api-security-best-practices)
- [Google Maps Platform 官方 SKU 價目表](https://developers.google.com/maps/billing-and-pricing/pricing)
- [Google Cloud：查詢專案連結的帳單帳戶](https://docs.cloud.google.com/billing/docs/how-to/view-linked)
- [Google One：雲端儲存訂閱說明](https://support.google.com/googleone/answer/9004014)
- [Gemini API 帳單與預付點數用途](https://ai.google.dev/gemini-api/docs/billing)
