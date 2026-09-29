<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const googleMapsApiKey = import.meta.env.VITE_GOOGLE_MAPS_BROWSER_API_KEY || ''
const hasGoogleMapsKey = Boolean(googleMapsApiKey && googleMapsApiKey !== 'replace-with-local-browser-key')

const defaultDestination = {
  label: '新莊體育館',
  address: '新北市新莊區中華路一段75號',
  latitude: 25.036,
  longitude: 121.4505,
}

const defaultOrigin = {
  label: '目前位置（測試座標）',
  latitude: 25.04,
  longitude: 121.45,
}

const mapElement = ref(null)
const autocompleteHost = ref(null)
const selectedDestination = ref({ ...defaultDestination })
const origin = ref({ ...defaultOrigin })
const departureType = ref('NOW')
const scheduledDepartureLocal = ref('')
const queryResult = ref(null)
const loading = ref(false)
const locating = ref(false)
const errorMessage = ref('')
const mapStatus = ref(hasGoogleMapsKey ? '正在載入 Google 地圖' : '未設定瀏覽器 Google Maps key，使用手動座標模式')
const selectedItemId = ref('')

let googleMapsPromise
let map
let destinationMarker
let radiusCircle
let infoWindow
let parkingMarkers = []
const markersById = new Map()

const roadsideSpotCount = computed(() => queryResult.value?.roadside?.availableSpots?.length ?? 0)
const parkingLotCount = computed(() => queryResult.value?.offStreet?.parkingLots?.length ?? 0)
const resultDestination = computed(() => queryResult.value?.destination ?? selectedDestination.value)
const etaMessage = computed(() => {
  const eta = queryResult.value?.eta
  if (!eta) return '送出查詢後由後端 Google Routes 估算'
  if (eta.status === 'READY') return `預估抵達：${formatDateTime(eta.estimatedArrivalAt)}`
  return eta.message || 'ETA 暫時無法估算'
})
const resultItems = computed(() => {
  const lots = queryResult.value?.offStreet?.parkingLots ?? []
  const spots = queryResult.value?.roadside?.availableSpots ?? []
  return [
    ...lots.map((lot) => ({
      id: `lot-${lot.id}`,
      rawId: lot.id,
      type: 'OFF_STREET',
      label: lot.name,
      latitude: lot.latitude,
      longitude: lot.longitude,
      distanceMeters: lot.distanceMeters,
      availabilityStatus: lot.availabilityStatus,
      currentValue: lot.availableCarSpaces === null || lot.availableCarSpaces === undefined
        ? '未知'
        : `${lot.availableCarSpaces} 位`,
      systemCollectedAt: lot.systemCollectedAt,
      navigationAvailable: lot.navigationAvailable,
      message: lot.message,
    })),
    ...spots.map((spot) => ({
      id: `spot-${spot.id}`,
      rawId: spot.id,
      type: 'ROADSIDE',
      label: spot.label,
      latitude: spot.latitude,
      longitude: spot.longitude,
      distanceMeters: spot.distanceMeters,
      availabilityStatus: spot.availabilityStatus,
      currentValue: spot.availabilityStatus === 'AVAILABLE' ? '目前回報有位' : spot.availabilityStatus,
      systemCollectedAt: spot.systemCollectedAt,
      navigationAvailable: spot.navigationAvailable,
      message: '路邊格位只代表目前觀測，不代表抵達時仍有位。',
    })),
  ]
})

onMounted(async () => {
  if (!hasGoogleMapsKey) return

  try {
    await initializeGoogleMap()
  } catch (error) {
    mapStatus.value = `Google 地圖載入失敗：${error.message}`
  }
})

watch([selectedDestination, queryResult], () => {
  renderMap()
}, { deep: true })

function buildRequestToken() {
  if (window.crypto?.randomUUID) return `web-${window.crypto.randomUUID()}`
  return `web-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function buildDeparture() {
  if (departureType.value === 'SCHEDULED' && scheduledDepartureLocal.value) {
    return {
      type: 'SCHEDULED',
      departureAt: new Date(scheduledDepartureLocal.value).toISOString(),
    }
  }
  return { type: 'NOW' }
}

async function createParkingQuery() {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        requestToken: buildRequestToken(),
        origin: {
          label: origin.value.label,
          latitude: Number(origin.value.latitude),
          longitude: Number(origin.value.longitude),
        },
        destination: {
          label: selectedDestination.value.label,
          latitude: Number(selectedDestination.value.latitude),
          longitude: Number(selectedDestination.value.longitude),
        },
        departure: buildDeparture(),
      }),
    })

    if (!response.ok) {
      throw new Error(await readApiError(response))
    }

    queryResult.value = await response.json()
    selectedItemId.value = ''
    await nextTick()
    renderMap()
  } catch (error) {
    errorMessage.value = `建立查詢失敗：${error.message}`
  } finally {
    loading.value = false
  }
}

async function refreshParkingAvailability() {
  if (!queryResult.value?.query?.queryId) return

  loading.value = true
  errorMessage.value = ''

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${queryResult.value.query.queryId}/refresh`, {
      method: 'POST',
    })

    if (!response.ok) {
      throw new Error(await readApiError(response))
    }

    queryResult.value = await response.json()
    renderMap()
  } catch (error) {
    errorMessage.value = `更新車位失敗：${error.message}`
  } finally {
    loading.value = false
  }
}

