# 新北市路邊停車路段剩餘位歷史資料 v1

此資料夾保存 TDX 原始 ZIP 及解壓後七個每日 CSV。原始資料由 Git 忽略，不推送 GitHub。

- 日期：2026-08-25 至 2026-08-31，共 7 個每日 CSV。
- ZIP：`99a64f1c-38df-4eef-a3c2-68c133d16015.zip`，19,245,767 bytes。
- SHA-256：`10D2DC3D39F880BA3BC3D5082AED28E922095B559F92D8DE22F7FDE014290A16`。
- 購買頁：已購買，顯示 18.4 MB、預估 1.02 點；實扣點數待核對。
- 來源範圍是**新北市路邊停車路段**，並非新莊區專檔，也不是路外停車場。需與路段基本資料對照後才能篩選新莊。
- 初步欄位包括 `ParkingSegmentID`、`TotalSpaces`、`AvailableSpaces`、`SpaceType`、`SpaceTypeAvailableSpaces`、`DataCollectTime`。首筆 `ParkingSegmentID` 為空白。
- 唯讀盤點：七日共 3,907,867 列；以目前新莊即時資料的 81 個非空 `roadId`，可對上 341,473 列。路段名稱另出現一個目前清單沒有的 `RA1`，也有 ID 空白的紀錄，因此當前清單不是歷史新莊資料的完整定義。
- 這 341,473 列的 `SpaceTypeAvailableSpaces` 全部為 0，但 `AvailableSpaces` 在多數列大於 0；不可把前者直接當汽車空位標籤，也不可將不同 `SpaceType` 列的總剩餘位重複加總。正式使用前須核對官方欄位語意與每筆資料粒度。

資料清單見 `docs/new-taipei-historical-inventory.md`。來源：[TDX 歷史路段 API 文件](https://tdx.transportdata.tw/api-service/swagger/historical/bfc9dd07-b597-4851-b20f-82f2c53a0fe1#/OnStreetParkingCityAPI/Historical_ParkingOnStreetSegmentAvailability)。
