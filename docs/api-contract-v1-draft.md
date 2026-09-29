# 主系統前後端 API 契約 v1（Sprint 1 提案）

更新日期：2026-09-24。狀態：供 Sponsor 審閱的語意草案；路由與欄位名稱尚未核定，沒有 coding。後端只接主系統的官方即時來源；獨立訓練 collector 不提供此 API 的目前車位。

## 三種使用者操作

| 候選操作／路由 | 輸入 | 回應與副作用 |
| --- | --- | --- |
| 建立查詢：`POST /api/v1/parking-queries` | 使用者已選定的起點、目的地座標與顯示名稱、NOW／帶時區的指定出發時間、同次規劃的防重送識別；汽車與直線 300 公尺由 MVP 後端固定 | 建立短期、不易猜測的 `queryId`；立即回可信的目的地及已收錄位置（若本地目錄已可用），ETA／路邊／路外各自更新中。後端安排 Google ETA 與必要的來源共用受控刷新 |
| 讀取進度：`GET /api/v1/parking-queries/{queryId}` | `queryId` | 只讀本次進度與已完成結果；不得重新呼叫 Google 或政府 API。Vue 尚有工作在進行才按有限次數輪詢；候選節奏見下方期限設計 |
| 手動更新車位：`POST /api/v1/parking-queries/{queryId}/refresh` | `queryId`；不重送搜尋文字 | 分別檢查路邊／路外共用完整快照、最低間隔、冷卻與用量限制；可能直接沿用有效快照、加入執行中刷新，或啟動新刷新。回每來源處置與可再試時間（若有），不重算 Google 搜尋與 ETA；新觀測到位後可按已驗證模型規則更新同一 ETA 時刻的預測 |

建立查詢回應可立即提供已完成欄位，不要求 ETA 與兩種來源同時完成。`queryId` 短期保存在 Redis，第一版 TTL 以 2 小時作候選值並待實測；它不是車位預約。Redis 鍵過期、刷新工作被重啟中斷或失敗時，進度必須有終止狀態，不能永遠停在更新中。查詢進度不長期保存原始搜尋文字或個人行程；Redis 詳細 key 類型見 `redis-design.md`。

Sponsor 已決定建立行程查詢須由使用者明確送出已選起點、目的地與出發時間；只在這個動作安排 Google Routes ETA。條件改變後必須明確重新規劃才重算。`GET` 進度與自動／手動車位刷新都不重做 ETA。搜尋候選、Routes 與各政府來源分開計量；Routes 被內部預算阻擋時，ETA 區塊回失敗／阻擋原因，其他有效目前車位區塊仍可顯示，抵達時機率因缺 ETA 留空。

### 已決定的分階段語意；HTTP 與欄位仍待技術定稿

Sponsor 已同意：`POST` 在完成輸入檢查、試點範圍檢查及短期查詢狀態建立後快速回應，不等待 Google Routes 或兩個官方車位來源。建議使用 `202 Accepted`；候選第一個回應至少有 `queryId`、伺服器接受時間、查詢有效期限、原目的地座標、直線 300 公尺範圍，並把 ETA、路邊、路外與各自抵達時預測列為獨立區塊。MySQL 停車目錄若已可用，可同時給**已收錄位置**；目錄不可用時回明確的「位置資料待取得／無法確認」，不得用空清單冒充完整搜尋結果。第一個回應的具體欄位名稱、HTTP 碼及期限仍是提案。

| 區塊 | 第一個回應可能狀態 | 後續可補齊的內容 |
| --- | --- | --- |
| 地圖中心與範圍 | 目的地座標與 300 公尺立即可用；底圖本身由網頁載入 Google Maps | 已核實且目錄可用的路邊格位與路外場站位置；沒有目錄就標未知 |
| Google ETA | 通常 `pending`；若已完成則直接 `ready` | 當次 Google ETA、計算時間與失敗原因；只在使用者明確規劃時啟動一次邏輯工作 |
| 路邊／路外目前車位 | **各自**為 `pending`、有效 `ready`、過期或失敗；可立即沿用已發布且仍新鮮的快照 | 各自的有效觀測、來源／取得時間及資料品質；兩個來源互不等待 |
| 抵達時預測 | 等 Google ETA 與該類可信觀測／模型條件；`pending` 或已知 `insufficient_data` | 路邊區域機率、路外逐場機率，或明確的資料不足／失敗狀態；不得從目前有位數猜機率 |

Vue 只在仍有工作進行且頁面可見時讀取 `GET` 進度；`GET` 只讀已建立狀態，不新增 Google／政府請求。哪個區塊先完成就先顯示，不人為等待另一區塊以製造固定順序；先前產品希望「先看到 ETA 與位置、車位後補」是常見路徑，不能保證官方快照一定晚於 ETA。當各工作達最後期限，區塊轉明確終止狀態，前端停止等待。手動／可見頁面過期後的車位刷新仍走獨立、受控的刷新操作，不重算 ETA。上述分階段語意已同意，欄位與輪詢數值仍待定。

## 建立查詢的輸入規則

### 已決定：Google 地點選取後如何交給主後端

Sponsor 已同意：使用者從前端 Places (New) 候選選定目的地或手動起點時，Vue 傳該地點的座標、短顯示名稱與可選的 `placeId`；目前位置起點則傳使用者同意取得的裝置座標，不偽裝成 Google 地點。**同一份目的地座標**交給 Google Routes 作 ETA 目標，也作 300 公尺附近查詢的圓心，避免同次查詢的路線終點與停車範圍中心不一致；顯示名稱只供介面呈現，不作地理判定。Google Routes 官方支援座標作起終點。

後端必須檢查必填欄位、經緯度範圍、MVP 覆蓋及輸入長度，並限制建立查詢的頻率；**僅靠格式檢查無法證明前端座標確實來自 Google 候選或裝置定位**。第一版依 Sponsor 同意的流程先使用同一傳入座標，公開使用前再評估來源驗證與濫用控制。不得因為前端提供一串地點名稱，就由後端自行猜座標；也不讓前端傳自算 ETA。具體欄位名稱尚未定案或實作。

### 已決定的輸入語意：起訖點、出發時間與重複送出

| 候選欄位 | 已決定的第一版語意 | 後端要檢查什麼 |
| --- | --- | --- |
| 起點 | `CURRENT_LOCATION`（使用者許可的裝置座標）或 `SEARCH_SELECTION`（手動選取地點）；兩者都含經緯度 | 經緯度有效，來源種類與內容一致；起點不限定新莊 |
| 目的地 | 已選定搜尋候選的經緯度、短顯示名稱；`placeId` 可選 | 經緯度有效且在新莊 MVP 服務範圍；名稱不作定位依據 |
| 出發時間 | `NOW` 或 `SCHEDULED`；`NOW` 由後端在建立查詢時取當下時間，`SCHEDULED` 需提供含時區偏移的具體時間 | 避免前端預填「現在」後使用者停留太久形成過去時間；指定時間不得是過去，超過 Google 可支援範圍時回明確錯誤 |
| 重複送出識別 | 前端每次**明確**按「規劃」產生一個短期識別值；網路重試或連點重用同值，真正重新規劃才換新值 | 同一識別值只建立一個 `queryId` 與一個邏輯 Google ETA 工作；識別值期限與儲存方式待定 |

第一版汽車、直線 300 公尺由主後端固定，不接受前端改寫。輸入與防重送的**語意**已獲 Sponsor 同意；具體欄位名稱、防重送識別有效期及儲存、指定時間容許範圍仍待技術設計。候選 HTTP 規則：欄位缺漏或格式錯誤回 `400`；有效格式但目的地在新莊試點外回 `422` 和穩定的 `unsupported_area` 代碼，且不呼叫 Routes／車位來源；過去的指定出發時間也回可理解的輸入錯誤；建立查詢速率受限制時回 `429`。HTTP 碼與代碼仍是提案，尚未定案。

- 起點支援經使用者許可取得的裝置座標或手動選定地點；目的地來自使用者選取的搜尋候選。API 驗證座標範圍與新莊 MVP 覆蓋，不能依純文字自行猜地點。
- 出發模式預設 NOW，由後端在建立查詢時取當下；使用者可改為指定日期時間，此時傳輸值須含 UTC 偏移或清楚的 UTC 語意。後端用 Google Routes 估計到**目的地**的 ETA。
- 前端不傳自算 ETA、剩餘位或模型機率；後端固定第一版汽車與 300 公尺範圍。

### 已決定：建立查詢 Request Body 草案

`POST /api/v1/parking-queries` 的 Request Body 只描述一次明確行程規劃所需的輸入。前端不傳 Google ETA、目前剩餘位、模型機率、搜尋半徑或車種；第一版車種固定汽車，搜尋範圍固定目的地直線 300 公尺，由後端決定。

```json
{
  "requestToken": "plan_20260925_001",
  "origin": {
    "type": "CURRENT_LOCATION",
    "displayName": "目前位置",
    "latitude": 25.0478,
    "longitude": 121.5170,
    "accuracyMeters": 35
  },
  "destination": {
    "type": "GOOGLE_PLACE",
    "displayName": "新莊體育館",
    "formattedAddress": "新北市新莊區中華路一段75號",
    "placeId": "google-place-id-example",
    "latitude": 25.0391,
    "longitude": 121.4529
  },
  "departure": {
    "mode": "NOW"
  }
}
```

若使用者手動選擇起點，`origin.type` 改為 `SEARCH_SELECTION`，可帶 `placeId` 與 `formattedAddress`。若使用者指定未來出發時間，`departure.mode` 改為 `SCHEDULED`，並提供含時區偏移的 `departureAt`。

```json
{
  "requestToken": "plan_20260925_002",
  "origin": {
    "type": "SEARCH_SELECTION",
    "displayName": "台北車站",
    "formattedAddress": "台北市中正區北平西路3號",
    "placeId": "google-place-id-origin-example",
    "latitude": 25.0478,
    "longitude": 121.5170
  },
  "destination": {
    "type": "GOOGLE_PLACE",
    "displayName": "新莊體育館",
    "formattedAddress": "新北市新莊區中華路一段75號",
    "placeId": "google-place-id-destination-example",
    "latitude": 25.0391,
    "longitude": 121.4529
  },
  "departure": {
    "mode": "SCHEDULED",
    "departureAt": "2026-09-25T18:30:00+08:00"
  }
}
```

