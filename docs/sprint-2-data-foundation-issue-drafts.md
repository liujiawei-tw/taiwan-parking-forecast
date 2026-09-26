# Sprint 2 第二組 GitHub Issues：資料基礎與 300 公尺查詢

更新日期：2026-09-26。狀態：GitHub Issues #11～#15 已完成並合併；Sprint 2 第二組資料基礎與 300 公尺查詢接入完成。

## 目標

Sprint 2 第一組已完成主系統骨架、mock 查詢流程、Docker 與 GitHub Actions CI。第二組目標是把系統從純 mock 往本地資料查詢推進：先建立資料庫模型與 migration，再用新莊小樣本驗證 300 公尺停車位置查詢，最後接回既有 `parking-queries` 流程。

此組仍不接 Google ETA、新北市即時 API、Redis query state 或 ML 預測；預測區塊維持資料不足狀態。

## Issue 連結

| S2 編號 | GitHub Issue | 主題 |
| --- | --- | --- |
| S2-009 | [#11](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/11) | 建立核心資料模型與 JPA Entity |
| S2-010 | [#12](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/12) | 建立 schema migration 與 MySQL/H2 邊界驗證 |
| S2-011 | [#13](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/13) | 建立新莊停車目錄小樣本與 seed 流程 |
| S2-012 | [#14](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/14) | 實作 300 公尺停車位置查詢服務 |
| S2-013 | [#15](https://github.com/liujiawei-tw/taiwan-parking-forecast/issues/15) | 將停車目錄查詢接入 `parking-queries` mock 流程 |

## 建議實作順序

1. **S2-009**：先建立 entity／repository，讓資料語意落地。已建立核心 Entity／Repository，並以 H2 repository 測試驗證；PR #16 已合併。
2. **S2-010**：建立 migration，確認 H2 與 MySQL 邊界。已建立 Flyway V1 migration，後端 Maven test 與臨時 Docker MySQL 驗證皆通過；PR #17 已合併。
3. **S2-011**：準備可提交的小樣本，不使用大型原始資料。已新增 3 筆路邊與 2 筆路外合成 seed，H2、Docker MySQL 與 Sponsor Postman 人工驗收皆通過；PR #18 已合併。
4. **S2-012**：完成 300 公尺附近查詢 service。已新增後端 service 與測試，H2 全測試 12 tests／0 failures，臨時 MySQL service 測試 4 tests／0 failures；PR #19 已合併。
5. **S2-013**：把查詢結果接入現有 API 與前端基本顯示。已將本地停車目錄接入既有 `parking-queries` mock 流程，H2 全測試 13 tests／0 failures，臨時 MySQL controller 測試 3 tests／0 failures；PR #20 已合併。

## 此組完成後可展示

- 後端有核心資料表與 JPA repository。
- Docker MySQL 可建立 schema。
- 系統可用新莊小樣本查出 300 公尺內已收錄的路邊與路外停車位置。
- 既有查詢 API 可回傳本地停車目錄結果。
- 目前車位與預測仍誠實顯示資料不足或未知，不輸出假資料。

## 風險與限制

- 小樣本只用於流程驗證，不代表新莊完整覆蓋。
- 300 公尺先用直線距離，不代表步行距離。
- H2 測試只驗證快速開發邏輯，MySQL schema 與索引仍需 Docker 驗證。
- 暫不接即時來源，因此不能宣稱目前剩餘車位準確。
