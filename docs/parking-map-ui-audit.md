# 車位資訊完整性與地圖改版稽核

更新日期：2026-10-25。狀態：後端 API／欄位擴充、前端地圖改版與 RWD 已實作；先前本機前後端測試、HTTP 契約 smoke 與 production builds 通過。Docker API／Web 已同步重建，MySQL V4 migrations 已套用。五種真實瀏覽器尺寸與互動驗收尚未完成。此文件將來源觀測、資料品質與抵達時間預測分開記錄。

## 產品定位與驗收基準

主要使用者是正前往目的地、需要先找停車選項的駕駛。地圖應讓人快速比較周邊逐格路邊車位與路外停車場，並能辨認目前官方觀測、資料品質、可導航資格與抵達時間預測各自的狀態。尚未通過模型驗證前，不宣稱預測優勢，也不把目前觀測描述成抵達時結果。

此定位來自使用者回覆，已同步至 `docs/PRODUCT.md`。原始需求及來源決策仍以 `docs/requirements.md` 與 `docs/decisions.md` 為準。

## 端到端資料盤點

| 資料／欄位 | 官方來源與目前證據 | 後端保存與 API | 前端現況 | 可信度／限制 |
| --- | --- | --- | --- | --- |
| 路邊格位 ID、位置、道路 | 新北市路邊即時資料含 `id`、`cellid`、`roadname`、`latitude`、`longitude`、`areacode` 等欄位；新莊以 `areacode=65000050` 篩選。 | 以來源 ID、座標及審查過的 source mapping 對應 `parking_place`。新 migration 另保存位置詳細資料。 | 地圖近距離逐格呈現、遠距離以 56 CSS 像素網格合併，點選群聚會放大；選取格位可從底部／側邊詳情查看來源欄位。 | 地圖只展示已審查目錄／mapping；來源座標未由本專案實地校正，不宣稱已驗證實體格位位置精度。 |
| 路邊狀態與空位 | 官方資料含 `parkingstatus`、`cellstatus` 等狀態；`1/2/3/5` 語意曾以官方地圖、交通部標準及樣本交叉核對。 | 只以已發布 snapshot、可用品質及已確認汽車適用性計算逐格目前狀態；狀態碼 `3`、`5` 等仍依既有保守驗證規則處理。 | 有位、占用、未知、過期等狀態以文字、色彩與不同標籤呈現；近距離格位可分開選取，群聚只統計資料筆數，不當成空位數。 | 新北市資料未附完整 enum 字典；`1/2` 有交叉證據，`5` 保持未知，不能把第三方圖例視為官方字典。 |
| 路邊收費與時段 | 官方逐格 row 含 `pay`、`paycash`、`day`、`hour`、`memo`；新北路段費率表另列 `areacode`、完整 `road_name`、收費時間與 `rates`。 | 逐格欄位優先；僅在費率缺漏時，後端以已驗證 mapping 的完整路名與行政區碼唯一配對官方路段表，並在回應標成「官方路段費率」。路口範圍與重複候選不套用。 | 詳情可連到官方路段表；桌面附近清單只顯示最多兩行收費摘要，手機不在清單擠入長費率。 | 不做座標最近路口、部分路名或模糊配對；官方區間／交叉口列維持逐格值或未提供。未取得官方道路幾何／格位 crosswalk 前不擴大配對範圍。 |
| 路外停車場名稱、地址、容量 | 新北市路外靜態 catalog 提供名稱、地址、類型、收費說明、營業時間及 `TOTALCAR`；即時資料另提供可用位數。 | 已保存地址、類型、收費、服務時間、容量、摘要；非負 `TOTALCAR` 才映射為容量。availability 與靜態 catalog 分開讀取。 | 地圖以不同形狀標示路外停車場；選取詳情卡顯示已提供的可用／總車位、位置與收費資訊。 | `AVAILABLECAR=-9` 等無即時數值不得當成 0；未確認品質、資格或 freshness 時可用位數留空。 |
| 官方觀測時間與系統時間 | 新北市路邊資料未提供可採信的逐筆官方觀測時間。 | `sourceObservedAt` 缺失時維持 null；`systemCollectedAt` 表示本系統擷取，不冒充官方時間。 | 現有細節會分別標示官方逐筆時間與系統取得時間；前者缺失時明示未提供。 | 系統擷取時間只能證明本系統何時取得資料，不能證明來源 row 的實際觀測時刻。 |
| 資料品質與汽車資格 | 來源 row／mapping 需經目錄與品質審查；符合狀態語意不代表已確認一般汽車可用。 | 回傳 `qualityStatus`、`qualityReason`、`carEligibleStatus`；未確認資格、未知或過期時不回報可用數、不提供導航。 | 詳情顯示品質、原因、適用性、容量與導航 gate；狀態仍搭配文字與色彩。 | UI 必須把「目前狀態」和「能否推薦／導航」分開，不可用顏色單獨傳達。 |
| 抵達時間預測 | ETA 由 Google Routes；停車到達時機率需經已驗證模型與足量訓練資料。 | 查詢契約將 ETA 與 `predictions` 分開；模型資料不足時回 `INSUFFICIENT_DATA`，不產生虛構機率。 | 現有結果卡分開顯示 ETA 與預測資料不足。 | 不以模型分數或 UI 呈現暗示模型勝過基準，直到時間外測試達到預先決定的門檻。 |