| 欄位 | 必填 | 語意與驗證 |
| --- | --- | --- |
| `requestToken` | 是 | 前端每次明確按「規劃」產生；同一次網路重試或連點重用。長度、字元集與有效期由後端限制 |
| `origin.type` | 是 | `CURRENT_LOCATION` 或 `SEARCH_SELECTION`；目前位置不得偽裝成 Google 地點 |
| `origin.latitude`／`origin.longitude` | 是 | 起點座標；緯度 -90 到 90，經度 -180 到 180；起點不限定新莊 |
| `origin.accuracyMeters` | 否 | 裝置定位精度；只適用 `CURRENT_LOCATION`，可用於提示但不作唯一可信依據 |
| `origin.displayName` | 否 | 顯示用文字，不作定位依據 |
| `origin.placeId` | 條件式 | `SEARCH_SELECTION` 可帶；`CURRENT_LOCATION` 不需要 |
| `destination.type` | 是 | 第一版一般使用者為 `GOOGLE_PLACE`；開發測試可保留 `MANUAL_COORDINATE` 候選，但不作主要流程 |
| `destination.latitude`／`destination.longitude` | 是 | 目的地座標；同時作 Google Routes 終點與 300 公尺圓心 |
| `destination.displayName` | 是 | 顯示用短名稱；不作定位依據 |
| `destination.placeId` | 建議 | Google Places 選取結果建議提供；後端第一版不靠它重新查座標 |
| `departure.mode` | 是 | `NOW` 或 `SCHEDULED` |
| `departure.departureAt` | 條件式 | `SCHEDULED` 必填，必須含時區偏移或明確 UTC；`NOW` 不接受前端傳現在時間，由後端建立查詢時取伺服器時間 |

後端驗證順序建議為：JSON 格式與必填欄位、座標範圍、文字長度、`departure` 語意、`requestToken` 防重送、目的地是否在新莊 MVP、建立查詢速率限制。目的地若不在新莊 MVP，停止流程並回 `UNSUPPORTED_AREA`；不得呼叫 Google Routes 或官方車位來源。

`requestToken` 是防止連點與網路重試造成重複 Google ETA 工作的短期識別，不是安全權杖。它只應在同一位使用者或同一瀏覽器短時間內有效；真正重新規劃起點、目的地或出發時間時，前端必須產生新的 `requestToken`。後端仍要搭配使用者／裝置層級限流，不能只靠 `requestToken` 防濫用。

第一版不接受前端覆寫以下產品設定：車種、半徑、距離算法、Google ETA 交通模式、來源刷新策略、模型版本。這些都由後端與系統設定控制，避免前端送出與 MVP 不一致的查詢。
## 回應分層，避免把不同狀態混成一個字

### 已決定：`query` 區塊欄位語意

`query` 區塊只描述本次查詢生命週期與前端輪詢控制，不放目的地、ETA、車位數或模型機率。第一版語意如下；正式 JSON 名稱可微調，但不改變職責邊界。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 查詢識別 | `queryId` | 短期、不易猜測的查詢 ID；供同分頁恢復與讀取進度，不放分享網址 |
| 查詢生命週期 | `status` | 候選值：`ACCEPTED`、`RUNNING`、`COMPLETED`、`PARTIAL`、`EXPIRED`、`FAILED`。它表示整體查詢狀態，不取代各區塊狀態 |
| 建立與伺服器時間 | `createdAt`、`serverTime` | 用來判斷前端看到的時間與伺服器時間差，避免以前端本機時間推論資料是否過期 |
| 查詢到期 | `expiresAt` | Redis 查詢狀態的絕對有效期；到期後前端提示重新規劃，不自動建立新查詢 |
| 是否仍需輪詢 | `hasPendingWork` | 只要 ETA、路邊、路外或預測仍有工作在進行就為 true；全部終止後前端停止輪詢 |
| 建議輪詢時間 | `nextPollAfterSeconds` | 後端給前端的建議等待秒數；前端仍需在頁面隱藏時停止輪詢 |
| 進行中區塊 | `pendingSections` | 例如 `ETA`、`ROADSIDE`、`OFF_STREET`、`PREDICTIONS`；方便前端只顯示相關 loading |
| 已終止區塊 | `finishedSections` | 包含成功、失敗、資料不足或被阻擋的區塊；是否成功看各區塊自己的狀態 |
| 重新規劃需求 | `requiresReplan` | 原 ETA 目標時刻已過、查詢過期或輸入條件需重送時為 true；車位刷新不能把它改回 false |
| 防重送追蹤 | `requestTokenAccepted` | 可選；告知本次回應是否沿用同一次明確規劃動作，避免前端誤以為建立了新查詢 |

`status=PARTIAL` 表示至少一個區塊成功或可顯示，但仍有區塊失敗、資料不足或被阻擋；這是正常產品狀態，不等於 HTTP 失敗。HTTP `200` 或 `202` 只代表主系統成功處理這次讀取或接受查詢，不代表 Google 或官方車位來源全部成功。

`query` 的 `expiresAt`、各背景工作的最後期限、來源資料新鮮度及 Google ETA 目標時刻是不同概念。`queryId` 過期不會讓 MySQL 已發布快照消失；來源資料過期也不會讓 `queryId` 失效。前端讀到 `hasPendingWork=false` 後停止進度輪詢；之後若使用者按「更新車位」，走刷新端點與 `refresh` 區塊，不改變原本 Google ETA。


### 已決定：`destination` 區塊欄位語意

`destination` 區塊只描述使用者本次選定的目的地與主系統的支援判定，不放 Google ETA、停車選項或模型預測。第一版語意如下；正式 JSON 名稱可微調，但職責邊界固定。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 顯示名稱 | `displayName` | 前端 Google Places 候選的短名稱或使用者可辨識名稱；只供顯示，不作地理判定 |
| 格式化地址 | `formattedAddress` | 可選；若 Google 回傳且前端有需要，可用於結果頁顯示 |
| Google 地點 ID | `placeId` | 可選；來自 Places 選取結果。後端可記錄作追蹤，但第一版不靠它重新查座標 |
| 座標 | `latitude`、`longitude` | 本次 Google ETA 終點與 300 公尺搜尋圓心的唯一座標來源；後端檢查有效範圍 |
| 來源種類 | `selectionSource` | 候選值：`GOOGLE_PLACE`、`MANUAL_COORDINATE`。第一版目的地主要來自 Google Places；手動座標只作開發或測試候選 |
| 支援狀態 | `supportStatus` | 候選值：`SUPPORTED`、`UNSUPPORTED_AREA`、`INVALID_COORDINATE`、`UNKNOWN` |
| MVP 區域 | `serviceArea` | 第一版為 `NEW_TAIPEI_XINZHUANG`；若不支援則可為 `null` |
| 拒絕原因 | `unsupportedReason` | 例如 `OUTSIDE_MVP_AREA`、`INVALID_COORDINATE`、`MISSING_COORDINATE`；只在不支援或無法判定時使用 |
| 使用者訊息 | `message` | 給前端顯示的簡短狀態，例如「目前尚未支援此地區」 |

目的地座標一旦建立查詢便固定，用於 Google Routes ETA 終點與 `area` 的 300 公尺圓心。前端不得同時傳另一個停車搜尋中心；後端也不得根據純文字名稱自行猜另一個座標。若目的地在新莊 MVP 外，`destination.supportStatus=UNSUPPORTED_AREA`，查詢不啟動 Google Routes 或官方車位來源，前端保留地點並提供改搜入口。

`destination` 的支援判定只回答「這個目的地是否在目前服務範圍內」。它不表示 300 公尺內是否有停車資料；收錄範圍與跨區覆蓋由 `area` 區塊描述。


### 已決定：`area` 區塊欄位語意

`area` 區塊描述本次停車查詢的地理範圍與資料覆蓋狀態，不放 ETA、即時剩餘位或模型機率。第一版以目的地座標為圓心、直線距離 300 公尺為查詢範圍；這是產品驗證用的地圖距離，不代表步行距離。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 圓心 | `centerLatitude`、`centerLongitude` | 必須等於 `destination` 座標；不可另傳停車搜尋中心 |
| 半徑 | `radiusMeters` | 第一版固定 `300`，由後端決定，不接受前端改寫 |
| 距離算法 | `distanceMode` | 第一版固定 `STRAIGHT_LINE`；日後若改步行距離再新增模式 |
| 服務範圍 | `serviceArea` | 第一版為 `NEW_TAIPEI_XINZHUANG`；和 `destination.serviceArea` 保持一致 |
| 整體覆蓋 | `coverageStatus` | 候選值：`COMPLETE`、`PARTIAL`、`UNKNOWN`、`UNSUPPORTED` |
| 路邊覆蓋 | `roadsideCoverageStatus` | 路邊格位資料在 300 公尺圓內的覆蓋狀態 |
| 路外覆蓋 | `offStreetCoverageStatus` | 路外停車場目錄在 300 公尺圓內的覆蓋狀態 |
| 是否跨出支援區 | `crossesServiceBoundary` | 例如目的地在新莊內，但 300 公尺圓部分跨到鄰區 |
| 覆蓋說明 | `coverageNotes` | 可選；列出缺口原因，例如 `CROSSES_UNSUPPORTED_AREA`、`CATALOG_UNAVAILABLE` |
| 已收錄數量摘要 | `catalogCounts` | 只描述目錄中符合一般汽車條件的已收錄路邊格位／路外場站數量；不等於目前有位數 |

`coverageStatus=COMPLETE` 需要可信邊界或同等可驗證依據；不能只因目的地地址在新莊就宣稱完整。若 300 公尺圓部分跨出目前收錄範圍，`coverageStatus=PARTIAL` 且 `crossesServiceBoundary=true`。此時可以顯示已收錄位置及合格路外單場結果，但不得輸出完整 300 公尺路邊「至少一格可停」機率。

`catalogCounts` 的 `0` 只代表「目前收錄目錄中沒有符合條件的項目」，不代表現場沒有停車位。若目錄不可用或覆蓋未知，數量應為未知或可空，不用空陣列冒充查詢完整。


### 已決定：`eta` 區塊欄位語意

