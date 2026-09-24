# 前端技術選型比較（Sprint 1 討論稿）

更新日期：2026-09-24。狀態：Sponsor 已選 B：Vue 3＋Vite 搭配 Spring Boot Web／Spring MVC REST；Google Places／Routes／Maps JavaScript API 已選；沒有 coding。本文保留選型比較與尚待決定的用量細節。

## 已定需求與選型條件

- 一頁以地圖為主的搜尋與結果：電腦版固定區域摘要及「查看全部結果」，手機版上地圖／下清單。
- 類 Google Maps 的目的地與手動起點候選、裝置目前位置、出發時間、目的地 ETA、路邊區域與路外場站結果。
- 標記與詳情面板連動、目前車位與抵達時預測分開、資料更新中／過期／未知／失敗的畫面、小資訊卡外部路線。
- Sponsor 熟悉 Java／Spring Boot，前端經驗較少；20 天目標涵蓋後端、前端與兩類預測，面試先在本機展示。

## 兩條可行路線

| 候選 | 組成 | 適合點 | 主要代價 |
| --- | --- | --- | --- |
| A：Spring Boot 頁面 | Spring MVC／Thymeleaf 呈現頁面，少量 JavaScript 控制地圖與資料刷新 | 沿用熟悉的 Java 專案與單一建置／部署 | 地圖標記、清單、面板、ETA、刷新狀態需手動同步；互動增加後 JavaScript 容易分散 |
| B：前後端分層 | Vue 3 + Vite 管理單頁互動，Spring Boot 提供 REST 查詢與受控刷新 | 地圖選取、詳情面板與多種資料狀態可由同一前端狀態管理；API 責任清楚 | 需要學 Vue 基礎與 Node／Vite 建置；本機需啟動或整合前後端兩套開發程序 |

**已選 B，先保持最小規模。** 只有一個結果頁，不急著加路由或全域狀態函式庫；先用 JavaScript，等實際複雜度或團隊要求再評估 TypeScript。這是本專案的工時與互動複雜度取捨，不是官方說 Vue 一定比 Thymeleaf 更好。A 保留為選型比較紀錄。

## 若採 B，建議的責任邊界

- **瀏覽器／Vue**：Google Place Autocomplete Widget (New) 已選為目的地與手動起點搜尋；Google Maps JavaScript API 已選為互動地圖。Vue 管理裝置定位權限、標記、卡片／面板狀態、出發時間與資料品質顯示；地圖載入用量另計。
- **Spring Boot**：驗證使用者選定的座標與時間、呼叫 Google Routes Web Service 算到目的地 ETA、按來源受控刷新政府資料、查 300 公尺附近項目、組合兩類預測及品質狀態。
- **金鑰**：若網頁端用 Maps JavaScript／Places，使用只允許指定網站與指定 API 的網頁金鑰；Routes Web Service 憑證留在伺服器並限制用途。Google 官方安全指引要求按用途限制金鑰，伺服器金鑰不得放進前端程式。先做本機展示仍要限制額度並核對 localhost 使用方式。
- **部署**：仍維持一個主專案 repository；開發時前端與 Spring Boot 可分別運行，面試本機展示應提供可重現的啟動方式。是否將前端正式建置檔交 Spring Boot 靜態提供，待選型後再決定。

## 待 Sponsor 決定

1. Google Maps 已選；仍須估算地圖載入、Places 與 Routes 的個別用量，設定適用配額／金鑰限制及每月可接受支出。
2. 前端工具學習與原型製作如何排入 20 天時程？若面試重點是後端，須限制前端功能與額外套件。

官方參考：[Spring MVC／Thymeleaf 入門](https://spring.io/guides/gs/serving-web-content/)、[Vue 官方 Quick Start](https://vuejs.org/guide/quick-start)、[Vite 入門](https://vite.dev/guide/)、[Google Place Autocomplete Widget](https://developers.google.com/maps/documentation/javascript/place-autocomplete-new)、[Google Maps Platform 安全指引](https://developers.google.com/maps/api-security-best-practices)。
