# S3-017 完整新莊 catalog／mapping dry-run 驗收

更新日期：2026-10-04。狀態：已完成一輪真實官方 dry-run；尚未寫入完整 catalog，尚未把新莊覆蓋宣稱為完整。

2026-10-07 續作：S3-022 已完成一般汽車子集分類／匯入，見 `s3-022-reviewed-catalog-demo.md`。本文件以下保留 10/04 歷史量測；最新為路邊 confirmed 1,857、unknown 164、排除 103，路外 confirmed 96、排除 4，已匯入子集 mapping 1,953。S3-017 全量資格與行政區邊界驗收仍未完成，不能以子集完成關閉。

## 目的

S3-017 驗證新莊 MVP 是否能用新北市官方 Open Data 建立完整 catalog／source mapping 的候選清單，並分開記錄來源完整性、資料品質、車種資格與既有 verified mapping 覆蓋。

本輪只做 dry-run：

- 不設定 `confirm-write=true`。
- 不寫入 `parking_place` 或 `source_mapping`。
- 不保存官方原始 response body。
- 不提交 raw data、金鑰、token 或 private sample。

## 執行方式

以獨立 Docker Compose project 啟動 MySQL／Redis，並執行一次性 catalog import command：

```powershell
docker compose -p s3017dryrun up -d mysql redis
docker compose -p s3017dryrun build api
docker compose -p s3017dryrun run --rm `
  -e NTPC_CATALOG_IMPORT_COMMAND_ENABLED=true `
  -e NTPC_CATALOG_IMPORT_CONFIRM_WRITE=false `
  -e NTPC_CATALOG_DRY_RUN_PAGE_SIZE=40000 `
  -e NTPC_CATALOG_DRY_RUN_MAX_PAGES=3 `
  api
```

`NTPC_CATALOG_IMPORT_CONFIRM_WRITE=false` 是關鍵安全條件；本輪 log 明確顯示 `NTPC catalog import write skipped because confirm-write=false`。

## 真實量測摘要

| 項目 | 數值 |
| --- | ---: |
| service area | `NEW_TAIPEI_XINZHUANG` |
| scoped rows | 2,224 |
| accepted candidates | 2,037 |
| accepted candidate percent | 91.59% |
| missing source IDs | 0 |
| duplicate source IDs | 0 |
| invalid coordinates | 0 |
| unknown eligibility | 174 |
| existing verified mappings | 10 |
| verified mapping coverage percent | 0.45% |
| recommendation | `REVIEW_ELIGIBILITY_RULES` |
| traversal complete | `true` |

完整 dry-run log 摘要：

```text
NTPC catalog dry-run completed: serviceArea=NEW_TAIPEI_XINZHUANG, scopedRows=2224, acceptedCandidates=2037, missingSourceIds=0, duplicateSourceIds=0, invalidCoordinates=0, unknownEligibility=174, existingVerifiedMappings=10, acceptedCandidatePercent=91.59, verifiedMappingCoveragePercent=0.45, recommendation=REVIEW_ELIGIBILITY_RULES, roadsidePages=1, roadsideHttpRequests=1, roadsideBytes=11270904, roadsideLatencyMs=4815, offStreetPages=1, offStreetHttpRequests=1, offStreetBytes=390418, offStreetLatencyMs=775, traversalComplete=true
```

## HTTP 與資料量

| 來源 | pages | HTTP requests | bytes | latency |
| --- | ---: | ---: | ---: | ---: |
| 新北市路邊全量資料，本地篩 `areacode=65000050` | 1 | 1 | 11,270,904 | 4,815 ms |
| 新北市路外靜態 catalog，本地篩 `AREA=新莊區` | 1 | 1 | 390,418 | 775 ms |

本輪沒有打 TDX，也沒有下載 TDX raw data。TDX 仍只作後續全臺擴充與交叉驗證候選。

## 驗收判讀

可接受的部分：

- 來源 traversal complete，沒有因 `max-pages` 截斷造成缺頁。
- 缺 source ID、重複 source ID、座標異常皆為 0。
- accepted candidate 達 2,037 筆，可支撐後續人工審查與受控匯入流程。
- command 預設安全：未顯式確認寫入時不會改 DB。

尚不可匯入的部分：

- `unknownEligibility=174`，車種／一般汽車資格仍需審查。
- 2,224 筆 scoped rows 中，扣除 2,037 accepted 與 174 unknown eligibility，仍有 13 筆屬於非 confirmed eligibility 的剩餘拒絕候選；目前 aggregate log 未再細分為 `NOT_ELIGIBLE` 或其他類型，後續若要更精準可擴充 dry-run report 欄位。
- 既有 verified mapping 只有 10 筆，覆蓋率 0.45%；因此目前仍是 `PARTIAL`，不可輸出完整 500 公尺路邊區域機率或宣稱完整新莊 catalog 已上線。

## 下一步

1. 先檢查 174 筆 `UNKNOWN` 的 raw hint 規則，決定能否用穩定欄位判斷一般汽車資格。
2. 若資格規則可被文件化與測試鎖定，再重跑 dry-run，目標是 recommendation 進到 `READY_FOR_IMPORT_REVIEW`。
3. 只有 dry-run 通過且 Sponsor 同意時，才用 `confirm-write=true` 做受控匯入。
4. 匯入後仍需重跑 coverage report 與 500 公尺查詢覆蓋檢查；DB 寫入成功不等於模型可上線。

## 已知限制

- 本輪是單輪量測，可作驗收證據與設定依據，不是 SLA。
- 覆蓋邊界仍只確認到新莊 service scope；任一目的地 500 公尺圓是否完整落在已收錄範圍，仍需要邊界資料或查詢時覆蓋判定。
- 路外 catalog 第一版使用官方座標作位置與入口座標，後續若取得更可信入口點，需要版本化更新。