`eta` 區塊只描述本次明確行程規劃取得的 Google Routes ETA，不放車位資料、來源刷新狀態或模型機率。第一版只有在使用者送出起點、目的地與出發時間後才安排 Google Routes；車位自動刷新與手動更新不重算 ETA。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| ETA 狀態 | `status` | 候選值：`PENDING`、`READY`、`FAILED`、`BLOCKED`、`EXPIRED`、`NOT_REQUESTED` |
| 起點摘要 | `originSummary` | 不保存完整搜尋文字；可描述目前位置或手動選取起點的顯示名稱／座標摘要 |
| 目的地參照 | `destinationRef` | 指向 `destination` 區塊；不重複另一套目的地座標 |
| 出發時間 | `departureAt` | 後端實際送給 Google Routes 的出發時間；`NOW` 由後端建立查詢時決定 |
| 計算時間 | `calculatedAt` | Google ETA 完成或主系統取得結果的時間；不得拿停車資料時間替代 |
| 行車秒數 | `durationSeconds` | Google Routes 回傳的估計行車時間，用來換算抵達時刻 |
| 預計抵達 | `estimatedArrivalAt` | `departureAt + durationSeconds` 的結果，是停車預測共同目標時刻 |
| 交通模式 | `trafficMode` | 第一版設計為 `TRAFFIC_AWARE_OPTIMAL`；若未來改用其他模式需明示 |
| 路線供應者 | `provider` | 第一版為 `GOOGLE_ROUTES` |
| 是否需要重規劃 | `requiresReplan` | 原 ETA 目標時刻已過、查詢過期或使用者改變條件時為 true |
| 失敗原因 | `failureReason` | 候選值：`ROUTE_NOT_FOUND`、`GOOGLE_TIMEOUT`、`GOOGLE_ERROR`、`BUDGET_LIMITED`、`INVALID_INPUT`、`UNKNOWN` |
| 使用者訊息 | `message` | 例如「暫時無法估算抵達時間」或「原預計抵達時間已過，請重新規劃」 |

`estimatedArrivalAt` 是路邊與路外抵達時預測的共同目標時刻。若 `eta.status` 不是 `READY`，`predictions` 不能輸出看似有效的抵達時機率，應標示缺 ETA 或資料不足。若 `estimatedArrivalAt` 已過，`eta.status` 可轉為 `EXPIRED` 或 `requiresReplan=true`，保留舊 ETA 作參考，但停止把其預測當作未來結果。

Google ETA 的時間狀態與停車資料 5 分鐘新鮮度門檻分開。停車快照更新後可以在原 ETA 尚未過期時重新推論同一目標時刻；不得默默重算 Google Routes，也不得用固定 10／30／60 分鐘替代 Google ETA。

### 已決定：`roadside` 區塊欄位語意

`roadside` 區塊只描述目的地 300 公尺範圍內的路邊停車目前觀測、來源狀態與可顯示格位明細，不直接放抵達時機率。抵達時「至少一格可停」的機率統一放在 `predictions`，避免把目前空位誤解成到達時仍可停。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 來源狀態 | `status` | 沿用統一來源狀態：`FRESH`、`STALE`、`REFRESHING`、`FAILED`、`COOLDOWN`、`NO_DATA`、`UNSUPPORTED` |
| 來源類型 | `sourceType` | 第一版為 `NTPC_ROADSIDE_SPOT`；未來接其他地方政府來源時保留來源辨識 |
| 快照識別 | `snapshotId` | 指向主系統已發布的完整快照；不可指向未驗證或半套擷取結果 |
| 來源觀測時間 | `sourceObservedAt` | 若官方資料有提供真正觀測時間才填；沒有則為空，不以系統擷取時間偽裝來源時間 |
| 系統取得時間 | `systemFetchedAt` | 主系統成功取得並發布快照的時間；5 分鐘新鮮度先以此作試驗門檻 |
| 是否新鮮 | `isFresh` | 依來源類型與目前試驗門檻判斷；第一版超過 5 分鐘為 false |
| 區域目前摘要 | `summary` | 只描述目前觀測摘要，例如已收錄汽車適用格位數、已觀測格位數、目前回報空位數、未知數、可顯示導航空位數 |
| 格位明細 | `spots` | 目前可顯示的格位清單；第一版可限制數量，避免回傳過大 |
| 使用者訊息 | `message` | 例如「正在更新路邊車格」、「路邊資料已過期」、「目前沒有可確認的路邊空位」 |

`summary.availableSpotCount` 只代表「目前快照中回報可停且仍新鮮的格位數」，不是抵達時預測。若來源過期、失敗、冷卻或覆蓋未知，主要目前可用數應為空或不可用狀態；舊值只能放在附時間的參考欄位，不得當作目前推薦或導航依據。

`spots` 中每筆格位至少需要能表達：系統內格位 ID、來源格位 ID、經緯度、距離目的地幾公尺、目前狀態、是否一般汽車適用、觀測／取得時間、品質狀態、導航資格與不可導航原因。候選狀態可包含 `AVAILABLE`、`OCCUPIED`、`UNKNOWN`、`UNAVAILABLE`、`STALE`；導航資格可包含 `ELIGIBLE`、`NOT_ELIGIBLE`、`UNKNOWN`、`STALE`。

路邊格位可以提供外部地圖導航入口，但必須同時滿足：目前回報有位、快照仍新鮮、一般汽車適用性已確認、座標有效、不是排除中的特殊格位，且資料覆蓋足以支持該格位顯示。若資料已過期，即使曾經回報有位，也不能顯示成「目前可停」或推薦導航；最多保留中性的查看位置，並明確標示車位狀態未確認。

`roadside` 與 `area` 的關係是：`area` 說明 300 公尺範圍和覆蓋是否完整，`roadside` 說明這個範圍內目前路邊觀測能顯示什麼。若 `area.roadsideCoverageStatus` 是 `PARTIAL` 或 `UNKNOWN`，`roadside` 仍可顯示已收錄且品質合格的格位位置，但不得暗示整個 300 公尺圓已完整查過，也不得讓 `predictions` 輸出完整區域機率。
### 已決定：`offStreet` 區塊欄位語意

`offStreet` 區塊只描述目的地 300 公尺範圍內已收錄路外停車場的目前觀測、來源狀態與逐場資訊，不直接放抵達時機率。抵達時「該停車場仍有汽車位」的機率統一放在 `predictions`，避免把目前剩餘位誤解成到達時仍有位。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 來源狀態 | `status` | 沿用統一來源狀態：`FRESH`、`STALE`、`REFRESHING`、`FAILED`、`COOLDOWN`、`NO_DATA`、`UNSUPPORTED` |
| 來源類型 | `sourceType` | 第一版為 `NTPC_OFF_STREET_AVAILABILITY`；未來接 TDX、其他縣市或業者來源時保留來源辨識 |
| 快照識別 | `snapshotId` | 指向主系統已發布的完整路外動態快照；不可指向未驗證或半套擷取結果 |
| 來源觀測時間 | `sourceObservedAt` | 若官方資料有提供真正觀測時間才填；沒有則為空，不以系統擷取時間偽裝來源時間 |
| 系統取得時間 | `systemFetchedAt` | 主系統成功取得並發布快照的時間；5 分鐘新鮮度先以此作試驗門檻 |
| 是否新鮮 | `isFresh` | 依來源類型與目前試驗門檻判斷；第一版超過 5 分鐘為 false |
| 區域目前摘要 | `summary` | 只描述目前觀測摘要，例如 300 公尺內已收錄場站數、目前有即時資料的場站數、目前仍有汽車位的場站數、未知或暫停提供動態的場站數 |
| 停車場清單 | `parkingLots` | 300 公尺內已收錄且位置可信的路外停車場清單；逐場呈現目前汽車剩餘位、狀態、距離與導航資格 |
| 使用者訊息 | `message` | 例如「正在更新路外停車場」、「路外資料已過期」、「目前沒有可確認的路外剩餘位」 |

`parkingLots` 中每筆停車場至少需要能表達：系統內停車場 ID、來源停車場 ID、名稱、經緯度或入口座標、距離目的地幾公尺、目前汽車剩餘位、總汽車位數或容量是否未知、動態狀態、觀測／取得時間、品質狀態、導航資格與不可導航原因。第一版只做汽車，不把機車位、身障位、充電位或其他特殊車位混入主要剩餘汽車位。

路外目前剩餘位必須區分三種情況：有效數字 `0`、有效正數，以及「目前無法提供即時動態」。例如新北市官方資料中 `AVAILABLE=-9` 這類特殊值不能當成 `0`，應轉成動態不可用或未知狀態，並保留原因碼。若來源過期、失敗、冷卻或未知，主要目前剩餘汽車位應為空；舊值只能放在附時間的參考欄位，不得作目前推薦或排序依據。

路外停車場可以提供外部地圖導航入口，但導航入口只代表前往該場站或入口，不承諾抵達時仍有車位。若目前剩餘位新鮮且大於 0，可標示「目前回報有位」並提供路線；若剩餘位為 0，仍可提供中性的查看位置或導航，但不能標成有位；若資料過期或未知，需標示「車位狀態未確認」。

`offStreet` 與 `area` 的關係是：`area` 說明 300 公尺範圍與路外目錄覆蓋是否完整，`offStreet` 說明這個範圍內目前路外場站能顯示什麼。若 `area.offStreetCoverageStatus` 是 `PARTIAL` 或 `UNKNOWN`，可以顯示已收錄且位置可信的單一場站，但不得暗示附近所有路外停車場都已完整收錄；`predictions` 只能對資料條件合格的單場輸出抵達時有位機率。
### 已決定：`predictions` 區塊欄位語意

`predictions` 區塊是第一版唯一承載「抵達時預測」的地方。它不放目前車位數，也不直接取代 `roadside` 或 `offStreet` 的目前觀測；它只回答在 `eta.estimatedArrivalAt` 這個目標時刻，依目前資料、歷史資料與模型條件，能否輸出可信的有位機率。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 預測狀態 | `status` | 候選值：`PENDING`、`READY`、`INSUFFICIENT_DATA`、`BLOCKED`、`FAILED`、`EXPIRED` |
| 目標抵達時間 | `targetArrivalAt` | 必須等於 `eta.estimatedArrivalAt`；不得用固定 10／30／60 分鐘替代 |
| ETA 參照 | `etaRef` | 指向 `eta` 區塊的本次 Google Routes 計算結果與計算時間 |
| 模型版本 | `modelVersion` | 可空；尚未有模型或只用基準規則時要明確標示 |
| 訓練資料摘要 | `trainingDataSummary` | 可空；可描述資料來源、時間範圍、樣本數與最後訓練時間 |
| 路邊預測 | `roadside` | 目的地 300 公尺內「抵達時至少一格一般汽車格位可停」的區域機率與狀態 |
| 路外預測 | `offStreet` | 逐停車場「抵達時仍有汽車位」的機率與狀態 |
| 阻擋或不足原因 | `reasons` | 例如缺 ETA、覆蓋不完整、資料過期、訓練資料不足、模型不可用、來源失敗 |
| 使用者訊息 | `message` | 給前端顯示的簡短說明，例如「目前資料不足，先顯示即時車位」 |

路邊預測第一版的產品語意是：在目的地直線 300 公尺範圍內，抵達時至少一個一般汽車路邊格位可停的機率。它是區域摘要，不是逐格位承諾。若 `area.roadsideCoverageStatus` 不是可支持完整 300 公尺判定的狀態，或路邊目前觀測／歷史訓練資料不足，`predictions.roadside.status` 應為 `INSUFFICIENT_DATA` 或 `BLOCKED`，不可輸出完整區域機率。

