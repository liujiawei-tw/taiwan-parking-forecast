# S3-021 模型實驗收尾與續作條件

更新日期：2026-10-07。狀態：離線實驗證據／限制交接已完成；正式模型不合格、沒有發布模型產物或接入線上推論。

## 結論

模型工作可在本次審查告一段落，DEMO 繼續提供官方目前資訊與 ETA，predictions 保持空機率、空模型版本與 INSUFFICIENT_DATA。本件完成的是實驗審查，不代表預測產品完成。

路邊高分的主要已確認原因是資料中的狀態完全不變，entity baseline 記住各圈固定答案即可滿分。路外有觀測變化，但 logistic regression 的 Brier 仍未勝過 persistence。兩者舊實驗均有跨切分標籤污染，不能用原報告宣稱獨立 holdout 或模型准入通過。

## 可重現設定與結果

- 原實驗程式基線：commit 9f77841；本次診斷增加 audit 工具，不改舊 baseline 計算。Python 3.13.5，標準函式庫，沒有官方／Google／TDX API 請求。
- 設定：ml-training/config/training-config.example.json，schema=ml-training-config-v1；SHA-256 為 ec59feb945f929151faed51d69be4d6762b0129f2e2eb52198a41295d034a3c8。
- 樣本：2026-09-24～2026-10-02 共 9 個 daily ZIP；2026-09-23 不使用。train=09-24～09-30，test=10-01～10-02；500m、50 圈、30／60 分鐘 lead、最近觀測 ±15 分鐘。
- Roadside 規則：ntpc_roadside_confirmed_available_status_2_else_false_v1；false 是未確認有一般可停空位，不能宣稱一定有車或客滿。Offstreet 未知／負值拒絕，抵達時 available_car > 0 作正例。
- private audit JSON 保存各 ZIP 與設定雜湊；不輸出格位 ID、逐列標籤或模型檔。原始 ZIP 不改寫。相關目錄已以 git check-ignore 確認不進 Git。

```powershell
python ml-training/scripts/audit_frozen_experiment.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/s3-021-frozen-experiment-audit.json
python ml-training/scripts/run_baseline_experiment.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/s3-021-baseline-reproduction.md
python -m unittest discover -s ml-training/tests
```

重現報告的 4 列 dataset status 與 16 列 metrics 和既有 private baseline 報告一致；詳細既有指標見 s3-008-baseline-experiment.md。

| 類型／lead | train／test labels | persistence Brier | logistic Brier |
| --- | ---: | ---: | ---: |
| 路邊 30m | 10,750／3,900 | 0.0025 | 0.0074 |
| 路邊 60m | 10,900／3,850 | 0.0025 | 0.0074 |
| 路外 30m | 8,516／3,189 | 0.0152 | 0.0220 |
| 路外 60m | 8,638／3,148 | 0.0182 | 0.0247 |

persistence 使用固定 0.95／0.05 分數，非經校準的機率；coverage=1 是已被 sample builder 接受的 test 樣本內分母，不是全新莊查詢覆蓋。上述分數保留供重現，不作上線 gate。

## 已確認：路邊時序與重疊

- 728,532 rows、2,124 格、343 個不同 collected_at；每格均有 343 筆。726,408 次相鄰比較，raw parkingstatus 與二元 label 的變化均為 0。整體 ID＋raw status 向量只有 1 個不同快照。
- 各 train／test、30／60m 的 50 個圈均無 label 變化；10 圈永遠正例、40 圈永遠負例。目前狀態與抵達 label 不一致為 0。20% 正例率是固定圈的空間比例，不能當成未來狀態轉換的機率證據。
- 1,225 個圈配對中 386 對共享格位，15 對的格位集合完全相同；平均 Jaccard 0.239679、最大 1。5,339 次圈內格位 membership 只包含 1,091 個不同格位；915 格出現在多圈，最多一格共用於 26 圈。重複樣本不能代表獨立目的地泛化。
- collected_at 間隔中位數約 30 分鐘、最短 3.41、最長 464.80 分鐘；來源回應時間缺失 36,108 rows。收集輪數不等於官方更新次數，也不足以驗證 5 分鐘 ETA 的變化。

