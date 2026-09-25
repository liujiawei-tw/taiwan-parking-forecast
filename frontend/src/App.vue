<script setup>
import { computed, ref } from 'vue'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const hasGoogleMapsKey = Boolean(import.meta.env.VITE_GOOGLE_MAPS_BROWSER_API_KEY)
const destination = ref('新莊體育館')
const queryResult = ref(null)
const loading = ref(false)
const errorMessage = ref('')

const roadsideSpotCount = computed(() => queryResult.value?.roadside?.availableSpots?.length ?? 0)
const parkingLotCount = computed(() => queryResult.value?.offStreet?.parkingLots?.length ?? 0)

async function createMockQuery() {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        requestToken: `mock-${Date.now()}`,
        origin: {
          label: '目前位置',
          latitude: 25.04,
          longitude: 121.45,
        },
        destination: {
          label: destination.value || '新莊體育館',
          latitude: 25.036,
          longitude: 121.4505,
        },
        departure: {
          type: 'NOW',
        },
      }),
    })

    if (!response.ok) {
      throw new Error(`API 回應 ${response.status}`)
    }

    queryResult.value = await response.json()
  } catch (error) {
    errorMessage.value = `建立 mock 查詢失敗：${error.message}`
  } finally {
    loading.value = false
  }
}

async function refreshMockQuery() {
  if (!queryResult.value?.query?.queryId) return

  loading.value = true
  errorMessage.value = ''

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${queryResult.value.query.queryId}/refresh`, {
      method: 'POST',
    })

    if (!response.ok) {
      throw new Error(`API 回應 ${response.status}`)
    }

    queryResult.value = await response.json()
  } catch (error) {
    errorMessage.value = `更新 mock 車位失敗：${error.message}`
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="app-shell">
    <section class="hero-panel" aria-labelledby="app-title">
      <div>
        <p class="eyebrow">Taiwan Parking Forecast</p>
        <h1 id="app-title">新莊停車抵達預測</h1>
        <p class="description">
          Sprint 2 先用 mock API 跑通查詢生命週期，之後才接 Google ETA、官方即時車位與預測模型。
        </p>
      </div>
      <div class="status-card">
        <span class="status-dot" aria-hidden="true"></span>
        S2-006 mock API
      </div>
    </section>

    <section class="search-panel" aria-label="目的地搜尋">
      <label for="destination">目的地</label>
      <div class="search-row">
        <input id="destination" v-model="destination" type="text" placeholder="搜尋地址、地標或店名">
        <button type="button" :disabled="loading" @click="createMockQuery">
          {{ loading ? '處理中' : '建立 mock 查詢' }}
        </button>
      </div>
      <p>
        目前使用固定新莊座標作 mock；Google Places 與 Google Routes 尚未接入。
      </p>
      <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
    </section>

    <section class="content-grid" aria-label="地圖與結果">
      <article class="map-placeholder">
        <div class="map-circle">300m</div>
        <h2>地圖區</h2>
        <p v-if="queryResult">
          Mock 目的地：{{ queryResult.destination.label }}，目前回傳 {{ roadsideSpotCount }} 個路邊格位、{{ parkingLotCount }} 個路外停車場。
        </p>
        <p v-else>
          建立 mock 查詢後，這裡會顯示目的地 300 公尺內的示範停車結果摘要。
        </p>
      </article>

      <aside class="result-panel" aria-label="查詢結果摘要">
        <h2>結果摘要</h2>

        <template v-if="queryResult">
          <div class="result-card">
            <strong>Query</strong>
            <span>ID：{{ queryResult.query.queryId }}</span>
            <span>狀態：{{ queryResult.query.status }}</span>
          </div>
          <div class="result-card">
            <strong>ETA</strong>
            <span>{{ queryResult.eta.provider }}</span>
            <span>抵達：{{ queryResult.eta.estimatedArrivalAt }}</span>
          </div>
          <div class="result-card">
            <strong>路邊停車</strong>
            <span>{{ queryResult.roadside.message }}</span>
            <span>目前示範有位：{{ queryResult.roadside.currentlyAvailableSpaces }} 格</span>
          </div>
          <div class="result-card">
            <strong>路外停車場</strong>
            <span>{{ queryResult.offStreet.message }}</span>
            <span>示範停車場：{{ parkingLotCount }} 座</span>
          </div>
          <div class="result-card warning">
            <strong>抵達時預測</strong>
            <span>{{ queryResult.predictions.status }}</span>
            <span>{{ queryResult.predictions.message }}</span>
          </div>
          <button class="secondary-button" type="button" :disabled="loading" @click="refreshMockQuery">
            更新 mock 車位
          </button>
        </template>

        <template v-else>
          <div class="result-card">
            <strong>ETA</strong>
            <span>等待建立 mock 查詢</span>
          </div>
          <div class="result-card">
            <strong>路邊停車</strong>
            <span>等待後端 mock API</span>
          </div>
          <div class="result-card">
            <strong>路外停車場</strong>
            <span>等待後端 mock API</span>
          </div>
        </template>

        <div class="result-card muted">
          <strong>設定狀態</strong>
          <span>API：{{ apiBaseUrl }}</span>
          <span>Google Key：{{ hasGoogleMapsKey ? '已設定' : '未設定' }}</span>
        </div>
      </aside>
    </section>
  </main>
</template>