路外預測第一版的產品語意是：對 300 公尺內每個資料條件合格的路外停車場，估計抵達時仍有汽車位的機率。它是逐場結果，不是整個區域至少一場有位的合併機率。若某停車場目前動態為 `-9` 類暫無即時資料、沒有可靠位置、歷史資料不足或車種欄位不能確認，該場可保留在 `offStreet` 清單中，但 `predictions.offStreet` 對應項目應標示資料不足或不可預測。

`predictions` 必須保留「不能預測」的合法結果。若 `eta.status` 不是 `READY`，`targetArrivalAt` 不存在或已過期，整個 `predictions.status` 不得為 `READY`。若車位來源過期，仍可保留已知目前觀測作參考，但模型是否能推論要看資料品質規則；不能因為目前有位就直接產生高機率。

第一版面試展示可以先採簡單基準模型或可解釋規則作為模型候選，但 API 仍要用同一個 `predictions` 契約表示。若尚未通過時間切分回測，前端應顯示「預測資料不足」或「實驗中」，不得把未驗證分數包裝成正式準確預測。之後改成 ML 模型時，只更新模型版本、資料摘要與推論服務，不改前端主要契約。
### 已決定：`refresh` 區塊欄位語意

`refresh` 區塊只描述路邊與路外官方來源的刷新操作、共用擷取、冷卻、失敗重試與可再試時間，不放目前車位數，也不放抵達時預測。實際目前觀測分別放在 `roadside` 與 `offStreet`；抵達時機率放在 `predictions`。

| 欄位語意 | 建議欄位 | 說明 |
| --- | --- | --- |
| 整體刷新狀態 | `status` | 描述本次刷新請求或自動刷新觸發的整體結果；單一來源成功不代表另一來源成功 |
| 路邊刷新狀態 | `roadside` | 路邊來源的處置結果、是否啟動或加入共用擷取、可再試時間與原因 |
| 路外刷新狀態 | `offStreet` | 路外來源的處置結果、是否啟動或加入共用擷取、可再試時間與原因 |
| 觸發方式 | `trigger` | 候選值：`INITIAL_QUERY`、`VISIBLE_AUTO_REFRESH`、`MANUAL_REFRESH`、`BACKGROUND_SAMPLING` |
| 是否重算 ETA | `recalculateEta` | 第一版固定為 false；車位刷新不重新呼叫 Google Routes |
| 使用者訊息 | `message` | 例如「正在更新車位」、「資料仍在冷卻中」、「已沿用最新資料」 |

每個來源的刷新處置建議用同一套語意表示：`USED_FRESH_SNAPSHOT`、`JOINED_RUNNING_REFRESH`、`STARTED_REFRESH`、`COOLDOWN`、`BUDGET_BLOCKED`、`FAILED`、`SUCCEEDED`、`NO_DATA`、`UNSUPPORTED`。正式大小寫可在實作前微調，但語意需保留。`USED_FRESH_SNAPSHOT` 表示不呼叫官方 API；`JOINED_RUNNING_REFRESH` 表示已有其他使用者或分頁啟動同來源擷取；`STARTED_REFRESH` 才代表後端允許啟動新一輪官方來源呼叫。

每個來源至少需要能回：`sourceType`、`action`、`refreshInProgress`、`startedAt`、`finishedAt`、`lastSuccessfulFetchedAt`、`nextAllowedRefreshAt`、`canRefreshNow`、`failureReason`、`message`。`nextAllowedRefreshAt` 由後端根據來源最低間隔、失敗冷卻、預算保留量和官方硬限制決定；前端不得用自己的計時器繞過它。

可見頁面自動刷新與手動「更新車位」共用同一套後端來源級刷新控制。頁面可見且資料超過 5 分鐘試驗門檻時，前端可以對過期來源觸發一次受控刷新；頁面隱藏時停止自動刷新與進度輪詢。使用者手動更新可以加入既有工作，但同樣不能突破冷卻、預算或來源限制。

來源擷取失敗後，路邊與路外分開處理。單一來源失敗不抹掉另一來源已成功資料；失敗來源立即結束載入，回最後成功取得時間和可再試時間。冷卻後頁面仍可見時，最多共用自動重試一輪；若仍失敗，停止自動重試，之後只接受使用者手動更新且仍受後端限制。

`refresh` 成功只代表已取得並發布新的官方來源快照。新快照發布後，`roadside`、`offStreet` 可以更新目前觀測；若原 Google ETA 仍有效，`predictions` 可用同一 `eta.estimatedArrivalAt` 重新推論。`refresh` 不重新搜尋地點、不重算 Google ETA、不延長 `queryId` 有效期，也不把舊有位格位變成目前推薦。

### 已決定：查詢總回應頂層結構

查詢總回應第一版採固定頂層分層，讓前端能用穩定區塊渲染，也讓後端將行程、地理範圍、官方即時觀測、模型預測與刷新控制分開演進。頂層候選結構如下；欄位名稱可在實作前微調，但區塊職責先固定。

```json
{
  "query": {},
  "destination": {},
  "area": {},
  "eta": {},
  "roadside": {},
  "offStreet": {},
  "predictions": {},
  "refresh": {}
}
```

| 區塊 | 職責 |
| --- | --- |
| `query` | 本次查詢識別、建立時間、過期時間、整體是否仍有工作進行，以及輪詢停止依據 |
| `destination` | 使用者選定目的地的顯示名稱、座標、可選 Google `placeId`，以及是否位於新莊 MVP 支援範圍 |
| `area` | 目的地直線 300 公尺範圍、路邊／路外覆蓋狀態、是否部分跨出收錄範圍 |
| `eta` | Google Routes ETA、出發時間、預計抵達時間、計算時間、交通感知模式與失敗狀態 |
| `roadside` | 路邊 300 公尺區域摘要、來源狀態、目前格位明細；主要產品語意是「抵達時至少一格可停」 |
| `offStreet` | 路外停車場清單、每場來源狀態、目前汽車剩餘位與場站位置資訊 |
| `predictions` | 抵達時路邊區域機率、路外逐場機率、模型版本、目標 ETA 與資料不足原因 |
| `refresh` | 路邊／路外來源各自刷新狀態、冷卻、可再試時間、是否有共用刷新進行中 |

`roadside` 與 `offStreet` 可包含目前觀測與位置明細，但不直接決定模型是否可預測；預測統一放在 `predictions`，以避免前端把目前車位當作抵達時機率。`refresh` 不重新描述所有車位資料，只描述刷新操作與限制。這個分層不代表所有區塊會同時完成；各區塊仍可獨立 `pending`、完成、失敗或資料不足。

| 層次 | 建議欄位與狀態 | 用途 |
| --- | --- | --- |
| 查詢工作 | `queryId`、`expiresAt`；ETA／路邊／路外／預測各自 `pending`、`ready`、`failed` 或 `blocked` | 告訴 Vue 哪些區塊仍在進行，何時停止輪詢；某一來源失敗不抹掉其他區塊 |
| 範圍覆蓋 | 路邊／路外各自的完整、部分或未知；目的地圓心與 300 公尺；試點外另有整筆查詢狀態 | 新莊邊界或資料未收錄要與「目前 0 位」分開；具體欄位值待核定 |
| 目前觀測 | 每來源／每位置的有效、過期、未知與尚無觀測狀態；目前汽車剩餘數可空 | 有效 `0` 是數字；過期或未知時主要目前可用數為 `null`，以狀態區分原因；舊值只能帶原時間供次要參考 |
| 來源刷新 | `idle`、`running`、`succeeded`、`failed`、`cooldown`、`budget_blocked`；最後成功取得時間、可空來源觀測時間、原因碼 | 告知按「更新」後是否真的啟動擷取，以及何時可能再試；取得時間不冒充來源更新時間 |
| 抵達時預測 | `pending`、`ready`、`insufficient_data`、`failed`；可空機率、目標 ETA、模型版本 | 模型機率與官方目前觀測分開；資料不足時不補假機率 |

回應中的路邊區塊主要是「抵達時 300 公尺內至少一個合格格位可停」的機率，明細格位只代表**目前**有效且合格的觀測；路外按每場回剩餘汽車位與抵達時有位機率。兩者共享來源、時間與品質等共同欄位，但保留不同的結果粒度。前端只處理主系統統一欄位，不解讀新北市或 TDX 原始碼。

兩類推論需要的資料類別、拒絕預測條件與任意 300 公尺圓的訓練對齊風險，另見 `prediction-inference-contract.md`（Sprint 1 提案）。目前不因有了欄位草案就宣稱模型可用或已定 Java／Python 推論執行方式。

Sponsor 已決定：資料過期時，主要「目前可用數」回空值並標過期；來源未知同樣可為空值，但須有不同狀態／原因；有效 0 位回數字 0。前次數值若保留，僅能在附原資料時間的次要參考欄位，不得轉為目前格位推薦或導航資格。來源可信觀測時間與系統取得時間分開；重新取得舊來源觀測不視為來源剛更新。欄位名稱與來源時間可靠性判定仍待設計。
### 已決定：來源狀態欄位語意

每個官方車位來源，也就是路邊與路外，各自回傳同一組來源狀態欄位，讓前端不用解讀新北市或 TDX 原始碼。第一版建議欄位包含：`sourceType`、`status`、`currentAvailability`、`lastKnownAvailability`、`lastSuccessfulFetchedAt`、`lastSourceObservedAt`、`nextAllowedRefreshAt`、`refreshInProgress`、`canRefreshNow`、`failureReason` 與使用者可讀訊息。欄位名稱仍可在實作前調整，但語意先固定。

| `status` | 語意 | 目前車位呈現 |
| --- | --- | --- |
| `FRESH` | 有新鮮有效資料 | 可顯示目前可用數；有效 `0` 回數字 0 |
| `STALE` | 超過 5 分鐘試驗門檻或該來源的新鮮度門檻 | `currentAvailability` 為 `null`；舊值只可放 `lastKnownAvailability` 並附時間 |
| `REFRESHING` | 來源級共用刷新進行中 | 顯示正在更新；不另開一輪擷取 |
| `FAILED` | 本輪擷取失敗或工作到期 | 顯示目前車位無法確認及最後成功取得時間 |
| `COOLDOWN` | 冷卻、用量限制或暫不可再抓 | 顯示暫時無法更新，若可得則回 `nextAllowedRefreshAt` |
| `NO_DATA` | 來源有回應，但該項目沒有有效即時車位資料 | 顯示目前無即時車位資料，不等於 0 位 |
| `UNSUPPORTED` | 試點外或該來源不支援 | 顯示目前尚未支援此地區或此類資料 |

