# S3-010 路邊狀態碼驗證與 relabel 計畫

更新日期：2026-10-03。狀態：第一版狀態碼驗證報告；尚未修改 collector、尚未重產訓練資料、尚未接入線上模型。

## 目的

S3-006 至 S3-008 發現 roadside reliable label 為 0，原因不是沒有路邊資料，而是 collector 舊規則只將 `parkingstatus=0` 視為空位；新北市官方即時資料主要出現 `1`、`2`、`3`、`5`。本文件用官方 CSV 全檔、collector 歷史樣本、TDX 路邊歷史樣本、交通部停車資料標準、新北市前台圖例與 ParkBoss 第三方呈現做交叉驗證，提出安全的 relabel 計畫。

## 可重跑工具

新增 `tools/roadside_status_validation.py`，只輸出 aggregate 摘要，不保存官方 CSV 原文或 row-level collector 資料。

```powershell
python tools/roadside_status_validation.py --collector-samples data/private/collector-samples --tdx-spot-samples data/raw/tdx/new-taipei/on-street-spot-availability --allow-insecure-official-csv --output data/private/roadside-status-validation-2026-10-03.md
```

## 驗證來源

| 來源 | 用途 | 結論 |
| --- | --- | --- |
| 新北市官方 CSV 全檔 `https://data.ntpc.gov.tw/api/datasets/54a507c4-c038-41b5-bf60-bbecb9d052c6/csv/file` | 實際欄位與狀態組合分布 | 可確認 `parkingstatus`、`cellstatus`、`isnowcash` 的真實值，但沒有 enum 字典。 |
| 新北市 OpenAPI `https://data.ntpc.gov.tw/api/v1/openapi/units/1130000` | 確認 dataset endpoint | 有 endpoint 與資料集描述，沒有 `parkingstatus`／`cellstatus` enum。 |
| 新北市前台地圖 | 官方顯示語意 | 圖例顯示紅色有車、綠色空位、灰色非收費時段、黃色時段性禁停、橙色施工／裝卸貨／身障車格。 |
| TDX 新北市路邊格位歷史樣本 | 與交通部 `SpotStatus` 標準交叉比對 | 本機兩份歷史 CSV 出現 `SpotStatus=1`、`2`、`254`；可支持 `1/2` 的 occupied/free 語意，但不能直接證明新北市 `parkingstatus=5`。 |
| 交通部停車資料標準 | 狀態碼高可信參考 | `SpotStatus` 可作參考：`1=使用中`、`2=空位`、`3=不開放`、`255=未知`。 |
| ParkBoss 新北市即時停車位地圖 | 第三方實作交叉驗證 | 可觀察路邊格 `0/1`、`1/1` 呈現，但不得取代官方資料字典。 |

## 官方即時 CSV 摘要

本輪工具讀取官方 CSV 全檔，未保存原始 CSV。全檔 31,560 rows，新莊區 `areacode=65000050` 有 2,149 rows。

### 全檔狀態組合

| parkingstatus | cellstatus | isnowcash | Count |
| --- | --- | --- | ---: |
| `3` | `Y` | `false` | 16,029 |
| `1` | `Y` | `false` | 7,020 |
| `2` | `N` | `false` | 4,009 |
| `3` | `N` | `false` | 3,792 |
| `5` | `N` | `false` | 365 |
| `5` | `Y` | `false` | 332 |
| `1` | `N` | `false` | 6 |
| `2` | `Y` | `false` | 4 |
| `2` | `N` | `true` | 2 |
| `1` | `Y` | `true` | 1 |

### 新莊區狀態組合

| parkingstatus | cellstatus | isnowcash | Count |
| --- | --- | --- | ---: |
| `3` | `Y` | `false` | 1,733 |
| `3` | `N` | `false` | 186 |
| `1` | `Y` | `false` | 145 |
| `2` | `N` | `false` | 49 |
| `5` | `Y` | `false` | 30 |
| `5` | `N` | `false` | 6 |

## Collector 歷史樣本摘要

來源為 `data/private/collector-samples/` 中 2026-09-24 至 2026-10-02 共 9 天 ZIP；原始 ZIP 不提交 Git。

