# Google 路線 ETA 與停車預測時間（Sprint 1 草案）

更新日期：2026-10-26。狀態：Routes 主系統接線與同源查詢契約已存在；起點需由使用者明確設定，起訖點均採 Places 搜尋或起點裝置定位，真實 Places 候選互動仍待受限測試 key 人工驗收；正式預測機率尚未接入。

## Sponsor 已決定

使用者不輸入抵達時間。明確規劃時，由 Google 路線服務依使用者選定的起點與目的地估算駕車時間，再用使用者確認的出發時刻（預填當下，也可手動改為未來日期時間）加上估計行車時間，得到顯示與預測使用的預計抵達時間。出發地與目的地均使用 Places 搜尋候選選取；起點初始未選擇，也可明確使用裝置目前位置。目的地未選擇時不建立查詢；目的地已選擇但沒有起點時仍可查附近停車，只是不請求 ETA，並將抵達時間留空。若定位被拒絕或失敗，保留最後有效起點，尚無起點時提示改用 Places 搜尋。

使用者成功選取目的地後，系統自動建立附近停車查詢；若起點與出發時間有效，才安排 Google ETA。初次載入的預設目的地不觸發查詢。目的地選取後若尚無起點，仍查詢附近停車資訊，ETA 回傳 `NOT_REQUESTED`，不顯示預計抵達時刻；起點選定後依新條件自動重查並估算 ETA。修改起點或出發時間時，舊結果先失效，再對有效的新條件自動重規劃；指定時間無效時等待修正。只讀進度、車位自動刷新及手動更新車位均不重做 Google ETA。Google 地點候選搜尋、Routes ETA 與官方車位來源分開計量；額度阻擋時仍可顯示有效的目前車位，但抵達時預測不能假裝已有 ETA。

## 時間語意與產品邊界

**目的地 ETA** 是從選定起點到使用者搜尋的目的地，使用出發時間欄位計算；欄位預填當下但可改為未來日期時間。產品問題是「抵達目的地時，周圍 300 公尺有沒有適用的停車選項及有位可能性」，因此同一個目的地 ETA 作為該範圍內路邊區域與路外場站預測的共同參考時間；畫面要說明這是抵達目的地的時間，不是各停車場入口或格位的精確到達時間。

Sponsor 已更正：**停車模型的目標時刻就是當次 Google ETA**。系統只把 Google 回傳的行車時間加到使用者選定的出發時刻，換算成預計抵達的鐘點；不得另設 60 分鐘、10／30／60 分鐘等固定抵達時間，也不得為了模型方便而把 Google ETA 取整。資料回測可分析不同預測時距，但不會改寫使用者的 ETA；若該次目標時刻缺乏可信模型結果，保留 Google ETA，抵達時機率顯示資料不足。

「停車選項」指畫面上的某個停車場或路邊結果，不是汽車／機車車種選擇。Sponsor 本次明確表示不做內建導航，也不要求對每個停車選項重算路線；原先的逐場 ETA／Matrix 是未採納的擴充方案。第一版只做汽車，機車暫不納入。

「開始導航」是另外的明確動作：開啟外部 Google Maps 或 Apple 地圖，由外部地圖提供導航；出發時間預填當下不會自動開啟導航。若外部地圖選擇不同路線或重新計算時間，其 ETA 可能與本產品先前的規劃結果不同，不應聲稱完全一致。

## Google 來源與費用

Google Routes API 的 Compute Routes 用於一組起終點；Compute Route Matrix 可計算多組起終點，但第一版不使用逐停車選項 ETA。Google 官方說明：Compute Routes 按請求計費，Matrix 按起點數乘終點數的元素計費；此處保留 Matrix 比較供未來擴充參考。啟用考慮交通的 routing preference 可能落入較高的計費類別；若不指定，預設可能是不考慮即時交通的選項，因此不能直接稱為交通預測 ETA。選型時須確認實際 SKU、每月免費用量、每分鐘／每天配額與金鑰限制，並把 Places 搜尋與地圖用量分開估算。

**已確認的服務選型**：前端的出發地與目的地均使用 Google Place Autocomplete Widget (New)，各自維持獨立的 autocomplete host 與選取處理器；後端於明確送出行程時使用 Routes API `computeRoutes` 估算一條駕車路線；互動地圖使用 Google Maps JavaScript API。選取地點後只請求頁面真正需要的欄位；缺少 browser key 時不提供座標輸入替代。交通感知模式已在下方選定，實際 SKU 與預算仍須核對。地圖、搜尋／詳情和 Routes 的用量分別估算。

