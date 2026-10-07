# S3-022 審查子集與展示交接

更新日期：2026-10-07。範圍：本機隔離 Docker project `s3022demo`，不是遠端上線或完整新莊驗收。

## 審查決策

一般汽車 DEMO 共用 catalog／即時資料的資格分類器。不以收費文字推測車種；只接受明確一般汽車類型。機車、大型車與身心障礙專用格不納入一般汽車推薦，不代表這些格位本身禁止合法使用。

| 來源 | CONFIRMED | UNKNOWN／需審查 | NOT_ELIGIBLE |
|---|---:|---:|---:|
| 新莊路邊 | 1,857 | 164 | 103 |
| 新莊路外 | 96 | 0 | 4 |

路邊未知為家長接送 103、時段禁停 32、裝卸貨專用 29；排除為身心障礙專用 84、大型車 10、機車 9。路外排除 4 筆汽車容量為零，負容量改為未知。條件式格位未解析時段，不放寬為 confirmed。

官方欄位以 [新北市路邊資料說明](https://data.ntpc.gov.tw/datasets/54a507c4-c038-41b5-bf60-bbecb9d052c6) 為依據；分類是本專案一般汽車展示政策，不宣稱官方完整資格碼表。主系統路邊正規化修正為讀 `parkingstatus`，沿用既有驗證：2 為 confirmed available、1 為 occupied，0／3／5 及未定義值保留未知；不再以 `cellstatus=0` 判斷空位。

## 匯入證據與保護

- 規則版本：`ntpc-general-car-subset-v1`。
- Manifest SHA-256：`264e00d6e87992e7be279261d46578c9dbc2895b1ee1eddd45fffd12fb95164f`。雜湊包含排序後全部 scoped 正規化紀錄及規則版本，包含排除項；來源變動須重新 dry-run／審查，不能沿用舊 hash 強行寫入。
- Scoped 2,224，選取 1,953（87.81%），排除 271；missing ID／duplicate ID／invalid coordinates 均 0。兩來源各一頁完整遍歷。路邊 HTTP 1、11,270,904 bytes、5,685ms；路外 catalog HTTP 1、390,465 bytes、345ms。
- 全體 recommendation 仍為 `REVIEW_ELIGIBILITY_RULES`；子集獨立 report 為 `READY_FOR_IMPORT_REVIEW`。需完整遍歷、整體 ID／座標品質通過、子集 gate 與相同 manifest，最後仍須顯式 confirm-write。
- 匯入前 verified mapping 10；首次新增 place／mapping 各 1,943，保留既有 10；匯入後路邊 1,857、路外 96。再跑新增 0、保留 1,953，DB 重複 mapping key 0。
- 寫入前 MySQL 完整 dump 存在本機 mysql container `/tmp/s3022-before-import.sql`，未進 Git。這是本次隔離環境回復素材，不是已演練遠端備份；正式備份／還原交 S3-024。回復時先停 API、將 dump 匯入隔離資料庫並失效 Redis，先確認 project 與資料庫，勿覆蓋其他環境。

重現流程（先 build，再 dry-run，審查當次 hash 後才加入寫入設定）：

```powershell
docker compose -p s3022demo up -d mysql redis
docker compose -p s3022demo build api
docker compose -p s3022demo run --rm `
  -e NTPC_CATALOG_IMPORT_COMMAND_ENABLED=true `
  -e NTPC_CATALOG_IMPORT_CONFIRM_WRITE=false `
  -e NTPC_CATALOG_DRY_RUN_PAGE_SIZE=40000 `
  -e NTPC_CATALOG_DRY_RUN_MAX_PAGES=3 api
# 同一命令改 confirm-write=true，另加 reviewed-subset=true 與當次 hash：
# NTPC_CATALOG_IMPORT_REVIEWED_SUBSET=true
# NTPC_CATALOG_IMPORT_EXPECTED_MANIFEST_SHA256=<reviewed hash>
docker compose -p s3022demo run --rm `
  -e PARKING_REFRESH_MEASUREMENT_COMMAND_ENABLED=true `
  -e PARKING_REFRESH_PAGE_SIZE=40000 `
  -e PARKING_REFRESH_MAX_PAGES=3 api
$env:GOOGLE_ROUTES_EXTERNAL_CALLS_ENABLED='false'
docker compose -p s3022demo up -d api
./tools/demo_catalog_smoke.ps1 -RequireFresh
```

此 measurement runner 是人工受控量測，不是正式自動排程／預算證據。API 預設刷新分頁設定仍須在 S3-023 驗證；不能把一次性 runner 成功當作前端刷新完成。

## 官方來源到查詢

首次刷新兩類來源均發布：路邊 run 1／snapshot 1，路外 run 2／snapshot 2。

| 量測 | 路邊 availability | 路外 availability |
|---|---:|---:|
| HTTP／頁數 | 1／1 | 1／1 |
| Bytes／原始筆數 | 11,270,904／30,941 | 36,022／1,000 |
| 發布觀測／mapping 命中 | 1,916／1,916 | 96／96 |
| 未 mapping／失敗 | 29,025／0 | 904／0 |
| HTTP／發布交易／總耗時 ms | 4,813／48,259／53,943 | 200／855／1,061 |
| 查詢不同位置／查詢耗時 ms | 1,857／216 | 96／9 |

發布筆數不等於唯一格位數。全市路邊原始資料 unique ID 30,653、重複列 288；snapshot 24 個重複 place key 多出 59 列，但本輪未見狀態衝突。全市涉及新莊的 28 組重複 ID 跨行政區標籤，座標／parkingstatus 相同。查詢讀取 1,857 個不同路邊格位，不宣稱發布層已實作去重／衝突拒絕。S3-023 須續驗品質與寫入延遲；以上只是單輪觀測，不是服務延遲承諾。

## 展示範圍與限制

`tools/demo_catalog_smoke.ps1` 驗證 500m、兩來源 snapshot、未知位數、空目錄、過期資料與不支援區域；`-RequireFresh` 會拒絕過期快照，需受控刷新後重跑。實測過期模式及拒絕均符合契約；第二次低頻刷新後 fresh smoke 通過：路邊展示點 READY／18 個目前有位觀測，其他展示點路邊 UNKNOWN／null，路外 READY，邊界空目錄仍不補零。這是當輪觀測，不保證使用者抵達時仍有位。

第二輪 run／snapshot 為路邊 3、路外 4，均成功發布。路邊 HTTP 1／一頁／11,270,904 bytes，HTTP 5,291ms、發布交易 43,089ms、總 49,095ms；路外 HTTP 1／一頁／36,023 bytes，HTTP 216ms、發布交易 655ms、總 878ms。mapping／唯一位置數同首輪。

- 農會展示點 `(25.0378576,121.4582263)`：圈內路外 2 個停車場。
- 路邊展示點 `(25.0583380,121.4521160)`：圈內路外 10 個停車場及路邊觀測。
- 未知剩餘位展示點 `(25.0414214,121.4647633)`：圈內路外 4 個停車場，保留至少一筆未知位數；本輪 96 個路外中 77 個 count 未知，不補零。
- 邊界空目錄點 `(25.0749,121.4899)`：`NO_CATALOG_RESULTS`，路邊 count=null、路外空清單，不表示客滿。
- 支援目的地仍是 MVP 矩形 lat 25.015..25.075、lon 121.415..121.490，並非行政區多邊形。迴龍停車場 050193 在目錄內但目的地座標超出支援框；矩形外回 `UNSUPPORTED_AREA`。跨邊界圈不宣稱 `COMPLETE`。
- 所有預測維持 `INSUFFICIENT_DATA`、probability=null、modelVersion=null。Google 外部 Routes 本輪關閉，真實 ETA／前端桌面手機／錯誤與刷新流程交 S3-023，不作本件驗收證據。
- 後端 94 tests 通過。全量資格、完整地理覆蓋留 S3-017；遠端驗收留 S3-024／S3-025。原始回應、DB dump、金鑰及私有樣本不進 Git。