`failureReason` 用於工程判斷與必要的使用者文案分支，第一版候選值為 `SOURCE_TIMEOUT`、`SOURCE_ERROR`、`PARTIAL_RESPONSE`、`QUALITY_REJECTED`、`SOURCE_COOLDOWN`、`BUDGET_LIMITED`、`UNKNOWN`。這些原因碼不得直接取代 `status`；例如來源冷卻可同時有 `status=COOLDOWN` 與 `failureReason=SOURCE_COOLDOWN`。前端主要根據 `status` 決定顯示與互動，再用時間欄位和原因碼補充說明。

過期、失敗、冷卻或未知時，`currentAvailability` 不回舊數字。若產品需要顯示舊數字，只能放在 `lastKnownAvailability`，並同時附原資料時間與「僅供參考」語意；舊「有位」不可作目前格位推薦或外部導航依據。

第一版預測資格已決定：若某模型要求近期觀測，而該類觀測過期且受控刷新未成功，該預測區塊回 `insufficient_data` 與原因，機率為空值；目前觀測區塊仍獨立標 `stale`。不自動切到未驗證的歷史規律估計。路邊區域與路外單場逐一判斷，某一來源／場站資料不足不抹掉其他有效預測。實際容許觀測年齡與模型輸入契約仍待回測確認。

### 覆蓋與「沒有結果」的候選區分（部分已決定）

路邊、路外各自回傳目錄載入狀態、目的地 300 公尺圓的覆蓋狀態（完整／部分／未知）及符合一般汽車條件的已收錄位置數。只有目錄可用且覆蓋已核對時，數量 `0` 才能解讀為「目前收錄目錄中沒有符合條件的選項」；仍不能宣稱現場沒有車位。目錄取得失敗或覆蓋未知時，數量應為未知，不用空陣列假裝查詢完成。跨新莊邊界的圓若鄰區未收錄，標為部分覆蓋，即使已收錄部分數量為 `0`，也必須提示另一部分未涵蓋。目錄有位置、但即時來源失敗時，保留位置並將車位狀態標為未知；這不是「沒有停車選項」。

Sponsor 已決定：目的地位於新莊 MVP 支援範圍外時，先回明確的「目前尚未支援此地區」，前端保留使用者選定的地點並可改搜；不呼叫 Google Routes ETA 或官方車位來源，也不回通用空清單。目的地在新莊內、300 公尺圓跨區時仍可回答已收錄部分，但須標示覆蓋不完整。試點外回應的 HTTP 表達與固定錯誤碼、邊界資料尚待核定。

跨界的預測顯示已由 Sponsor 決定：路邊完整 300 公尺圓的抵達時機率不可由已收錄部分冒充，故暫不輸出；路外逐場預測只要該場資料及模型各自合格仍可輸出，附場站清單可能不完整的提示。ETA 到目的地照常計算。實際邊界資料、覆蓋驗證與 HTTP／欄位名稱待核定。

**覆蓋欄位語意**：Sponsor 已同意每種停車類型須區分完整、部分與未知。覆蓋未知時，完整 300 公尺路邊機率暫不顯示；已核實位置與合格路外單場預測可保留，但附近清單的覆蓋狀態須另標明。候選欄位值為 `complete`（經核對，查詢圓完整位於該類型的收錄範圍）、`partial`（已知有一部分未收錄）及 `unknown`（尚無足夠依據判斷）；試點外候選為整筆查詢的 `unsupported_area`，不拿空陣列表示。覆蓋狀態與「目錄是否成功載入」「即時車位是否新鮮」「模型能否預測」分層；完整地理覆蓋不保證每個格位都有有效即時觀測。`complete` 必須有可信覆蓋邊界或同等可核對依據，不能只因目的地地址寫新莊就宣稱完整。英文狀態值及邊界資料仍是設計提案。

### 已決定：Response 必填與可空規則

查詢回應第一版採固定頂層結構：`query`、`destination`、`area`、`eta`、`roadside`、`offStreet`、`predictions`、`refresh` 八個區塊都要存在。前端可以依固定區塊渲染畫面，不需要先判斷某個區塊是否缺失。區塊內資料是否可用，由該區塊的 `status`、時間欄位與原因碼說明。

| 區塊 | 固定存在 | 主要不可空欄位 | 可空或可為空集合的欄位 | 規則 |
| --- | --- | --- | --- | --- |
| `query` | 是 | `queryId`、`status`、`createdAt`、`serverTime`、`expiresAt`、`hasPendingWork`、`pendingSections`、`finishedSections`、`requiresReplan` | `nextPollAfterSeconds` | `hasPendingWork=false` 時 `nextPollAfterSeconds` 可為 `null` |
| `destination` | 是 | `displayName`、`latitude`、`longitude`、`selectionSource`、`supportStatus` | `formattedAddress`、`placeId`、`unsupportedReason`、`message` | 目的地支援與否都要保留使用者選定地點；試點外不啟動上游 |
| `area` | 是 | `centerLatitude`、`centerLongitude`、`radiusMeters`、`distanceMode`、`coverageStatus` | `catalogCounts`、`coverageNotes` | 覆蓋未知時 `catalogCounts` 可為 `null`；不能用 `0` 冒充沒有收錄 |
| `eta` | 是 | `status`、`provider`、`trafficMode` | `durationSeconds`、`estimatedArrivalAt`、`calculatedAt`、`failureReason`、`message` | 只有 `status=READY` 時才需要有 `durationSeconds` 與 `estimatedArrivalAt` |
| `roadside` | 是 | `status`、`sourceType` | `snapshotId`、`summary`、`spots`、`sourceObservedAt`、`systemFetchedAt`、`message` | `spots` 可以是空陣列，但空陣列不等於附近沒有車格；要看覆蓋與狀態 |
| `offStreet` | 是 | `status`、`sourceType` | `snapshotId`、`summary`、`parkingLots`、`sourceObservedAt`、`systemFetchedAt`、`message` | `parkingLots` 可以是空陣列，但空陣列不等於附近沒有停車場；要看覆蓋與目錄狀態 |
| `predictions` | 是 | `status` | `targetArrivalAt`、`modelVersion`、`trainingDataSummary`、`roadside`、`offStreet`、`reasons`、`message` | ETA 未完成、資料不足或模型不可用時，機率欄位為 `null` 或不出現；不得補假機率 |
| `refresh` | 是 | `status`、`trigger`、`recalculateEta` | `roadside`、`offStreet`、`message` | 建立查詢與刷新端點都回刷新狀態；`recalculateEta` 第一版固定 false |

數值欄位要區分三種情況：有效 `0`、未知／不適用、尚未完成。有效 `0` 必須回數字 `0`；未知或不適用用 `null`，並搭配 `status` 或原因碼；尚未完成用 `PENDING`／`REFRESHING` 等狀態表達。前端不得只依 `null` 或空陣列判斷顯示文案。

清單欄位第一版採固定存在但可為空陣列的方式，例如 `roadside.spots: []`、`offStreet.parkingLots: []`、`predictions.offStreet: []`。空陣列只表示目前這個區塊沒有可列出的項目；是否代表附近沒有收錄資料，必須再看 `area` 覆蓋狀態、目錄數量與來源狀態。

時間欄位需要保留語意差異：`serverTime` 是主系統回應時間；`createdAt` 是查詢建立時間；`expiresAt` 是 `queryId` 到期時間；`calculatedAt` 是 Google ETA 完成時間；`systemFetchedAt` 是主系統成功取得並發布車位快照時間；`sourceObservedAt` 只有官方來源提供真正觀測時間時才填。不得用系統取得時間偽裝成來源觀測時間。

機率欄位只在 `predictions` 對應項目 `status=READY` 時出現有效數字。若 `status=INSUFFICIENT_DATA`、`BLOCKED`、`FAILED` 或 `EXPIRED`，`probability` 應為 `null` 或省略，並提供 `reasons` 或 `message`。前端顯示「預測資料不足」時，不應把目前車位數轉成機率。
## 查詢 API 範例回應（欄位形狀草案）

以下 JSON 是前後端對齊用的草案範例，不代表正式欄位已鎖死。範例中的座標、名稱、ID、時間、數量與機率都是假資料；正式實作前仍需整理欄位命名、大小寫、可空規則、分頁與錯誤碼。

### 範例 1：建立查詢後的快速回應

情境：使用者已選定新莊目的地、起點與出發時間；後端完成輸入檢查與查詢狀態建立後快速回 `202 Accepted`。此時目的地與 300 公尺範圍已可顯示，ETA、來源快照與預測仍在進行中。

```json
{
  "query": {
    "queryId": "pq_20260925_abc123",
    "status": "RUNNING",
    "createdAt": "2026-09-25T10:00:00+08:00",
    "serverTime": "2026-09-25T10:00:01+08:00",
    "expiresAt": "2026-09-25T12:00:00+08:00",
    "hasPendingWork": true,
    "nextPollAfterSeconds": 2,
    "pendingSections": ["ETA", "ROADSIDE", "OFF_STREET", "PREDICTIONS"],
    "finishedSections": [],
    "requiresReplan": false
  },
  "destination": {
    "displayName": "新莊體育館",
    "formattedAddress": "新北市新莊區中華路一段75號",
    "placeId": "google-place-id-example",
    "latitude": 25.0391,
    "longitude": 121.4529,
    "selectionSource": "GOOGLE_PLACE",
    "supportStatus": "SUPPORTED",
    "serviceArea": "NEW_TAIPEI_XINZHUANG"
  },
  "area": {
    "centerLatitude": 25.0391,
    "centerLongitude": 121.4529,
    "radiusMeters": 300,
    "distanceMode": "STRAIGHT_LINE",
    "serviceArea": "NEW_TAIPEI_XINZHUANG",
    "coverageStatus": "UNKNOWN",
    "roadsideCoverageStatus": "UNKNOWN",
    "offStreetCoverageStatus": "UNKNOWN",
    "crossesServiceBoundary": false,
    "catalogCounts": null
  },
  "eta": {
    "status": "PENDING",
    "provider": "GOOGLE_ROUTES",
    "trafficMode": "TRAFFIC_AWARE_OPTIMAL",
    "message": "正在估算抵達時間"
  },
  "roadside": {
    "status": "REFRESHING",
    "sourceType": "NTPC_ROADSIDE_SPOT",
    "summary": null,
    "spots": [],
    "message": "正在更新路邊車格"
  },
  "offStreet": {
    "status": "REFRESHING",
    "sourceType": "NTPC_OFF_STREET_AVAILABILITY",
    "summary": null,
    "parkingLots": [],
    "message": "正在更新路外停車場"
  },
  "predictions": {
    "status": "PENDING",
    "targetArrivalAt": null,
    "roadside": { "status": "PENDING" },
    "offStreet": [],
    "message": "等待 ETA 與車位資料"
  },
  "refresh": {
    "status": "RUNNING",
    "trigger": "INITIAL_QUERY",
    "recalculateEta": false,
    "roadside": {
      "sourceType": "NTPC_ROADSIDE_SPOT",
      "action": "STARTED_REFRESH",
      "refreshInProgress": true
    },
    "offStreet": {
      "sourceType": "NTPC_OFF_STREET_AVAILABILITY",
      "action": "STARTED_REFRESH",
      "refreshInProgress": true
    }
  }
}
```

