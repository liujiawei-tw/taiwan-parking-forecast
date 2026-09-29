# Seed data

更新日期：2026-09-29。狀態：Sprint 2 S2-020 官方來源小樣本資料，供本機流程驗證使用。

## 目的

Seed data 用來讓系統在完整正式目錄匯入前，可以先驗證以下流程：

- `parking_place` 可保存新莊路邊與路外停車位置。
- `source_mapping` 可保存來源系統、資料集、來源 ID 與系統內停車位置的對應。
- H2 test profile 可以載入小樣本並做 repository 測試。
- Docker MySQL 可以在明確開啟 seed 的情況下載入小樣本。
- `source_mapping` 使用官方新北市 Open Data ID，而不是合成 ID，讓即時 snapshot 能對上本地 catalog。

這批資料只用於流程驗證，不代表新莊完整停車覆蓋，也不代表目前即時車位狀態。

## 檔案位置

| 檔案 | 用途 |
| --- | --- |
| `backend/src/main/resources/db/seed/R__seed_xinzhuang_parking_catalog.sql` | Flyway repeatable seed migration，建立新莊小樣本停車目錄 |

## 小樣本內容

目前包含 10 筆官方來源小樣本停車位置：

| 類型 | 數量 | 說明 |
| --- | ---: | --- |
| 路邊格位 | 7 | 新北市路邊停車空位查詢中 `areacode=65000050` 的一般汽車格位小樣本 |
| 路外停車場 | 3 | 新北市路外公共停車場資訊中可對上即時剩餘位 ID 的新莊汽車停車場小樣本 |

每筆位置都包含：

- 顯示名稱
- 停車類型
- WGS84 經緯度
- 汽車適用狀態
- 啟用狀態
- service area：`NEW_TAIPEI_XINZHUANG`
- source mapping

## 來源與限制

這批 seed data 使用 `source_system = NTPC_OPEN_DATA`，來源 ID 來自新北市官方 Open Data：

- 路邊：`54A507C4-C038-41B5-BF60-BBECB9D052C6` 的 `id` 欄位。`cellid` 可能在不同路段重複，只放在 `raw_name_hint` 作追溯資訊。
- 路外：`B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68` 的 `ID` 欄位，並已用 `e09b35a5-a738-48cc-b0f5-570b67ad9c78` 即時剩餘位資料確認同 ID 存在。
- 路外靜態來源座標為 TWD97，seed 內保存的是轉換後 WGS84；原始 TWD97 值保留在 `raw_location_hint`。

限制：

- 不是 TDX 歷史資料。
- 不是新北市政府官方完整資料，只是可追溯的小樣本。
- 不包含目前剩餘車位；目前車位仍要讀已發布 snapshot。
- 不包含即時觀測時間。
- 不可作為 ML 訓練資料或準確率評估資料。
- 不可用來宣稱新莊區停車覆蓋率。2026-09-29 小量核對時，官方路邊新莊約 2,124 筆，路外靜態新莊汽車場小樣本核對到 97 筆；本 seed 未全量匯入。

## 載入方式

`test` 與 `local` profile 會載入：

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/seed
```

`docker` profile 預設只跑正式 schema migration，不自動載入 seed。若要在 Docker MySQL 手動驗證 seed，可在啟動時加入：

```text
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed
```

## 驗證

S2-020 更新後需驗證：

- H2 test profile 套用 V1 schema migration 與 repeatable seed migration。
- Repository 測試可讀到 `NTPC_OPEN_DATA` 的路邊與路外 `source_mapping`。
- 300 公尺查詢可在官方小樣本附近同時找到路邊格位與路外停車場。

