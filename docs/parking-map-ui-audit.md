# 車位資訊完整性與地圖改版稽核

更新日期：2026-10-25。狀態：後端 API／欄位擴充與前端地圖、群聚、詳情及響應式樣式已實作；本機前後端測試與前端 production build 通過。五種真實瀏覽器尺寸與互動驗收、Docker MySQL migration 尚未完成：自動化瀏覽器政策拒絕本機頁面讀取並禁止改用其他瀏覽器路徑；Docker daemon pipe 拒絕存取。此文件將來源觀測、資料品質與抵達時間預測分開記錄。

## 產品定位與驗收基準

主要使用者是正前往目的地、需要先找停車選項的駕駛。地圖應讓人快速比較周邊逐格路邊車位與路外停車場，並能辨認目前官方觀測、資料品質、可導航資格與抵達時間預測各自的狀態。尚未通過模型驗證前，不宣稱預測優勢，也不把目前觀測描述成抵達時結果。

此定位來自使用者回覆，已同步至 `docs/PRODUCT.md`。原始需求及來源決策仍以 `docs/requirements.md` 與 `docs/decisions.md` 為準。

## 端到端資料盤點

| 資料／欄位 | 官方來源與目前證據 | 後端保存與 API | 前端現況 | 可信度／限制 |
| --- | --- | --- | --- | --- |
| 路邊格位 ID、位置、道路 | 新北市路邊即時資料含 `id`、`cellid`、`roadname`、`latitude`、`longitude`、`areacode` 等欄位；新莊以 `areacode=65000050` 篩選。 | 以來源 ID、座標及審查過的 source mapping 對應 `parking_place`。新 migration 另保存位置詳細資料。 | 地圖近距離逐格呈現、遠距離以 56 CSS 像素網格合併，點選群聚會放大；選取格位可從底部／側邊詳情查看來源欄位。 | 地圖只展示已審查目錄／mapping；來源座標未由本專案實地校正，不宣稱已驗證實體格位位置精度。 |
| 路邊狀態與空位 | 官方資料含 `parkingstatus`、`cellstatus` 等狀態；`1/2/3/5` 語意曾以官方地圖、交通部標準及樣本交叉核對。 | 只以已發布 snapshot、可用品質及已確認汽車適用性計算逐格目前狀態；狀態碼 `3`、`5` 等仍依既有保守驗證規則處理。 | 有位、占用、未知、過期等狀態以文字、色彩與不同標籤呈現；近距離格位可分開選取，群聚只統計資料筆數，不當成空位數。 | 新北市資料未附完整 enum 字典；`1/2` 有交叉證據，`5` 保持未知，不能把第三方圖例視為官方字典。 |
| 路邊收費與時段 | 官方 row 含 `pay`、`paycash`、`day`、`hour`、`memo`。 | 已映射至 `feeStatus`、`feeDescription`、`chargingDays`、`chargingHours`、`notes`；單一有效 `cellid` 以 1 格記錄總格數。 | 選取詳情卡顯示收費、時段與備註；不在地圖摘要中塞入長文字。 | 文字是來源原文清理後的提示；沒有另行推定法定收費語意。 |
| 路外停車場名稱、地址、容量 | 新北市路外靜態 catalog 提供名稱、地址、類型、收費說明、營業時間及 `TOTALCAR`；即時資料另提供可用位數。 | 已保存地址、類型、收費、服務時間、容量、摘要；非負 `TOTALCAR` 才映射為容量。availability 與靜態 catalog 分開讀取。 | 地圖以不同形狀標示路外停車場；選取詳情卡顯示已提供的可用／總車位、位置與收費資訊。 | `AVAILABLECAR=-9` 等無即時數值不得當成 0；未確認品質、資格或 freshness 時可用位數留空。 |
| 官方觀測時間與系統時間 | 新北市路邊資料未提供可採信的逐筆官方觀測時間。 | `sourceObservedAt` 缺失時維持 null；`systemCollectedAt` 表示本系統擷取，不冒充官方時間。 | 現有細節會分別標示官方逐筆時間與系統取得時間；前者缺失時明示未提供。 | 系統擷取時間只能證明本系統何時取得資料，不能證明來源 row 的實際觀測時刻。 |
| 資料品質與汽車資格 | 來源 row／mapping 需經目錄與品質審查；符合狀態語意不代表已確認一般汽車可用。 | 回傳 `qualityStatus`、`qualityReason`、`carEligibleStatus`；未確認資格、未知或過期時不回報可用數、不提供導航。 | 詳情顯示品質、原因、適用性、容量與導航 gate；狀態仍搭配文字與色彩。 | UI 必須把「目前狀態」和「能否推薦／導航」分開，不可用顏色單獨傳達。 |
| 抵達時間預測 | ETA 由 Google Routes；停車到達時機率需經已驗證模型與足量訓練資料。 | 查詢契約將 ETA 與 `predictions` 分開；模型資料不足時回 `INSUFFICIENT_DATA`，不產生虛構機率。 | 現有結果卡分開顯示 ETA 與預測資料不足。 | 不以模型分數或 UI 呈現暗示模型勝過基準，直到時間外測試達到預先決定的門檻。 |

## API 與資料流矩陣