### 範例 2：各區塊完成或終止後的查詢回應

情境：Google ETA 已完成；路邊與路外來源有新鮮快照；路邊覆蓋完整且模型可輸出區域機率；其中一個路外停車場可預測，另一個因目前無即時動態而只顯示位置與狀態。

```json
{
  "query": {
    "queryId": "pq_20260925_abc123",
    "status": "COMPLETED",
    "createdAt": "2026-09-25T10:00:00+08:00",
    "serverTime": "2026-09-25T10:00:08+08:00",
    "expiresAt": "2026-09-25T12:00:00+08:00",
    "hasPendingWork": false,
    "nextPollAfterSeconds": null,
    "pendingSections": [],
    "finishedSections": ["ETA", "ROADSIDE", "OFF_STREET", "PREDICTIONS"],
    "requiresReplan": false
  },
  "eta": {
    "status": "READY",
    "originSummary": "目前位置",
    "destinationRef": "destination",
    "departureAt": "2026-09-25T10:00:00+08:00",
    "calculatedAt": "2026-09-25T10:00:03+08:00",
    "durationSeconds": 1380,
    "estimatedArrivalAt": "2026-09-25T10:23:00+08:00",
    "trafficMode": "TRAFFIC_AWARE_OPTIMAL",
    "provider": "GOOGLE_ROUTES",
    "requiresReplan": false
  },
  "roadside": {
    "status": "FRESH",
    "sourceType": "NTPC_ROADSIDE_SPOT",
    "snapshotId": "rs_snap_001",
    "systemFetchedAt": "2026-09-25T10:00:05+08:00",
    "isFresh": true,
    "summary": {
      "eligibleSpotCount": 42,
      "observedSpotCount": 40,
      "availableSpotCount": 3,
      "unknownSpotCount": 2,
      "displayableAvailableSpotCount": 2
    },
    "spots": [
      {
        "spotId": "spot_internal_001",
        "sourceSpotId": "NTPC_CELL_001",
        "latitude": 25.0398,
        "longitude": 121.4519,
        "distanceMeters": 110,
        "status": "AVAILABLE",
        "isCarEligible": true,
        "fetchedAt": "2026-09-25T10:00:05+08:00",
        "navigationEligibility": "ELIGIBLE"
      }
    ]
  },
  "offStreet": {
    "status": "FRESH",
    "sourceType": "NTPC_OFF_STREET_AVAILABILITY",
    "snapshotId": "os_snap_001",
    "systemFetchedAt": "2026-09-25T10:00:06+08:00",
    "isFresh": true,
    "summary": {
      "parkingLotCount": 2,
      "lotsWithFreshAvailability": 1,
      "lotsWithAvailableCarSpaces": 1,
      "lotsWithUnknownAvailability": 1
    },
    "parkingLots": [
      {
        "parkingLotId": "lot_internal_001",
        "sourceParkingLotId": "NTPC_LOT_001",
        "name": "新莊範例停車場",
        "latitude": 25.0389,
        "longitude": 121.4538,
        "distanceMeters": 95,
        "availableCarSpaces": 12,
        "totalCarSpaces": 120,
        "availabilityStatus": "AVAILABLE",
        "navigationEligibility": "ELIGIBLE"
      },
      {
        "parkingLotId": "lot_internal_002",
        "sourceParkingLotId": "NTPC_LOT_002",
        "name": "新莊另一範例停車場",
        "latitude": 25.0403,
        "longitude": 121.4541,
        "distanceMeters": 180,
        "availableCarSpaces": null,
        "totalCarSpaces": 80,
        "availabilityStatus": "NO_REALTIME_AVAILABILITY",
        "rawAvailabilityHint": "-9",
        "navigationEligibility": "UNKNOWN"
      }
    ]
  },
  "predictions": {
    "status": "READY",
    "targetArrivalAt": "2026-09-25T10:23:00+08:00",
    "etaRef": "eta",
    "modelVersion": "baseline-2026-09-25",
    "roadside": {
      "status": "READY",
      "probability": 0.62,
      "meaning": "ARRIVAL_TIME_AT_LEAST_ONE_CAR_SPOT_AVAILABLE_WITHIN_300M"
    },
    "offStreet": [
      {
        "parkingLotId": "lot_internal_001",
        "status": "READY",
        "probability": 0.78,
        "meaning": "ARRIVAL_TIME_HAS_CAR_SPACE"
      },
      {
        "parkingLotId": "lot_internal_002",
        "status": "INSUFFICIENT_DATA",
        "probability": null,
        "reasons": ["NO_REALTIME_AVAILABILITY"]
      }
    ]
  },
  "refresh": {
    "status": "IDLE",
    "trigger": "INITIAL_QUERY",
    "recalculateEta": false,
    "roadside": {
      "sourceType": "NTPC_ROADSIDE_SPOT",
      "action": "SUCCEEDED",
      "refreshInProgress": false,
      "lastSuccessfulFetchedAt": "2026-09-25T10:00:05+08:00",
      "nextAllowedRefreshAt": "2026-09-25T10:05:05+08:00",
      "canRefreshNow": false
    },
    "offStreet": {
      "sourceType": "NTPC_OFF_STREET_AVAILABILITY",
      "action": "SUCCEEDED",
      "refreshInProgress": false,
      "lastSuccessfulFetchedAt": "2026-09-25T10:00:06+08:00",
      "nextAllowedRefreshAt": "2026-09-25T10:05:06+08:00",
      "canRefreshNow": false
    }
  }
}
```

### 範例設計重點

- `query` 告訴前端是否還要輪詢，不代表每個上游都成功。
- `eta.estimatedArrivalAt` 是 `predictions.targetArrivalAt` 的唯一來源。
- `roadside.summary.availableSpotCount` 和 `offStreet.parkingLots[].availableCarSpaces` 都是目前觀測，不是抵達時承諾。
- `predictions` 可以對部分項目回機率、部分項目回資料不足。
- `refresh` 的 `nextAllowedRefreshAt` 是後端保護官方 API 的依據，前端不能自行繞過。
## 已決定：正式 API 路由與 HTTP 語意草案

第一版主系統只需要三個對外查詢端點。地點搜尋候選與地圖顯示由前端 Google Maps／Places 處理；主後端只接收已選定的起點、目的地座標與出發時間。後端不接收前端自算 ETA、目前車位數或模型機率。

| 端點 | 用途 | 主要副作用 | 成功 HTTP |
| --- | --- | --- | --- |
| `POST /api/v1/parking-queries` | 建立一次明確行程查詢 | 建立短期 `queryId`，安排 Google ETA，依需要安排路邊／路外受控刷新 | `202 Accepted` |
| `GET /api/v1/parking-queries/{queryId}` | 讀取同一次查詢目前狀態 | 無上游副作用；不得呼叫 Google 或官方車位來源 | `200 OK` |
| `POST /api/v1/parking-queries/{queryId}/refresh` | 使用者手動更新或可見頁面受控刷新車位 | 只檢查路邊／路外來源級刷新控制；可能沿用、加入、啟動或拒絕刷新 | `202 Accepted` 或 `200 OK` |

`POST /api/v1/parking-queries` 使用非同步語意。後端完成輸入驗證、試點範圍判定與短期查詢狀態建立後快速回應，不等待 Google Routes 或官方車位來源全部完成。若已能立刻判斷目的地在新莊 MVP 外，回穩定的輸入範圍錯誤，不啟動 Google Routes 或車位來源。

`GET /api/v1/parking-queries/{queryId}` 是純讀取。它可以把過期背景工作轉成終止狀態，避免前端無限等待；但不得因為讀取進度而新增 Google Routes 或官方車位來源請求。前端只在 `query.hasPendingWork=true` 且頁面可見時有限輪詢。

`POST /api/v1/parking-queries/{queryId}/refresh` 不重新搜尋地點、不重算 ETA、不延長 `queryId`，也不重送搜尋文字。它只針對本次查詢已知範圍內的路邊與路外來源檢查是否可刷新。若快照仍新鮮，回 `USED_FRESH_SNAPSHOT`；若已有來源級刷新進行中，回 `JOINED_RUNNING_REFRESH`；若冷卻、預算或來源限制中，回 `COOLDOWN` 或 `BUDGET_BLOCKED` 與可再試時間。

HTTP 狀態只表示主系統是否成功處理這次 API 請求，不代表 Google、政府來源或模型都成功。Google ETA 失敗、路邊來源失敗、路外來源冷卻、模型資料不足，應放在 `eta`、`roadside`、`offStreet`、`predictions`、`refresh` 的區塊狀態中；只要主系統能正常回應查詢狀態，HTTP 仍可為 `200` 或 `202`。

| 情境 | 建議 HTTP | 回應重點 |
| --- | --- | --- |
| 建立查詢已接受 | `202 Accepted` | 回 `queryId`、目的地、範圍與各區塊初始狀態 |
| 讀取查詢成功 | `200 OK` | 回完整分層狀態；部分區塊失敗仍用區塊狀態表達 |
| 手動刷新已接受或已處置 | `202 Accepted` 或 `200 OK` | 已啟動／加入刷新可用 `202`；只沿用新鮮快照或冷卻拒絕可用 `200` |
| 欄位缺漏、格式錯誤、經緯度不合法 | `400 Bad Request` | 固定錯誤碼，例如 `INVALID_REQUEST`、`INVALID_COORDINATE` |
| 目的地不在新莊 MVP | `422 Unprocessable Entity` | 固定錯誤碼 `UNSUPPORTED_AREA`；不啟動 Google Routes 或車位來源 |
| 指定出發時間在過去或超出可支援範圍 | `422 Unprocessable Entity` | 固定錯誤碼 `INVALID_DEPARTURE_TIME` |
| 同一使用者或同一來源請求過於頻繁 | `429 Too Many Requests` | 回可再試時間；不得因此呼叫上游 |
| `queryId` 已過期 | `410 Gone` | 提示重新規劃；不自動建立新查詢 |
| `queryId` 不存在或不應揭露 | `404 Not Found` | 不透露是否曾存在；前端提示重新查詢 |
| 主系統自身錯誤 | `500` 或 `503` | 只在主系統無法建立或讀取查詢時使用；上游單一來源失敗不用整頁 500 |