## API 與資料流矩陣

| 層級 | 已完成 | 尚待驗收 |
| --- | --- | --- |
| Catalog import | `parking_place` nullable 欄位及 V2 migration 已建立；NTPC 路邊／路外 DTO 欄位映射及長度清理已補。新莊路外官方目錄 145 列與本機 138 個 confirmed-car `VERIFIED` mapping 完整相符，7 個汽車位數為 0 的機車場排除；V3 修正體育園區無效座標，V4 更新可辨識場站名稱。 | 新莊路邊仍是 reviewed subset，未代表行政區完整格位覆蓋；官方座標異常若沒有已審查 override 會被拒收。 |
| 規劃查詢 API | `POST /api/v1/parking-queries` 建立查詢；`GET /{queryId}` 讀取；舊 `GET /{queryId}/map-data` 保留 500 公尺目的地查詢契約。 | Docker 同源 POST smoke 回 `READY`；完整互動仍待桌面／手機瀏覽器驗收。 |
| 地圖範圍 API | 新增唯讀 `GET /{queryId}/map-data/viewport?south&west&north&east&offset&limit`；裁切新莊支援範圍，單頁最多 500 筆；依 verified mapping 與已發布 snapshot 回傳逐格資料。 | 前端以 map idle debounce、分頁、重複範圍快取及 request guards 接線；已量測的全量 catalog DB 延遲仍待 Docker MySQL 驗收。 |
| 官方刷新隔離 | viewport 是 GET 讀取流程，不呼叫 Routes、不啟動官方 refresh；既有官方刷新仍由受控 POST 流程管理。 | 需以瀏覽器網路事件及後端測試驗證拖曳／縮放不額外觸發 refresh。 |
| DB migration | H2 整合測試與 Docker MySQL 均已套用 V2 nullable 欄位 migration；既有 named volume 保留。 | 已完成 smoke，仍需高密度 viewport DB 延遲量測；本次沒有觸發 catalog seed 或來源刷新。 |

## 2026-10-25 官方新莊路外目錄與地圖 bounds 追加稽核

重新讀取新北市路外資料集三頁，官方目錄共 1,381 筆，新莊區 145 筆、汽車位數大於零 138 筆；本機 verified source IDs 全數命中，沒有缺 mapping。舊目的地／viewport 矩形之外另有 15 筆有效新莊場站，加上體育園區 `050064` 零座標錯誤，合計 16 筆不會出現在預期範圍。已將 bounds 南界調至 24.995、西界調至 121.405，並新增座標異常拒收、V3 座標修正與 V4 場站顯示名稱 migration。

體育局委外資料列園區 706 格分布於五個子區；新北市即時目錄只提供父場站一筆，因此 map label/detail 會補「正好停、體育館地下、公園路平面區」名稱提示，而空位仍只顯示整場 706 格觀測。`D 區` 是否等同臨公園路區尚無官方證明，維持待核實。場址座標來自官方門牌定位並與市府低碳充電資料的該場站位置交叉相符，尚未實測各子區出入口。矩形 bounds 不是行政區 polygon，行政區精確邊界留待後續。

## 2026-10-25 本機 Compose 端到端稽核

### 根因與觀測