| parkingstatus | cellstatus | Count |
| --- | --- | ---: |
| `3` | `Y` | 586,530 |
| `3` | `N` | 63,112 |
| `1` | `Y` | 49,735 |
| `2` | `N` | 16,807 |
| `5` | `Y` | 10,290 |
| `5` | `N` | 2,058 |

舊 collector 規則 `parkingstatus=0 -> true`、`parkingstatus=1 -> false` 對這批資料不適合，因為 9 天樣本沒有任何 `parkingstatus=0`；`parkingstatus=2` 被丟成空值，導致 roadside positive label 為 0。

## TDX 歷史樣本摘要

來源為本機 `data/raw/tdx/new-taipei/on-street-spot-availability/` 中 2026-08-09 與 2026-08-27 兩份新北市路邊格位歷史 CSV；原始 CSV 不提交 Git。

| 欄位 | 值 | Count |
| --- | --- | ---: |
| `SpotStatus` | `1` | 5,744,482 |
| `SpotStatus` | `2` | 1,469,909 |
| `SpotStatus` | `254` | 1,208,120 |
| `ServiceStatus` | `1` | 7,214,391 |
| `ServiceStatus` | `2` | 1,208,120 |
| `DeviceStatus` | `1` | 8,422,511 |
| `ChargeStatus` | `1` | 8,422,511 |

TDX 樣本可支持 `SpotStatus=1` 為使用中、`SpotStatus=2` 為空位，且 `254` 在樣本中與 `ServiceStatus=2` 同量出現，應保守視為非一般可靠可停 label。這只能作為新北市 `parkingstatus=1/2` 的交叉證據；`parkingstatus=3/5` 仍需靠官方前台圖例與保守拒絕策略處理。

## 建議 Mapping

| parkingstatus | 建議狀態 | 可作訓練 label | 依據 | 備註 |
| --- | --- | --- | --- | --- |
| `1` | `OCCUPIED` | negative | 交通部 `SpotStatus=1 使用中`；前台與第三方呈現有車語意。 | 可作可靠負例候選。 |
| `2` | `AVAILABLE` | positive | 交通部 `SpotStatus=2 空位`；官方 CSV 中常與 `cellstatus=N` 搭配。 | 可作可靠正例候選，但需在 collector 改版後重跑 dry-run。 |
| `3` | `RESTRICTED_OR_CLOSED` | no | 交通部 `SpotStatus=3 不開放`；前台有灰色／黃色／橙色非一般可停狀態。 | 不可當一般可停空位，也不要當一般無位負例。 |
| `5` | `UNKNOWN_OR_SPECIAL` | no | 未在交通部 `SpotStatus` 標準中確認；官方 CSV 與新莊樣本存在但語意未明。 | 保守拒絕，保留 raw code。 |
| other | `UNKNOWN` | no | 未確認狀態碼。 | 保守拒絕。 |

## 工程判定

- Roadside 並非資料量不足；真正問題是舊 label rule 錯位，沒有吃到 `parkingstatus=2` 這批高可信空位正例候選。
- 目前仍不能直接改線上預測；應先在 `parking-data-collector` 導入 versioned label rule，重新產出或 re-label historical samples。
- `parkingstatus=3` 與 `5` 不能為了增加樣本數而硬塞成 0 或 1，否則會把管制、特殊或未知狀態訓練成一般車位。
- 新北市官方前台圖例有助於判斷一般顯示語意，但不是 `parkingstatus` enum 字典；文件與程式仍須把「官方可證明」與「交叉推論」分開。

## 後續

1. 在 `parking-data-collector` 新增 label rule 版本，例如 `ntpc_spotstatus_1_occupied_2_free_3_closed_5_unknown_v1`。
2. 新增欄位或 staging 規格：`availability_state`、`is_reliable_label`、`rejection_reason`，並保留 raw status。
3. 重產或 re-label 2026-09-24 至 2026-10-02 roadside 樣本。
4. 回主系統重跑 S3-007 label dry-run 與 S3-008 baseline experiment，確認 roadside usable labels、positive/negative 比例、拒絕原因與時間切分。
