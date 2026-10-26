# 全臺停車位抵達預測｜HTML 面試作品集

## 開啟方式

直接用瀏覽器開啟 index.html。這是一份自包含的靜態頁，不需要安裝 npm 套件、啟動後端、連線資料庫、載入 CDN 或下載遠端字型。按「列印／另存 PDF」可開啟瀏覽器列印對話框。

作品集以繁體中文撰寫，主要圖表使用內嵌 SVG。手機、平板與桌面版會依可用寬度調整；流程圖可在窄螢幕上獨立水平捲動，不會把整個頁面撐寬。

## 內容索引

1. 專案總覽與實際產品狀態
2. 系統整體架構、後端模組與技術棧
3. 使用者流程與資料不足降級
4. 後端 API 時序、契約與錯誤
5. 線上來源、歷史資料與 ML 實驗生命週期
6. 人類、Codex、GPT 與 GitHub 的實際協作邊界
7. 有證據的工程案例
8. 成果、限制與接續工作
9. 可展開的 ERD、API、驗證紀錄與技術詞彙

## 內容維護

需求與驗收以 [docs/requirements.md](../docs/requirements.md) 為準；已決策事項以 [docs/decisions.md](../docs/decisions.md) 為準；當前狀態以 [docs/current-state.md](../docs/current-state.md) 和實際程式碼為準。較早的 [docs/architecture.md](../docs/architecture.md) 是設計草案，不能單獨當成已實作證據。

更新本頁前，逐項對照以下來源：

- API 契約與執行順序：backend/src/main/java/parking/forecast/controller/、service/、adapter/。
- 持久資料與外鍵：backend/src/main/java/parking/forecast/domain/、repository/、backend/src/main/resources/db/migration/。
- 線上產品與前端狀態：frontend/src/App.vue、frontend/src/style.css。
- 訓練邊界、切分、標籤和模型實驗：ml-training/README.md、ml-training/scripts/、docs/current-state.md。
- Compose、測試與部署限制：docker-compose.yml、backend/pom.xml、frontend/package.json 及目前進度紀錄。

每項數字都應同時記錄範圍、日期、單位和限制。分開寫明「事實」、「仍需驗證」和「工作假設」。**Build 成功不等於測試通過；容器 healthy 不等於外部服務或正式環境已驗收；歷史測試不代表本次 HTML 已通過驗證。**

## 更新圖表

SVG 圖表刻意直接放在 HTML，以便離線閱讀和一份檔案內搜尋。改圖時先核對箭頭是否符合目前呼叫順序、實線或虛線的狀態語意，以及是否誤把離線訓練畫成主系統即時輸入。ERD 以 Flyway migration／Entity 的實際外鍵為準。

首頁產品畫面是依 2026-10-26 產品介面重繪的靜態展示，不是螢幕截圖。街廓、道路、圈線與標記均為示意；不得補成真實地圖座標。畫面上的 62 筆是符合篩選的官方觀測筆數，不等於 62 個空位。來源觀測時間也不可用系統取得時間取代。

## 維護時應保留的事實邊界

- 主系統目前以新北市新莊 MVP 為基礎。TDX 歷史素材曾用於離線研究，但 TDX 全臺即時來源尚未接入主系統。
- 新建查詢只讀已發布快照；官方來源刷新由獨立 refresh API 和來源 gate 控制。不要把離線 parking-data-collector 的 CSV、時間戳或排程描述成使用者查詢契約。
- MySQL 保存目錄、來源 mapping、快照、觀測與來源狀態。Redis 用於短期 query state 與刷新協調，不是唯一車位真相。
- 路外模型只在明確啟用、觀測新鮮、狀態與數量一致、汽車資格確認、ETA 已就緒且 lead 符合 30／60 分鐘條件時，才可返回實驗性機率。現有 artifact 為 EXPERIMENT_ONLY，S3-020 gate 未通過。
- 路邊預測尚為 INSUFFICIENT_DATA。不得將模型估計冒充官方即時車位，也不得把未知或過期轉為 0。
- GitHub Actions 狀態依最新實際紀錄更新；本次使用者提供目前因用量限制暫停。避免用 workflow dispatch 驗證作品集。

## 設計契約（Impeccable surface brief）

**THESIS：**從實際停車查詢畫面出發，讓讀者沿著街區範圍、來源快照、API 和訓練標籤一路看到每個結論的依據。

**OWN-WORLD：**沿用產品中的暖米白、深綠、柔和狀態色與地圖資訊層。卡片只用於實際狀態面板，不把八區內容鋪成同尺寸卡片；不以陰影或裝飾性地圖紋理代替資料。

**STORY：**一般讀者先理解停車問題和目前的「資料過期／未設出發地／資料不足」；技術面試官再從架構、後端、流程、ML 到工程案例核對資料邊界和驗證證據。

**FIRST VIEWPORT：**頂端是產品名稱、段落導覽和列印入口；主視窗先呈現新莊體育館 500 公尺查詢。左側街廓是明確標示的示意重繪，不是實際地圖或螢幕截圖；右側數值與狀態依 2026-10-26 產品介面紀錄，並說明系統取得時間不能取代來源觀測時間。使用者已核准五個首屏區域皆由程式碼繪製；首屏瀏覽器截圖尚未完成。

**FORM：**由街區縮到資料證據；採核准的構圖方向 portfolio-zoom-layers。五個首屏區域（產品地圖示意、詳情、來源結果、下一節、導覽列）皆選擇 Code is fine，不打包 raster 圖像素材。

**FINISH：**內容與靜態 HTML 結構已檢查；瀏覽器安全政策拒絕此工作階段讀取 file:// 頁面，並禁止透過本機伺服器或其他瀏覽器介面繞過。因此桌面／手機截圖、列印預覽、Console 與 Impeccable finish reviewer 尚未完成。不要將這些項目標為通過。

## 驗證狀態

本作品集的靜態 HTML 檢查已確認標籤配對、ID 唯一、內部錨點與本機文件連結有效，且沒有外部資源、外部腳本或圖片資產；六個 SVG 圖表均有 title／desc。HTML parser 與 Impeccable detector 都不等於瀏覽器視覺驗收。安全政策封鎖後，沒有完成桌面／手機截圖、鍵盤實際操作、列印預覽與 Console 檢查；Impeccable finish reviewer 也尚未執行。
