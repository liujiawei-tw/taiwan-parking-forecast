# Seed data

更新日期：2026-09-26。狀態：Sprint 2 S2-011 小樣本資料，供本機流程驗證使用。

## 目的

S2-011 的 seed data 用來讓系統在尚未接官方即時 API 前，可以先驗證以下流程：

- `parking_place` 可保存新莊路邊與路外停車位置。
- `source_mapping` 可保存來源系統、資料集、來源 ID 與系統內停車位置的對應。
- H2 test profile 可以載入小樣本並做 repository 測試。
- Docker MySQL 可以在明確開啟 seed 的情況下載入小樣本。

這批資料只用於流程驗證，不代表新莊完整停車覆蓋，也不代表官方即時資料。

## 檔案位置

| 檔案 | 用途 |
| --- | --- |
| `backend/src/main/resources/db/seed/R__seed_xinzhuang_parking_catalog.sql` | Flyway repeatable seed migration，建立新莊小樣本停車目錄 |

## 小樣本內容

目前包含 5 筆示範停車位置：

| 類型 | 數量 | 說明 |
| --- | ---: | --- |
| 路邊格位 | 3 | 合成的新莊路邊汽車格位示範資料 |
| 路外停車場 | 2 | 合成的新莊路外停車場示範資料 |

每筆位置都包含：

- 顯示名稱
- 停車類型
- WGS84 經緯度
- 汽車適用狀態
- 啟用狀態
- service area：`NEW_TAIPEI_XINZHUANG`
- source mapping

## 來源與限制

這批 seed data 使用 `source_system = SYSTEM`，來源 ID 以 `SEED-XINZHUANG-*` 開頭，明確表示它是系統內的小樣本資料，不是官方來源 ID。

限制：

- 不是 TDX 歷史資料。
- 不是新北市政府官方完整資料。
- 不包含目前剩餘車位。
- 不包含即時觀測時間。
- 不可作為 ML 訓練資料或準確率評估資料。
- 不可用來宣稱新莊區停車覆蓋率。

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

S2-011 已驗證：

- Docker Maven test：8 tests／0 failures。
- H2 test profile 套用 V1 schema migration 與 repeatable seed migration。
- 臨時 Docker Compose MySQL 驗證：API healthy，MySQL 中有 3 筆路邊、2 筆路外、5 筆 source mapping。

