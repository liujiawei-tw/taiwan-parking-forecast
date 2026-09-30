# S2-HARDEN-001 人工驗收紀錄

更新日期：2026-09-30。狀態：待執行人工驗收；本文件提供可重現步驟與紀錄格式，不保存金鑰、原始 API 回應或截圖中的敏感設定。

## 目的

補齊 S2-023／S2-024 未完成的人工驗收：

- 真實 browser-restricted `VITE_GOOGLE_MAPS_BROWSER_API_KEY` 下，Google Maps JavaScript API 與 Place Autocomplete Widget (New) 可載入並選取新莊目的地。
- 前端送給主系統的查詢 payload 只包含使用者選定座標、顯示名稱、出發設定與 `requestToken`，不提交自算 ETA、目前車位數或預測機率。
- 無 key fallback 仍可用於 CI、本機低成本驗證與開發。
- 結果頁重新可見時先 `GET /api/v1/parking-queries/{queryId}` 只讀恢復，再依 stale／no snapshot 狀態送一次 `trigger=VISIBLE_AUTO_REFRESH`。
- 手動更新仍使用 `trigger=MANUAL_REFRESH`，且不重算 Google ETA。

## 前置條件

1. 本機 `.env.local` 或 shell 環境設定瀏覽器端 Google key，但不得提交：

   ```powershell
   $env:VITE_GOOGLE_MAPS_BROWSER_API_KEY="只放本機，不寫入文件"
   ```

2. 該 key 應限制 HTTP referrer，例如 `http://localhost:5173/*`，並只允許 Maps JavaScript API 與 Places API (New) 的瀏覽器用途。
3. 後端 API 可在本機啟動；Google Routes server key 可維持停用，避免本驗收額外消耗 Routes。
4. DevTools Network 可觀察 `parking-queries` 與 `refresh` 請求，但驗收紀錄只寫結論與狀態碼，不貼完整 response body。

## 驗收步驟

### A. 無 key fallback

1. 不設定 `VITE_GOOGLE_MAPS_BROWSER_API_KEY` 或保留 `.env.example` 的 placeholder。
2. 執行前端 build 與測試。
3. 開啟前端，確認畫面顯示手動座標模式。
4. 使用預設新莊體育館座標送出查詢。

紀錄：

- 日期／環境：
- `npm run test`：
- `npm run build`：
- 畫面是否維持手動座標模式：
- 查詢 payload 是否未包含 ETA／車位數／預測機率：

### B. 真實 Places 選取

1. 設定受限制的 browser key。
2. 啟動前端 dev server。
3. 搜尋並選取一個新莊目的地，例如「新莊體育館」。
4. DevTools 確認建立查詢時只呼叫主系統 `POST /api/v1/parking-queries`，且前端沒有直接呼叫政府停車 API。
5. DevTools 檢查 request payload 不包含 Google key、ETA、目前車位數或預測機率。

紀錄：

- 日期／環境：
- 使用的 key 限制方式摘要，不記錄 key 本身：
- Places 是否成功選取目的地：
- 建立查詢 HTTP 狀態：
- payload 邊界檢查結果：

### C. 可見頁受控刷新

1. 建立一筆查詢，使回應中路邊或路外狀態為 `STALE` 或 `NO_REALTIME_SNAPSHOT`。
2. 切到其他分頁或讓頁面隱藏，確認前端停止輪詢與自動刷新。
3. 回到頁面，確認 Network 先出現 `GET /api/v1/parking-queries/{queryId}`。
4. 若最新狀態仍需更新且沒有 `refreshInProgress`，確認只送一次 `POST /api/v1/parking-queries/{queryId}/refresh?trigger=VISIBLE_AUTO_REFRESH`。
5. 保持頁面可見且狀態未變時，確認不會密集重送同一組自動刷新。

紀錄：

- 日期／環境：
- 觸發前來源狀態：
- 重新可見後第一個主系統請求是否為 GET：
- 自動刷新 trigger：
- 同一 query/status 是否只送一次：

### D. 手動刷新

1. 按「更新車位」。
2. 確認請求為 `POST /api/v1/parking-queries/{queryId}/refresh?trigger=MANUAL_REFRESH`。
3. 確認沒有新的 Google Routes `computeRoutes` 請求。

紀錄：

- 日期／環境：
- 手動刷新 trigger：
- 是否重算 ETA：
- 後端回應摘要：

## 完成口徑

完成本文件的紀錄後，才可宣稱 S2-HARDEN-001 的人工驗收補齊。若缺少真實 browser key，仍可完成自動測試與無 key fallback，但必須把真實 Places 驗收標為未完成，不得用 mock 或截圖代替。
