<script setup>
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const hasGoogleMapsKey = Boolean(import.meta.env.VITE_GOOGLE_MAPS_BROWSER_API_KEY)
</script>

<template>
  <main class="app-shell">
    <section class="hero-panel" aria-labelledby="app-title">
      <div>
        <p class="eyebrow">Taiwan Parking Forecast</p>
        <h1 id="app-title">新莊停車抵達預測</h1>
        <p class="description">
          第一版會讓使用者像 Google Maps 一樣搜尋目的地，查看 300 公尺內路邊與路外汽車停車選項。
        </p>
      </div>
      <div class="status-card">
        <span class="status-dot" aria-hidden="true"></span>
        Sprint 2 前端骨架
      </div>
    </section>

    <section class="search-panel" aria-label="目的地搜尋">
      <label for="destination">目的地</label>
      <div class="search-row">
        <input id="destination" type="text" placeholder="搜尋地址、地標或店名" disabled>
        <button type="button" disabled>建立查詢</button>
      </div>
      <p>
        Google Places Autocomplete 會在後續 Issue 接入；目前先保留搜尋列位置與互動狀態。
      </p>
    </section>

    <section class="content-grid" aria-label="地圖與結果">
      <article class="map-placeholder">
        <div class="map-circle">300m</div>
        <h2>地圖區</h2>
        <p>後續會接 Google Maps JavaScript API，顯示目的地、300 公尺範圍、路邊格位與停車場。</p>
      </article>

      <aside class="result-panel" aria-label="查詢結果摘要">
        <h2>結果摘要</h2>
        <div class="result-card">
          <strong>ETA</strong>
          <span>等待 Google Routes API</span>
        </div>
        <div class="result-card">
          <strong>路邊停車</strong>
          <span>等待後端查詢 API</span>
        </div>
        <div class="result-card">
          <strong>路外停車場</strong>
          <span>等待後端查詢 API</span>
        </div>
        <div class="result-card muted">
          <strong>設定狀態</strong>
          <span>API：{{ apiBaseUrl }}</span>
          <span>Google Key：{{ hasGoogleMapsKey ? '已設定' : '未設定' }}</span>
        </div>
      </aside>
    </section>
  </main>
</template>