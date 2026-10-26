# 全臺停車位抵達預測｜後端面試簡報

## 開啟與操作

直接以瀏覽器開啟 index.html。簡報共兩頁：第 1 頁呈現整體架構、技術棧、程式語言，以及 GitHub／Docker Compose 基礎設施與交付；第 2 頁呈現使用者查詢、官方資料刷新、離線模型實驗三條流程。方向鍵、空白鍵或左右滑動翻頁；Esc 開啟總覽；P 開啟講者模式。講者備忘按穩定 slide ID 保存在本機。

## 目前大綱

兩頁內容規劃見 outline-v2.md；outline-v1.md 是較早版本。

## 設計與內容

使用 guizang-ppt-skill 的橫向翻頁與講者備忘結構，視覺沿用產品前端的暖米白、深綠與無襯線字體。圖示以單檔內嵌 SVG 呈現，包括 Vue、Vite、Spring Boot、MySQL、Redis、Python、GitHub 與 Docker；不需另外下載圖示檔。

## 範圍與事實邊界

目前即時服務範圍為新莊 MVP；全臺 TDX 即時來源尚未整合。離線 collector 不提供主系統即時資料或控制刷新。路外預測仍是實驗性功能，尚未通過正式生產驗證；模型估計不代表官方即時車位。

## 內容來源

依 docs/current-state.md、前後端程式碼、Docker Compose 設定、ml-training 腳本與模型文件核對。簡報只呈現目前可支持的架構與邊界。