錯誤回應也應使用穩定格式，方便 Vue 統一顯示。例如：`error.code`、`error.message`、`error.details`、`serverTime`。錯誤訊息給使用者看，錯誤碼給前端判斷流程；不可要求前端解析中文訊息決定行為。

第一版端點不提供「公開分享查詢」或「長期監控到未來出發時間」。`queryId` 是短期互動狀態 ID，不是預約，也不是可公開分享的永久連結。若使用者更改起點、目的地或出發時間，必須建立新的查詢，而不是在原 `queryId` 上偷偷改條件。
### 已決定：錯誤碼與錯誤回應格式

主系統錯誤回應採固定 `error` 物件，讓前端依穩定錯誤碼決定流程，不解析中文或英文訊息。錯誤回應只用於主系統無法接受或讀取這次 API 請求；Google、政府來源或模型的單一區塊失敗，仍放在正常查詢回應的區塊狀態內。

```json
{
  "error": {
    "code": "UNSUPPORTED_AREA",
    "message": "目前尚未支援此地區",
    "details": {
      "serviceArea": "NEW_TAIPEI_XINZHUANG"
    },
    "retryable": false,
    "nextAllowedAt": null
  },
  "serverTime": "2026-09-25T10:00:01+08:00"
}
```

| 欄位 | 必填 | 語意 |
| --- | --- | --- |
| `error.code` | 是 | 給前端判斷流程的穩定錯誤碼，不依賴顯示文字 |
| `error.message` | 是 | 給使用者或開發階段顯示的簡短訊息；正式文案可由前端依錯誤碼轉換 |
| `error.details` | 否 | 結構化補充資料，例如欄位名稱、支援區域或限制原因；不得放敏感資訊 |
| `error.retryable` | 是 | 告訴前端使用者是否可稍後再試；輸入修正類錯誤通常為 false |
| `error.nextAllowedAt` | 否 | 限流、冷卻或暫時不可用時可提供；沒有明確時間則為 `null` |
| `serverTime` | 是 | 主系統回應時間，供前端避免依本機時間推論 |

第一版錯誤碼候選如下；正式大小寫可在 OpenAPI 定稿時固定，但語意不應改變。

| HTTP | 錯誤碼 | 使用情境 | 前端建議行為 |
| --- | --- | --- | --- |
| `400` | `INVALID_REQUEST` | JSON 格式錯誤、必填欄位缺漏、型別不符 | 顯示一般輸入錯誤，保留使用者已輸入內容 |
| `400` | `INVALID_COORDINATE` | 經緯度缺漏、超出合法範圍或起訖點內容與類型不一致 | 要求使用者重新選擇地點或重新允許定位 |
| `400` | `INVALID_REQUEST_TOKEN` | `requestToken` 缺漏、太長、字元不合法或已不可接受 | 重新產生規劃請求，不自動重複送舊 token |
| `422` | `UNSUPPORTED_AREA` | 目的地不在新莊 MVP 服務範圍 | 保留目的地，顯示目前尚未支援此地區；不輪詢 |
| `422` | `INVALID_DEPARTURE_TIME` | 指定出發時間在過去、格式缺時區或超出可支援範圍 | 要求使用者重新選擇出發時間 |
| `429` | `RATE_LIMITED` | 建立查詢或讀取／刷新太頻繁 | 停止重試到 `nextAllowedAt` 或一段建議時間後 |
| `410` | `QUERY_EXPIRED` | `queryId` 已過期 | 清除本次查詢狀態，提示重新規劃 |
| `404` | `QUERY_NOT_FOUND` | `queryId` 不存在、格式不合法或不應揭露是否存在 | 提示重新查詢，不透露是否曾存在 |
| `503` | `SERVICE_TEMPORARILY_UNAVAILABLE` | Redis、MySQL 或主系統必要元件暫不可用，無法處理查詢 | 顯示服務暫時不可用，可稍後再試 |
| `500` | `INTERNAL_ERROR` | 未預期主系統錯誤 | 顯示一般錯誤，記錄追蹤資訊；不要自動密集重試 |

`POST /api/v1/parking-queries/{queryId}/refresh` 若只是來源冷卻、預算阻擋或官方來源失敗，通常不使用上述錯誤回應，而是在 `refresh` 區塊回 `COOLDOWN`、`BUDGET_BLOCKED`、`FAILED` 等處置結果。只有 `queryId` 無效、請求過於頻繁或主系統無法處理刷新請求時，才使用錯誤回應。

錯誤回應不要包含 Google API 金鑰、官方來源原始錯誤全文、堆疊追蹤、資料庫錯誤細節或使用者精確位置以外的敏感資訊。若需要工程追蹤，之後可加入 `traceId` 或 `requestId`；這類追蹤 ID 可放在錯誤回應與伺服器 log 中，但不取代穩定錯誤碼。
## HTTP 與失敗語意（候選）

- 建立查詢若採非同步，成功接受可用 `202 Accepted` 並附 `queryId` 與當時已完成結果；讀取進度成功用 `200 OK`，即使路邊來源失敗，該來源狀態仍在回應本體。HTTP 成功只表示主系統有成功處理這次讀取，不代表所有上游都成功。
- 輸入無效用 4xx 並回固定錯誤碼與可理解訊息。查詢已過期可用 `410 Gone`；若根本不存在、已過期或不應揭露其存在，需在安全與前端體驗間定一致規則。
- 官方來源失敗、冷卻或預算阻擋以**區塊狀態**呈現，不讓整個行程查詢直接成為通用 500。只有主系統自身無法建立／讀取查詢才回服務錯誤。
- 讀進度與手動更新需有限流與停止條件，避免同一使用者無限輪詢或反覆按鈕造成 Redis、MySQL 或上游負載。具體速率待實測。

## 前端必須能驗收的六個例子

1. 新查詢無快照：先看到目的地、300 公尺圈及已收錄可信位置；ETA 和兩種車位各有自己的進度；新快照完整發布後自動更新。
2. 路邊成功、路外失敗：保留路邊結果；路外位置若可信仍可見，但目前剩餘位標示無法確認。
3. 有效 `0`、觀測未知、資料過期、沒有收錄：四種狀態有不同值與文案，不能都回 `0` 或空陣列。
4. ETA 失敗：仍顯示可用的目前車位；抵達時預測標示暫時無法估算，不用舊 ETA 代替。
5. 手動更新時快照仍新或來源冷卻中：回明確原因，不呼叫 Google，也不保證新增官方請求。
6. Redis 查詢鍵過期或 Spring Boot 重啟中斷在途工作：畫面結束輪詢並提示重新查詢；MySQL 已發布的完整快照不因而變新或消失。

## 待 Sponsor／實測收斂

- 路由與欄位名稱、`queryId` 期限、輪詢次數和速率、前景／背景等待上限、錯誤碼及查詢過期的 HTTP 表達。
- Google 地點搜尋在瀏覽器或後端的實際分工、Routes 失敗／重算條件；這些不改變「更新車位不重做 ETA」的已定互動。
- 是否需要額外的只讀「目前公開快照」端點作 Redis 暫失時降級；第一版若不用，須明確向使用者回服務暫不可用。

## 2026-09-24 查詢中斷、頁面重整與繼續使用（提案）

| 情境 | Vue 與主系統的候選行為 | 上游費用／資料語意 |
| --- | --- | --- |
| 使用者重整同一瀏覽器分頁，`queryId` 仍有效 | Vue 可在該分頁的短期瀏覽器儲存保留 `queryId`；載入後只呼叫讀取進度，還原目的地、ETA 與已完成的車位區塊，並依狀態繼續有限輪詢 | `GET` 只讀；重整本身不重新做 Google ETA 或政府擷取 |
| `queryId` 已過期／查不到 | 清除舊 ID，顯示「本次查詢已結束，請重新查詢」；由使用者重新送出已選擇或重新選擇的起訖點 | 不在背景默默建立新查詢；重新查詢可能重新消耗 Google ETA 請求，車位仍依共用快照／刷新規則 |
| 路邊成功而路外失敗 | 路邊維持已完成；路外標示失敗與最後成功取得時間。按「更新車位」時只對需要且允許的來源重試，另一類新鮮快照不重抓 | 單一來源失敗不令整頁失敗；舊有位不作目前推薦 |
| Google ETA 失敗 | 位置及有效目前車位繼續顯示；抵達時預測標示缺 ETA。使用者明確選擇重新規劃，才新建一次行程查詢 | 車位來源若仍新鮮可共用，不因 Google 失敗立刻重抓；ETA 重算可能計費 |
| Spring Boot 在工作途中重啟 | Redis 可能仍有 `pending`；每個背景工作須有最後期限／工作版本或其他可辨識的中斷訊號，逾期轉終止失敗，不讓前端無限等待 | MySQL 已發布的來源快照維持原版本及時間；未完成候選快照不發布 |
| Redis 鍵過期或 Redis 暫失 | 本次 `queryId` 無法恢復時停止輪詢，顯示重新查詢或暫時無法查詢；MySQL 中的完整快照仍存在 | 不把 Redis TTL 當作政府資料時間，也不因 Redis 失效立即多抓官方 API |

短期瀏覽器儲存只放不易猜測的 `queryId` 作同分頁恢復候選，不把 ID 放進分享網址；是否另暫存起訖點供過期後重查，需兼顧使用者方便與位置隱私。`queryId` 有效期限、背景工作最後期限與 Vue 輪詢上限是三種不同時間，須依實際來源耗時和未來出發行程的使用情境決定。第一版的 `queryId` 代表**目前互動查詢**，不代表會替使用者持續監控到未來抵達時間；若隔很久才回來，須明確重新規劃或更新。重新整理車位仍沿用原 ETA；若 ETA 本身已久未重算，前端顯示其計算時間並提供重新規劃入口，不能把它當成新 ETA。

驗收時模擬同分頁重整、Google 失敗、單一政府來源失敗、Redis 鍵過期、Spring Boot 在候選快照寫入期間重啟；確認畫面最終停止等待，保留其他有效結果，且重整頁面不新增上游請求。

## 2026-09-24 三種期限與輪詢節奏（候選，待主系統量測）

建立查詢採非同步回應：完成輸入驗證與本地可信位置查詢後，即回 `queryId` 和當時已完成的欄位；不為了等官方完整快照而固定阻塞 10 秒。早期「約 10 秒前景等待」是另一種互動候選，已由此契約的立即回應方向取代。下表數字是**試作初值**，不是來源效能或正式服務承諾。