Sponsor 已同意 ETA 考慮交通。第一版設計預設 `TRAFFIC_AWARE_OPTIMAL`，因 Google 官方描述此模式與 Google Maps 網頁／行動版的路線計算模式相當；它通常比 `TRAFFIC_AWARE` 等待更久。兩者依目前官方文件均觸發 Compute Routes Pro，但仍須核對完整請求、當期價格與實際延遲。現在出發以請求時間為準；未來出發指定 `departureTime`，時間越遠，路線推估越倚重歷史交通。畫面顯示「Google 預估抵達時間」及計算時刻，不承諾外部導航開始後得到完全相同的時間。

**用量邊界**：搜尋候選在輸入過程就可能呼叫 Google；「送出才算 ETA」只限制 Routes 呼叫，不能當成 Places 搜尋零用量的保證。分別記錄搜尋會話、地點詳情、Routes 請求與地圖載入的用量。Google Cloud 的預算警示不會自動停止費用，正式使用前仍須設 Google API 配額及應用端限制；不能把警示當硬上限。

## 主系統資料流程

使用者選擇目的地 → 自動建立附近停車查詢並取得已發布快照；起點初始未選擇時 ETA 為 `NOT_REQUESTED`、不顯示預計抵達時刻 → 使用者選擇起點（Places 或目前位置）後，依新條件自動重查並請求 Google 駕車路線 ETA → 計算目標抵達時間 → 組合目前觀測與日後模型預測。初次載入預設目的地不建立查詢；Places 候選尚未選定與來源車位刷新不建立行程查詢。條件改變會立即失效原行程，條件有效後自動重算；不因每秒畫面更新或車位刷新而呼叫 Google Routes。

API／資料契約至少區分：`origin_source`、`route_target_type`（搜尋目的地或停車選項）、`route_calculated_at`、`departure_at`、`duration_seconds`、`estimated_arrival_at`、`traffic_mode`、`eta_state`。時間存 UTC、畫面以台灣時區顯示。無起點、拒絕定位、路線無結果、額度用完或超時時，ETA 為未知，模型若需要目標時間則暫不回傳預測。

## 結果頁停留時的時間處理（已確認）

- 一次明確行程規劃取得一個 Google ETA，附路線計算時刻。車位來源可依已決定規則獨立更新；在 ETA 仍指向未來且推論條件合格時，可用新車位快照重算**同一個 Google ETA 時刻**的有位機率，不重算或改寫 Google ETA。
- Google ETA 的目標時刻一旦已過，抵達時預測不能繼續標為「即將抵達」；保留先前規劃紀錄，提示更新出發時間或重新選取目的地；改變有效條件後自動建立新查詢並重新呼叫 Routes。
- 畫面至少分開顯示「Google ETA 計算時間」和「停車資料取得／觀測時間」。停車資料每 5 分鐘過期的試驗門檻不得直接拿來判定 Google ETA 過期；Google 路線尚未抵達前何時提示重算仍待產品測試，不先設任意分鐘數。

## 待決問題

- 起點已決定使用 Places 搜尋與目前位置，不設預設座標；仍須人工驗收真實候選歧義、定位權限拒絕及裝置座標品質。
- 已決定時間欄位預填當下、可手動選未來日期時間；顯示 ETA 與開啟外部導航是兩個不同動作。
- 已決定以目的地 ETA 為 300 公尺範圍內的共同參考時間，不做逐停車選項 ETA；須在結果標示此時間語意。
- 已選交通感知、第一版預設 `TRAFFIC_AWARE_OPTIMAL`，僅於明確規劃／重新規劃時重算；待實測等待時間、完整 SKU 與 Google Maps Platform 每月預算。

## 官方依據

- [Google Routes API 總覽](https://developers.google.com/maps/documentation/routes)
- [Google Routes API 使用與計費](https://developers.google.com/maps/documentation/routes/usage-and-billing)
- [Google RoutingPreference 定義](https://developers.google.com/maps/documentation/routes/reference/rest/v2/RoutingPreference)
- [Google 交通資料設定與未來出發時間](https://developers.google.com/maps/documentation/routes/config_trade_offs)
- [Google Routes 價格表](https://developers.google.com/maps/billing-and-pricing/pricing)
- [Google Place Autocomplete Widget (New)](https://developers.google.com/maps/documentation/javascript/place-autocomplete-new)
- [Google Maps Platform 服務專用條款](https://cloud.google.com/maps-platform/terms/maps-service-terms)
- [Google Maps JavaScript 用量與計費](https://developers.google.com/maps/documentation/javascript/usage-and-billing)
- [Google Maps Platform 費用管理](https://developers.google.com/maps/billing-and-pricing/manage-costs)