這只能證明本批 collector 輸出沒有變化。官方 feed 固定、collector 重用回應、上游／中間快取或其他故障都仍可能；未檢查逐輪來源產製紀錄，不能指定根因，更不能推論現場九天沒有停車變化。collector 是離線資料來源，不能據此判定主系統即時來源同樣停滯。

## 已確認：路外有變化，仍缺風險證據

- 50,516 rows、149 lots，50,367 次相鄰 available_car／unknown 比較有 6,001 次變化（11.91%）；121 個 lot 在其收錄期間無變化。這含未知狀態，不等於全部都是有效車位變化。
- 可用 test 樣本僅 41 lots：30m／60m 各有 36 lots label 不變。label 變化分別 44／42 次；目前與抵達 label 不一致分別 45／55 筆。
- 高正例率約 92.7%，且多數 lot 固定有位。尚未針對 near-full、長 ETA、precision／FPR 信賴程度與校準驗證；平均 accuracy 不足以證明快滿時可用。

## 洩漏與時間語意審查

| 類型／lead | usable pairs | train label 使用 test 日期觀測 |
| --- | ---: | ---: |
| 路邊 30m | 14,650 | 50 |
| 路邊 60m | 14,750 | 100 |
| 路外 30m | 11,705 | 41 |
| 路外 60m | 11,786 | 82 |

1. split 只依 feature 日期選擇，沒有禁止訓練 label 跨到 test。上表確認訓練使用 holdout 期間觀測，是污染；S3-019 必須依 arrival 與標籤實際可知時間清除邊界樣本，必要時加 embargo，測試守住邊界。
2. feature 值在目前程式只取 asOf 觀測，未直接讀 arrival 值；但圈集合由整批最後座標／ID 建立，是潛在未來 catalog 資訊。這批格位座標變化數為 0，因此未證明座標變更造成高分；仍須用預測當下或凍結 train catalog 建圈，不能保證 unseen entity 無偏。
3. weekday／hour 讀 collected_at 的 UTC，並非產品的臺北時間。S3-019 需明確時區與真實 source observed time；API response time 只表示回應時間，不能證明逐筆更新。
4. 最近 arrival label 可能較 target 提早或延後：路邊本批約 -3.17～+7.70 分鐘、路外 -3.17～+13.48 分鐘。是近似時間窗答案，不能宣稱精確抵達瞬間。
5. sample builder 可使用部分圈內存在的 rows，未建立正式一般汽車資格／完整 coverage 准入。此前仍是候選圈實驗，不能等同線上任意目的地。
6. 同一 holdout 已在半徑 300／1000／500m 實驗中多次查看。不能宣稱新一輪調參是獨立泛化驗證；本次只重現舊設定，不選新參數。

## 恢復模型工作的條件與責任

- S3-018：保留來源觀測、收集與回應時間差異、品質與 mapping 版本；受控歷史匯出與保留策略具備可用資料。
- S3-019：先查明來源更新鏈，補有真實狀態轉換的新日期、週末／工作日與 near-full；修正跨 split 標籤、catalog asOf、臺北時區與 coverage。測試未知不補 0、feature 不讀未來、label 不跨 holdout；說明短 ETA 是否被收集頻率支持。
- S3-020：在新 holdout 評估前固定 validation 策略、校準、FPR、near-full precision、樣本量與 abstention 門檻。未量測門檻保持暫定，不以本次分數當承諾。
- S3-026：新增 train／validation／未用過 test，時間順序切分並檢查空間重疊；先比較 persistence／歷史 baseline／logistic regression。只有資料與改善證據支持才擴充演算法。
- S3-009／S3-027：通過 gate 與 coverage 條件後才發布版本化產物、離線推論及線上推論；失敗只降級 predictions，不影響目前車位／ETA。

新增 audit 的 10 個 ML workspace 測試通過（含跨切分診斷、重疊、constant-series 與 ZIP fixture 整合）；此次不更動產品查詢，未跑後端／前端全套。模型工作停在此交接，下一件 DEMO 工作為 S3-022，先處理 S3-017 資格審查。