- 新北市路邊與路外官方資料 connector、來源欄位映射、catalog、verified source mapping、快照查詢及受控刷新皆已存在。無資料不是「完全沒串 API」；新莊仍是小樣本 catalog，資料未覆蓋全區。
- 使用者目的地（25.036, 121.4505）的 500 公尺唯讀 `/map-data` 有 5 筆路邊標記、沒有路外場站目錄項。較大的 viewport HTTP smoke 回 10 筆 verified 標記（7 路邊、3 路外）。目前這些位置的來源快照皆為 `STALE`，故不應以此呈現即時可用車位或 ETA 到達時結果。
- 前端原本呼叫 `GET /api/v1/parking-queries/{queryId}/map-data/viewport`，但當時執行中的 Docker API 是舊 image，該路徑回 404；前端遇錯後沒有退回目的地範圍 API，清單因此變空。重建後的 Docker API 已提供 viewport endpoint，HTTP smoke 成功。前端另新增唯讀 `/map-data` 降級，在 viewport route 暫不可用時仍可呈現目的地周邊目錄。
- Docker API 新 image 健康啟動；Flyway 在原 MySQL volume 只套用 V2 新增 nullable 欄位，repeatable seed checksum 未變而未重跑。Web 也已重建，頁面和新版 hashed JS asset 均回 HTTP 200，四項 Compose service healthy。
- API query smoke 回 `READY`；Google ETA 回 `FAILED`，原因明確是 `GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED=false`。本次沒有呼叫 Google Routes；前端現在將此設定原因翻為繁體中文。停車機率仍依模型資料治理維持資料不足。

### 前端功能與後端契約對照

| 前端功能 | 對應端點／服務 | 結果與限制 |
| --- | --- | --- |
| 出發地／目的地搜尋 | 瀏覽器端 Google Places；選取後 `POST /api/v1/parking-queries` 傳 label、座標、出發時間 | API 接收已選地點；Google Places 互動尚待受限測試 key 下人工驗收。 |
| 規劃即時／指定出發時間 | `POST /api/v1/parking-queries` | 輸入驗證及受支援區域檢查存在；此次 HTTP smoke 成功回 `READY`。 |
| 顯示 500 公尺附近路邊／路外資料 | `GET /{queryId}/map-data` | 讀取 verified catalog/mapping 與已發布快照；目的地小樣本是 5 路邊、0 路外。 |
| 依移動地圖顯示範圍資料 | `GET /{queryId}/map-data/viewport` | 後端端點與 MySQL 範圍查詢存在；新版 Docker 已以 10 個 verified 標記通過 HTTP smoke。 |
| 更新目前官方觀測 | `POST /{queryId}/refresh?trigger=MANUAL_REFRESH` | 後端以冷卻／鎖定協調新北來源刷新並發布快照，前端更新按鈕已接線。本次沒有呼叫此端點，所以沒有觸發 NTPC 或寫入新快照；現有資料仍 `STALE`。 |
| 車種／狀態／類型篩選、地圖／清單切換 | 前端以 map-data 回應執行本機篩選 | 不需獨立後端 endpoint；篩選不能擴大 verified mapping 範圍。「一般汽車」快捷篩選已移除，詳情仍保留汽車適用性事實。 |
| 目前位置、導航、分享 | 瀏覽器 Geolocation、Google Maps URL、Web Share／clipboard | 由瀏覽器原生能力執行，不是後端停車資料服務。定位及真實地圖操作仍待瀏覽器驗收。 |
| 收藏 | 前端目前為頁面記憶體狀態，沒有後端 profile/favorite API | 目前沒有登入／使用者模型，不建立無身份界線的伺服器收藏。重新載入會重設收藏；跨裝置保存需先決定身份與資料範圍。 |
| Google Routes ETA | 規劃 POST → 後端 `GoogleEtaService`／Google Routes client | mock service/client 測試通過；Docker 外部呼叫旗標關閉，本次未產生 Routes 請求。開啟並驗證真實 ETA 前需明確核准計費呼叫。 |
| 抵達時停車機率 | 規劃 response 的 `predictions` 區塊與版本化模型接點 | API 欄位已存在，模型與資料 gate 尚未通過，正確回報 `INSUFFICIENT_DATA`；不可用目前觀測冒充預測。 |

本機 HTTP smoke 僅建立短期 Redis query 並讀取目前 MySQL snapshot；沒有呼叫 Google Routes、新北官方 refresh，也沒有改動停車資料列。

## 前端與競品基線