| 時間 | 候選初值 | 作用 |
| --- | --- | --- |
| 單一 ETA 工作最後期限 | 15 秒 | 到期轉失敗；目前車位區塊仍可完成 |
| 每來源即時抓取與完整快照發布的工作最後期限 | 60 秒 | 到期須停止本輪並標失敗，不發布半套資料；來源實際 p95 未量到，實作前可調整 |
| Vue 進度輪詢 | 前 10 秒每 2 秒一次，之後每 5 秒一次；只在仍有 `pending` 且頁面可見時 | 最多約 15 次／分鐘／查詢；所有工作終止、離開頁面或到期限便停止。回到頁面時先讀一次，不補做離開期間的每次輪詢 |
| `queryId` 在 Redis 的絕對有效期 | 暫以建立後 2 小時為討論起點 | 支援同一趟短期互動；到期提示重新規劃，不能視為到未來 ETA 的持續監控。需依位置隱私、預計行程長度及 Redis 容量調整 |

背景工作期限、前端輪詢停止與 `queryId` 期限各自有獨立用途。`expiresAt` 與各區塊 `deadlineAt` 應讓前端知道何時停止等待；若 Spring Boot 重啟而 Redis 仍留 `pending`，讀取時可依已過的工作最後期限轉為失敗。來源刷新到期後才返回的結果不得把已失敗輪次重新發布；需要新的一輪受控刷新。若使用者隔天才出發，當天的 `queryId` 不應保存到隔天；回來時需重新規劃並取得新的 ETA。

**已決定的可見頁面刷新行為**：結果頁可見且某來源完整快照超過 5 分鐘試驗門檻時，Vue 對該來源發起一次受控車位刷新請求；路邊與路外分別判斷。頁面隱藏時暫停自動刷新與進度輪詢。重新顯示時先做一次只讀 `GET`，依最新取得時間、刷新狀態與可再試時間決定是否需要一次 `POST`；頁面重整也先走只讀恢復，不能直接引發上游請求。後端回應需能區分沿用新鮮快照、加入既有刷新、新啟動、冷卻中、預算阻擋與失敗，並在適用時提供 `nextAllowedAt` 候選欄位。冷卻／失敗時不得因前端定時器反覆送 `POST`；日後可再試時要重新檢查最新狀態。多位使用者共用來源級單輪刷新。手動「更新車位」仍可用，但同樣受限制；只讀進度 `GET` 不觸發政府或 Google 呼叫。刷新車位不重算 Google ETA，舊 ETA 仍標示計算時間。`queryId` 已過期時不自動建立新行程查詢。具體去重、最短間隔、錯誤碼與前端節奏待量測後定案。

## 2026-09-28 S2-017 已發布快照查詢欄位

`parking-queries` 目前已能讀取 `source_state.active_snapshot` 指向的 `PUBLISHED` 快照，將最新已發布觀測接回路邊與路外結果。查詢流程不會在 S2-017 重新抓官方 API，也不讀 `CANDIDATE` 或 `REJECTED` snapshot；官方來源擷取與受控刷新由後續 S2-018 處理。

路邊 `roadside` 區塊的主要語意：

| 欄位 | 語意 |
| --- | --- |
| `status` | `READY`、`STALE`、`UNKNOWN`、`NO_REALTIME_SNAPSHOT`、`NO_OBSERVATION`、`NO_CATALOG_RESULTS` |
| `source` | 使用已發布快照時為 `NTPC_OPEN_DATA`；尚無快照或只有本地目錄時為 `LOCAL_CATALOG_SEED` |
| `systemCollectedAt` | 該已發布快照的系統取得時間；沒有快照時為 `null` |
| `currentlyAvailableSpaces` | 僅在新鮮 `READY` 且有已知觀測時提供；過期、未知、無快照時為 `null` |
| `availableSpots[]` | 僅列出新鮮、確認可用且可導航的路邊汽車格位；過期或未知時為空陣列 |
| `availableSpots[].availabilityStatus` | 目前只會對可推薦格位回 `AVAILABLE` |
| `availableSpots[].systemCollectedAt` | 該格位所屬快照的系統取得時間 |

路外 `offStreet.parkingLots[]` 新增下列狀態欄位，讓前端不用用 `availableCarSpaces` 猜狀態：

| 欄位 | 語意 |
| --- | --- |
| `availabilityStatus` | `AVAILABLE`、`FULL`、`UNKNOWN`、`OUT_OF_SERVICE`、`STALE`、`NO_OBSERVATION`、`NO_REALTIME_SNAPSHOT` |
| `availableCarSpaces` | 僅在新鮮 `AVAILABLE` 或 `FULL` 時提供；`FULL` 可為有效 `0` |
| `systemCollectedAt` | 該停車場所屬快照的系統取得時間 |
| `message` | 給前端或開發階段顯示的狀態說明 |

前端判斷規則：

- `0` 位：`availabilityStatus=FULL` 且 `availableCarSpaces=0`，代表有效觀測，不是未知。
- 未知：`availabilityStatus=UNKNOWN`，`availableCarSpaces=null`。
- 過期：`status=STALE` 或停車場 `availabilityStatus=STALE`，主要目前可用數必須視為不可用。
- 尚未有即時資料：`NO_REALTIME_SNAPSHOT`，代表有目錄位置但還沒有已發布的即時快照。
- 未收錄：`NO_CATALOG_RESULTS`，代表 300 公尺內沒有本地目錄資料，不能宣稱現場沒有車位。

## 2026-09-29 S2-022 已實作 API 契約基線

S2-022 已把查詢端點收斂為目前可測的後端契約基線；以下為已實作內容，不是最終 OpenAPI 完稿。

| 項目 | 目前實作 |
| --- | --- |
| 建立查詢 | `POST /api/v1/parking-queries` 成功回 `202 Accepted`；完成輸入檢查後建立短期 `queryId`，同步回目的地、300 公尺範圍、ETA、停車資料與刷新摘要 |
| 查詢讀取 | `GET /api/v1/parking-queries/{queryId}` 成功回 `200 OK`；只讀既有查詢狀態，不呼叫 Google Routes 或新北市官方來源 |
| 手動刷新 | `POST /api/v1/parking-queries/{queryId}/refresh` 成功回 `202 Accepted`；只檢查路邊／路外來源刷新協調，不重新計算 Google ETA，不延長 `queryId` |
| 防重送 | 有效且相同的 `requestToken` 會回原本的 `queryId`；`requestToken` 限 120 字元內，允許英數、`.`、`_`、`:`、`-` |
| MVP 範圍 | 目的地必須在新莊 MVP bounding box 內；區外回 `422 UNSUPPORTED_AREA`，不建立查詢 |
| 查詢 TTL | `queryId` 有效 30 分鐘；Redis query payload 預設保留 60 分鐘，超過 response `expiresAt` 但 payload 尚在 retention 內時回 `410 QUERY_EXPIRED`，retention 後或從未存在時回 `404 QUERY_NOT_FOUND` |

錯誤回應固定為：

```json
{
  "error": {
    "code": "UNSUPPORTED_AREA",
    "message": "Destination is outside the Xinzhuang MVP service area.",
    "details": {
      "serviceArea": "NEW_TAIPEI_XINZHUANG"
    },
    "retryable": false,
    "nextAllowedAt": null
  },
  "serverTime": "2026-09-29T06:25:36Z"
}
```

已實作錯誤碼：

| HTTP | 錯誤碼 | 使用情境 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | JSON 格式錯誤、必填欄位缺漏或 Bean validation 失敗 |
| `400` | `INVALID_COORDINATE` | 起點或目的地經緯度缺漏、超出合法範圍 |
| `400` | `INVALID_REQUEST_TOKEN` | `requestToken` 缺漏、太長或字元不合法 |
| `422` | `UNSUPPORTED_AREA` | 目的地在新莊 MVP 服務範圍外 |
| `422` | `INVALID_DEPARTURE_TIME` | `departure.type` 不合法、`NOW` 帶 `departureAt`、`SCHEDULED` 缺少合法未來 offset datetime |
| `404` | `QUERY_NOT_FOUND` | `queryId` 不存在或不應揭露 |
| `410` | `QUERY_EXPIRED` | `queryId` 已超過目前 30 分鐘 TTL |
| `503` | `SERVICE_TEMPORARILY_UNAVAILABLE` | Redis query state 暫不可用，主系統無法可靠建立、讀取或刷新查詢 |

目前 Redis／MySQL 邊界：Redis 用於兩類短期協調狀態。來源刷新使用 `parking:refresh-lock:{source}:{dataset}` 短鎖與 MySQL `source_state.next_allowed_refresh_at` cooldown；查詢生命週期使用 `parking:query:{queryId}` 保存短期查詢 response/request，並用 `parking:query-token:{requestToken}` 做防重送索引。`parking:query:{queryId}` 的 TTL 是查詢有效期加 expired retention，目前預設 60 分鐘；`parking:query-token:{requestToken}` 只保留查詢有效期，目前預設 30 分鐘。MySQL 保存停車目錄、來源 mapping、候選／已發布快照、觀測、來源狀態與 ingestion run 量測，不保存使用者短期查詢。Redis query state 不可用時，API 回 `503 SERVICE_TEMPORARILY_UNAVAILABLE`，不降級為單機記憶體狀態。

## 2026-09-29 S2-024 已實作可見頁受控刷新接線

`POST /api/v1/parking-queries/{queryId}/refresh` 已新增可選 query parameter：`trigger`。預設為 `MANUAL_REFRESH`；前端頁面可見時的受控自動刷新使用 `VISIBLE_AUTO_REFRESH`。目前 HTTP 層只接受這兩個值，其他 trigger 回 `400 INVALID_REFRESH_TRIGGER`。這個限制讓瀏覽器端不能用查詢 API 冒充背景取樣或初始來源擷取；實際來源保護仍由後端 `SourceRefreshCoordinationService`、MySQL cooldown 與 Redis 來源短鎖決定。

前端已實作第一版節奏：頁面隱藏時停止輪詢與自動刷新；重新可見時先 `GET /api/v1/parking-queries/{queryId}` 只讀恢復，不直接 `POST`；若最新回應中 `roadside.status` 或 `offStreet.status` 為 `STALE`／`NO_REALTIME_SNAPSHOT`，且 `refresh.*.refreshInProgress=false`，同一 queryId 與來源狀態組合只送一次 `trigger=VISIBLE_AUTO_REFRESH`。手動「更新車位」仍送 `trigger=MANUAL_REFRESH`，且不重算 Google ETA、不重新搜尋地點、不延長 query TTL。

驗證：`ParkingQueryControllerTest`、`ParkingQueryServiceTest` 與 `RedisParkingQueryStateStoreTest` 共 18 tests／0 failures／0 errors，覆蓋成功查詢、讀取、刷新、重複 `requestToken`、非法座標、區外、過去指定出發時間、未知 queryId、過期 queryId、Redis TTL 寫入、Redis JSON 還原與 Redis 失敗包裝。完整後端 Docker Maven 測試通過 67 tests／0 failures／0 errors。測試未呼叫真實 Google 或新北市官方 API。