async function readApiError(response) {
  try {
    const payload = await response.json()
    return payload?.error?.message || payload?.error?.code || `API 回應 ${response.status}`
  } catch {
    return `API 回應 ${response.status}`
  }
}

async function initializeGoogleMap() {
  await loadGoogleMapsScript()
  const { Map } = await window.google.maps.importLibrary('maps')
  await window.google.maps.importLibrary('places')

  map = new Map(mapElement.value, {
    center: getDestinationLatLng(),
    zoom: 15,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: true,
  })
  infoWindow = new window.google.maps.InfoWindow()
  mapStatus.value = 'Google 地圖已載入'

  setupPlaceAutocomplete()
  renderMap()
}

function loadGoogleMapsScript() {
  if (window.google?.maps?.importLibrary) return Promise.resolve()
  if (googleMapsPromise) return googleMapsPromise

  googleMapsPromise = new Promise((resolve, reject) => {
    const callbackName = `initParkingMap${Date.now()}`
    window[callbackName] = () => {
      delete window[callbackName]
      resolve()
    }

    const script = document.createElement('script')
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(googleMapsApiKey)}&v=weekly&language=zh-TW&region=TW&callback=${callbackName}`
    script.async = true
    script.onerror = () => {
      delete window[callbackName]
      reject(new Error('無法載入 Maps JavaScript API'))
    }
    document.head.appendChild(script)
  })

  return googleMapsPromise
}

function setupPlaceAutocomplete() {
  if (!autocompleteHost.value || !window.google?.maps?.places?.PlaceAutocompleteElement) return

  autocompleteHost.value.replaceChildren()
  const autocomplete = new window.google.maps.places.PlaceAutocompleteElement()
  autocomplete.placeholder = '搜尋目的地、地址或地標'
  autocomplete.addEventListener('gmp-select', async (event) => {
    const prediction = event.placePrediction || event.detail?.placePrediction
    if (!prediction) return

    const place = prediction.toPlace()
    await place.fetchFields({
      fields: ['displayName', 'formattedAddress', 'location'],
    })

    if (!place.location) {
      errorMessage.value = '此地點沒有可用座標，請改選其他候選。'
      return
    }

    selectedDestination.value = {
      label: place.displayName || place.formattedAddress || '已選目的地',
      address: place.formattedAddress || '',
      latitude: place.location.lat(),
      longitude: place.location.lng(),
    }
    errorMessage.value = ''
    map?.panTo(getDestinationLatLng())
    renderMap()
  })
  autocompleteHost.value.appendChild(autocomplete)
}

function renderMap() {
  if (!map || !window.google?.maps) return

  const destinationPosition = getDestinationLatLng()
  map.setCenter(destinationPosition)
  clearMarkers()

  destinationMarker = new window.google.maps.Marker({
    map,
    position: destinationPosition,
    title: resultDestination.value.label,
    label: 'D',
  })

  radiusCircle = new window.google.maps.Circle({
    map,
    center: destinationPosition,
    radius: queryResult.value?.area?.radiusMeters ?? 300,
    strokeColor: '#1d4ed8',
    strokeOpacity: 0.8,
    strokeWeight: 2,
    fillColor: '#60a5fa',
    fillOpacity: 0.12,
  })

  const bounds = new window.google.maps.LatLngBounds()
  bounds.extend(destinationPosition)

  resultItems.value.forEach((item) => {
    const marker = new window.google.maps.Marker({
      map,
      position: { lat: item.latitude, lng: item.longitude },
      title: item.label,
      label: item.type === 'OFF_STREET' ? 'P' : 'R',
    })
    marker.addListener('click', () => selectResultItem(item.id, true))
    parkingMarkers.push(marker)
    markersById.set(item.id, marker)
    bounds.extend(marker.getPosition())
  })

  if (resultItems.value.length > 0) {
    map.fitBounds(bounds, 64)
  } else {
    map.setZoom(15)
  }
}

function clearMarkers() {
  destinationMarker?.setMap(null)
  radiusCircle?.setMap(null)
  parkingMarkers.forEach((marker) => marker.setMap(null))
  parkingMarkers = []
  markersById.clear()
}

function selectResultItem(itemId, fromMarker = false) {
  selectedItemId.value = itemId
  const item = resultItems.value.find((candidate) => candidate.id === itemId)
  const marker = markersById.get(itemId)
  if (!item || !marker || !map) return

  map.panTo(marker.getPosition())
  if (!fromMarker) map.setZoom(Math.max(map.getZoom() ?? 15, 16))
  infoWindow?.setContent(createInfoWindowContent(item))
  infoWindow?.open({ map, anchor: marker })
}

function createInfoWindowContent(item) {
  const wrapper = document.createElement('div')
  wrapper.className = 'marker-info'

  const title = document.createElement('strong')
  title.textContent = item.label
  wrapper.appendChild(title)

  const status = document.createElement('span')
  status.textContent = `${item.type === 'OFF_STREET' ? '路外停車場' : '路邊格位'}：${item.currentValue}`
  wrapper.appendChild(status)

  const time = document.createElement('span')
  time.textContent = `資料時間：${formatDateTime(item.systemCollectedAt)}`
  wrapper.appendChild(time)

  return wrapper
}

function getDestinationLatLng() {
  const destination = resultDestination.value
  return {
    lat: Number(destination.latitude),
    lng: Number(destination.longitude),
  }
}

function useDefaultDestination() {
  selectedDestination.value = { ...defaultDestination }
  queryResult.value = null
  selectedItemId.value = ''
  renderMap()
}

function useCurrentLocation() {
  if (!navigator.geolocation) {
    errorMessage.value = '此瀏覽器不支援定位，請保留測試座標或手動輸入起點。'
    return
  }

  locating.value = true
  errorMessage.value = ''
  navigator.geolocation.getCurrentPosition(
    (position) => {
      origin.value = {
        label: '裝置目前位置',
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      }
      locating.value = false
    },
    (error) => {
      errorMessage.value = `無法取得目前位置：${error.message}`
      locating.value = false
    },
    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 60000,
    },
  )
}

function openExternalRoute(item) {
  const url = `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(`${item.latitude},${item.longitude}`)}&travelmode=driving`
  window.open(url, '_blank', 'noopener,noreferrer')
}

function formatDateTime(value) {
  if (!value) return '無資料'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-TW', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}
</script>

<template>
  <main class="app-shell">
    <section class="top-bar" aria-labelledby="app-title">
      <div>
        <p class="eyebrow">Taiwan Parking Forecast</p>
        <h1 id="app-title">新莊停車抵達預測</h1>
      </div>
      <div class="service-status">
        <span class="status-dot" :class="{ ready: hasGoogleMapsKey }" aria-hidden="true"></span>
        {{ hasGoogleMapsKey ? 'Maps / Places 可載入' : '手動座標模式' }}
      </div>
    </section>

    <section class="planner-panel" aria-label="行程規劃">
      <div class="field-group destination-field">
        <label>目的地</label>
        <div v-if="hasGoogleMapsKey" ref="autocompleteHost" class="autocomplete-host"></div>
        <div v-else class="manual-grid">
          <input v-model="selectedDestination.label" type="text" aria-label="目的地名稱">
          <input v-model.number="selectedDestination.latitude" type="number" step="0.000001" aria-label="目的地緯度">
          <input v-model.number="selectedDestination.longitude" type="number" step="0.000001" aria-label="目的地經度">
        </div>
        <div class="selected-place">
          <strong>{{ selectedDestination.label }}</strong>
          <span>{{ selectedDestination.address || '未選 Google 地點時使用目前座標' }}</span>
          <span>{{ Number(selectedDestination.latitude).toFixed(6) }}, {{ Number(selectedDestination.longitude).toFixed(6) }}</span>
        </div>
      </div>

      <div class="field-group">
        <label>起點</label>
        <div class="origin-row">
          <button class="utility-button" type="button" :disabled="locating" @click="useCurrentLocation">
            {{ locating ? '定位中' : '使用目前位置' }}
          </button>
          <button class="utility-button" type="button" @click="origin = { ...defaultOrigin }">
            測試座標
          </button>
        </div>
        <div class="manual-grid compact">
          <input v-model="origin.label" type="text" aria-label="起點名稱">
          <input v-model.number="origin.latitude" type="number" step="0.000001" aria-label="起點緯度">
          <input v-model.number="origin.longitude" type="number" step="0.000001" aria-label="起點經度">
        </div>
      </div>

      <div class="field-group">
        <label>出發時間</label>
        <div class="segmented-control" role="group" aria-label="出發時間類型">
          <button type="button" :class="{ active: departureType === 'NOW' }" @click="departureType = 'NOW'">
            立即
          </button>
          <button type="button" :class="{ active: departureType === 'SCHEDULED' }" @click="departureType = 'SCHEDULED'">
            指定
          </button>
        </div>
        <input
          v-if="departureType === 'SCHEDULED'"
          v-model="scheduledDepartureLocal"
          type="datetime-local"
          aria-label="指定出發時間"
        >
      </div>

      <div class="action-row">
        <button class="primary-button" type="button" :disabled="loading" @click="createParkingQuery">
          {{ loading ? '處理中' : '規劃停車查詢' }}
        </button>
        <button class="utility-button" type="button" @click="useDefaultDestination">
          回到新莊體育館
        </button>
      </div>

      <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
    </section>

    <section class="workspace" aria-label="地圖與結果">
      <section class="map-stage" aria-label="停車地圖">
        <div class="map-toolbar">
          <div>
            <strong>{{ resultDestination.label }}</strong>
            <span>目的地 300 公尺範圍</span>
          </div>
          <button class="utility-button" type="button" @click="renderMap">回到目的地</button>
        </div>

        <div v-if="hasGoogleMapsKey" ref="mapElement" class="google-map" aria-label="Google 地圖"></div>
        <div v-else class="map-fallback">
          <div class="fallback-circle">300m</div>
          <strong>未載入 Google 地圖</strong>
          <span>設定 `VITE_GOOGLE_MAPS_BROWSER_API_KEY` 後才會載入 Maps JavaScript API。</span>
        </div>

        <div class="map-summary" aria-live="polite">
          <span>{{ mapStatus }}</span>
          <span>{{ etaMessage }}</span>
          <span>路外 {{ parkingLotCount }} 座，路邊目前有位格位 {{ roadsideSpotCount }} 個</span>
        </div>
      </section>

      <aside class="result-panel" aria-label="查詢結果">
        <div class="panel-header">
          <div>
            <p class="eyebrow">Result</p>
            <h2>停車結果</h2>
          </div>
          <button
            class="utility-button"
            type="button"
            :disabled="loading || !queryResult"
            @click="refreshParkingAvailability"
          >
            更新車位
          </button>
        </div>

        <template v-if="queryResult">
          <div class="status-grid">
            <div>
              <span>Query</span>
              <strong>{{ queryResult.query.status }}</strong>
            </div>
            <div>
              <span>ETA</span>
              <strong>{{ queryResult.eta.status }}</strong>
            </div>
            <div>
              <span>預測</span>
              <strong>{{ queryResult.predictions.status }}</strong>
            </div>
          </div>

          <article class="result-card">
            <strong>路邊區域</strong>
            <span>{{ queryResult.roadside.message }}</span>
            <span>目前可用：{{ queryResult.roadside.currentlyAvailableSpaces ?? '未知' }}</span>
            <span>資料時間：{{ formatDateTime(queryResult.roadside.systemCollectedAt) }}</span>
          </article>

          <article class="result-card">
            <strong>路外停車場</strong>
            <span>{{ queryResult.offStreet.message }}</span>
            <span>資料時間：{{ formatDateTime(queryResult.offStreet.systemCollectedAt) }}</span>
          </article>

          <article class="result-card warning">
            <strong>抵達時預測</strong>
            <span>{{ queryResult.predictions.message }}</span>
            <span>目標抵達：{{ formatDateTime(queryResult.predictions.targetArrivalAt) }}</span>
          </article>

          <div class="list-header">
            <strong>查看全部結果</strong>
            <span>{{ resultItems.length }} 筆</span>
          </div>

          <article
            v-for="item in resultItems"
            :key="item.id"
            class="parking-item"
            :class="{ selected: selectedItemId === item.id }"
          >
            <button class="parking-select" type="button" @click="selectResultItem(item.id)">
              <span class="item-type">{{ item.type === 'OFF_STREET' ? '路外' : '路邊' }}</span>
              <strong>{{ item.label }}</strong>
              <span>{{ item.distanceMeters }} m · {{ item.currentValue }}</span>
              <span>資料時間：{{ formatDateTime(item.systemCollectedAt) }}</span>
              <span>{{ item.message }}</span>
            </button>
            <button
              v-if="item.navigationAvailable"
              class="route-link"
              type="button"
              @click="openExternalRoute(item)"
            >
              查看路線
            </button>
          </article>
        </template>

        <template v-else>
          <article class="result-card">
            <strong>尚未送出查詢</strong>
            <span>選定目的地與出發設定後，前端才會呼叫主系統建立查詢。</span>
          </article>
          <article class="result-card">
            <strong>資料邊界</strong>
            <span>前端只提交座標與時間，不提交自算 ETA、車位數或預測機率。</span>
          </article>
        </template>

        <article class="result-card muted">
          <strong>設定</strong>
          <span>API：{{ apiBaseUrl }}</span>
          <span>Google Key：{{ hasGoogleMapsKey ? '已設定' : '未設定' }}</span>
        </article>
      </aside>
    </section>
  </main>
</template>