| 層級 | 已完成 | 尚待驗收 |
| --- | --- | --- |
| Catalog import | `parking_place` nullable 欄位及 V2 migration 已建立；NTPC 路邊／路外 DTO 欄位映射及長度清理已補；舊建構子相容性保留。 | 從完整官方 catalog 執行 dry-run、檢查缺 ID／座標／重複／資格比例，核對實際 mapping coverage；尚未匯入全量資料。 |
| 規劃查詢 API | 舊 `GET /{queryId}/map-data` 保留 500 公尺行程查詢契約。 | 舊契約中的固定範圍與新 viewport 流程需在 UI 過渡期共同驗收。 |
| 地圖範圍 API | 新增唯讀 `GET /{queryId}/map-data/viewport?south&west&north&east&offset&limit`；裁切新莊支援範圍，單頁最多 500 筆；依 verified mapping 與已發布 snapshot 回傳逐格資料。 | 前端以 map idle debounce、分頁、重複範圍快取及 request guards 接線；已量測的全量 catalog DB 延遲仍待 Docker MySQL 驗收。 |
| 官方刷新隔離 | viewport 是 GET 讀取流程，不呼叫 Routes、不啟動官方 refresh；既有官方刷新仍由受控 POST 流程管理。 | 需以瀏覽器網路事件及後端測試驗證拖曳／縮放不額外觸發 refresh。 |
| DB migration | H2 整合測試已驗證 migration；完整 Maven 測試 112/112 通過。 | Docker 中 MySQL migration 尚未驗收；目前保留既有容器與 volume，未重建或寫入。 |

## 前端與競品基線

| 面向 | 目前 repo／Docker 基線 | ParkBoss 參考差異 | 改版驗收目標 |
| --- | --- | --- | --- |
| 地圖與密度 | 既有 Docker Web `http://127.0.0.1:18088/` 在 1280×720 已實際開啟，Google 地圖載入；尚未送出查詢，因此畫面無車位結果。桌面畫面上方行程表單占用較大高度。 | ParkBoss 地圖首頁直接以大量逐格圓點呈現路邊狀態，路外場站另以不同形狀／數量表示。 | 地圖成為主要閱讀面；路邊格位依約 56 CSS 像素鄰近距離群聚，跨越內部格界的鄰近點仍合併，zoom 21 起逐格可見、可點；路外場站維持不同識別。 |
| 清單及資訊 | repo 現有結果面板有圖層／狀態篩選、逐筆時間與導航限制；單筆卡尚未完整呈現 catalog 收費與品質細節。 | ParkBoss 可切換清單、圖例和可用條件，選取 marker 可看車位／場站細節。 | 點選格位顯示位置、狀態、來源、資料品質、收費／時段、汽車適用性、容量與導航條件；未知資料清楚留空。 |
| 更新與預測 | Docker 基線未送出查詢；尚未在基線驗證上游呼叫次數。前端程式已實作受控可見頁刷新語意。 | 競品呈現可用即時點位；其資料品質與預測能力不作本產品的證據。 | 地圖拖移只呼叫唯讀 viewport；官方觀測、資料品質與抵達時間預測分區顯示。 |
| 手機版與自適應 | 已實作暖紙色圖集風格、緊湊規劃列、地圖工具列與固定底部結果抽屜；本輪真實瀏覽器的指定尺寸尚未驗收。 | 參考其可辨識單格密度，保留本專案的來源、品質及抵達預測說明。 | 驗收 375×812、390×844、430×932，以及桌面 1280×720、1440×900；確認表單、地圖、選取卡、清單抽屜、篩選和圖例均可操作。 |

ParkBoss 僅用來比較使用者可見的逐格粒度與互動，不作後端資料正確性、來源授權或模型效能的證明。使用者已明確偏好 Impeccable「臺灣街道圖集」完整手機稿；正式視覺規範見 `docs/DESIGN.md`。實際底圖仍使用 Google Maps，生成示意圖只提供配色與資訊層級參考，不會取代即時地圖或當成實際格位資料。

## 來源與證據

- 新北市路邊停車即時資料集：[data.ntpc.gov.tw](https://data.ntpc.gov.tw/datasets/54A507C4-C038-41B5-BF60-BBECB9D052C6)
- 新北市路外停車場即時剩餘車位：[新北市資料開放平臺](https://staging.data.ntpc.gov.tw/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78)
- 新北市路外停車場 catalog：[data.ntpc.gov.tw](https://data.ntpc.gov.tw/datasets/B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68)
- 路邊 status code 交叉驗證與保守規則：`docs/s3-010-roadside-status-validation.md`
- 目前實作及限制：`docs/current-state.md`、`docs/decisions.md`、`docs/open-questions.md`

## 驗證紀錄與剩餘工作

- 後端完整 Maven 測試：112 tests，0 failures、0 errors、0 skipped。
- 實際 Docker 基線：現有容器健康，Web 綁定 `127.0.0.1:18088`；本次讀取頁面、沒有重新建置容器或觸發查詢。
- 前端測試：`npm test` 通過 7 組測試，`npm run build` 成功；後端 Maven 112/112 通過；`impeccable detect --json frontend/src/App.vue` 回傳 `[]`。獨立 code review 的四項規格缺口已修正；標準審查發現重複 CSS 已合併，`safeText` 改名為 `normalizeAndLimitText`。指定尺寸瀏覽器驗收尚未完成：安全政策拒絕觀察本機 Vite 頁並禁止改用其他 surface 重試。Docker daemon pipe 亦拒絕存取。尚待五種尺寸互動及截圖、MySQL migration 與高密度 viewport 資料庫延遲量測；真實頁面視覺 critique／polish 仍受瀏覽器存取限制。
- 其餘待做：完整 catalog／mapping 量測、高密度 viewport 的 MySQL 查詢延遲。此工作分支未 push、未建立 PR、未合併，未修改 production 或任何既有 volume。
- 部署／資料風險：本機 Docker DB 尚未套用 V2 migration；若啟用新版 API，仍需先確認 MySQL migration 可用。官方座標未經實地精度校驗；UI 已明示其來源限制。