| 面向 | 目前 repo／Docker 基線 | ParkBoss 參考差異 | 改版驗收目標 |
| --- | --- | --- | --- |
| 地圖與密度 | Docker 舊 API 曾讓 viewport 回 404；新版 API／Web 已重建，HTTP 確認範圍資料正常，瀏覽器實際視覺仍待查看。桌面版採地圖主區與右側詳情。 | ParkBoss 地圖首頁直接以大量逐格圓點呈現路邊狀態，路外場站另以不同形狀／數量表示。 | 地圖成為主要閱讀面；路邊格位依約 56 CSS 像素鄰近距離群聚，跨越內部格界的鄰近點仍合併，zoom 21 起逐格可見、可點；路外場站維持不同識別。 |
| 清單及資訊 | 已改版的結果面板有圖層／狀態／類型篩選、逐筆時間與導航限制；目錄項完整度受目前小樣本限制。 | ParkBoss 可切換清單、圖例和可用條件，選取 marker 可看車位／場站細節。 | 點選格位顯示位置、狀態、來源、資料品質、收費／時段、汽車適用性、容量與導航條件；未知資料清楚留空。 |
| 更新與預測 | query 與 viewport HTTP smoke 通過；未按 refresh，故不觸發 NTPC。ETA 狀態已能指出 Docker Routes 外呼旗標關閉，尚未送真實 Routes 請求。 | 競品呈現可用即時點位；其資料品質與預測能力不作本產品的證據。 | 地圖拖移只呼叫唯讀 viewport；官方觀測、資料品質與抵達時間預測分區顯示。 |
| 手機版與自適應 | 已實作暖紙色圖集風格、緊湊規劃列、地圖工具列與固定底部結果抽屜；本輪真實瀏覽器的指定尺寸尚未驗收。 | 參考其可辨識單格密度，保留本專案的來源、品質及抵達預測說明。 | 驗收 375×812、390×844、430×932，以及桌面 1280×720、1440×900；確認表單、地圖、選取卡、清單抽屜、篩選和圖例均可操作。 |

ParkBoss 僅用來比較使用者可見的逐格粒度與互動，不作後端資料正確性、來源授權或模型效能的證明。使用者已明確偏好 Impeccable「臺灣街道圖集」完整手機稿；正式視覺規範見 `docs/DESIGN.md`。實際底圖仍使用 Google Maps，生成示意圖只提供配色與資訊層級參考，不會取代即時地圖或當成實際格位資料。

## 來源與證據

- 新北市路邊停車即時資料集：[data.ntpc.gov.tw](https://data.ntpc.gov.tw/datasets/54A507C4-C038-41B5-BF60-BBECB9D052C6)
- 新北市路邊收費路段資訊：[data.ntpc.gov.tw](https://data.ntpc.gov.tw/datasets/d9f18db5-41c7-41d4-b7f0-82a335255b08)；[交通局路段費率查詢](https://parkweb.traffic.ntpc.gov.tw/route.aspx)
- 新北市路外停車場即時剩餘車位：[新北市資料開放平臺](https://staging.data.ntpc.gov.tw/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78)
- 新北市路外停車場 catalog：[data.ntpc.gov.tw](https://data.ntpc.gov.tw/datasets/B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68)
- 路邊 status code 交叉驗證與保守規則：`docs/s3-010-roadside-status-validation.md`
- 目前實作及限制：`docs/current-state.md`、`docs/decisions.md`、`docs/open-questions.md`

## 驗證紀錄與剩餘工作

- 後端完整 Maven 測試：112 tests，0 failures、0 errors、0 skipped。
- 本輪：前端 `npm test` 7 組測試、`npm run build` 通過；Impeccable detector `[]`；完整後端 Maven suite 為 113 tests／0 failures／0 errors／0 skipped。Docker API／Web rebuild、query、viewport、index／最新 JS asset HTTP 200 與四服務 health 均驗收。真實瀏覽器桌面／手機互動尚未驗收。
- 尚待：完整 catalog／mapping 量測、高密度 viewport MySQL 查詢延遲、真實瀏覽器視覺驗收、真實 Google Routes ETA 上游（需授權）、目前官方來源快照刷新。本次未觸發 Google／NTPC 上游 refresh，既有資料維持 `STALE`。此工作分支未 push、未建立 PR、未合併；既有 MySQL volume 保留，只套用 V2 nullable 欄位 migration。
- 部署／資料限制：官方座標未經實地精度校驗；catalog 只覆蓋已審查的小樣本；UI 已明示未知／過期限制。路外場站及停車機率不得因地圖 marker 存在而宣稱可用或預測正確。
