<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  appendAcceptanceEvent,
  summarizeParkingQueryPayload,
} from './acceptanceLog.js'
import {
  buildParkingQueryPayload,
  buildRequestToken,
  hasUsableGoogleMapsKey,
  locationErrorMessage,
  roadsideCountLabel,
  shouldAutoRefresh,
} from './planner.js'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const googleMapsApiKey = import.meta.env.VITE_GOOGLE_MAPS_BROWSER_API_KEY || ''
const hasGoogleMapsKey = hasUsableGoogleMapsKey(googleMapsApiKey)
const destinationMode = ref(hasGoogleMapsKey ? 'PLACES' : 'COORDINATES')

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
const POLL_INTERVAL_MS = 5000
const DEFAULT_RADIUS_METERS = 500

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
const refreshMessage = ref('')
const mapStatus = ref(hasGoogleMapsKey ? '正在載入 Google 地圖' : '未設定瀏覽器 Google Maps key，使用手動座標模式')
const mapReady = ref(false)
const selectedItemId = ref('')
const autoRefreshAttemptKey = ref('')
const acceptanceEvents = ref([])

let googleMapsPromise
let map
let destinationMarker
let radiusCircle
let infoWindow
let parkingMarkers = []
let pollTimer
let locationRequestId = 0
const markersById = new Map()

const roadsideSpotCount = computed(() => queryResult.value?.roadside?.availableSpots?.length ?? 0)
const parkingLotCount = computed(() => queryResult.value?.offStreet?.parkingLots?.length ?? 0)
const resultDestination = computed(() => queryResult.value?.destination ?? selectedDestination.value)
const refreshInProgress = computed(() => Boolean(
  queryResult.value?.refresh?.roadside?.refreshInProgress
    || queryResult.value?.refresh?.offStreet?.refreshInProgress,
))
const autoRefreshEligibleSources = computed(() => getAutoRefreshDecision().sources)
const etaMessage = computed(() => {
  const eta = queryResult.value?.eta
  if (!eta) return '送出查詢後由後端 Google Routes 估算'
  if (eta.status === 'READY' && new Date(eta.estimatedArrivalAt).getTime() < Date.now()) {
    return '原預計抵達時間已過，請重新規劃'
  }
  if (eta.status === 'READY') return `Google 預估抵達：${formatDateTime(eta.estimatedArrivalAt)}`
  return 'Google 路線無法估算，請調整起點後重新規劃；目前車位仍可參考'
})
const roadsideMessage = computed(() => {
  const status = queryResult.value?.roadside?.status
  if (status === 'READY') return '已讀取官方路邊目前觀測；格位狀態可能隨時變動。'
  if (status === 'STALE') return '路邊資料已過期，請更新後再參考。'
  if (status === 'NO_CATALOG_RESULTS') return '此範圍尚無已審查的路邊格位資料。'
  return '附近路邊格位目前無法確認是否有位。'
})
const offStreetMessage = computed(() => {
  const status = queryResult.value?.offStreet?.status
  if (status === 'READY') return '已讀取官方停車場目前資料；剩餘位數可能隨時變動。'
  if (status === 'STALE') return '停車場資料已過期，請更新後再參考。'
  if (status === 'NO_CATALOG_RESULTS') return '此範圍尚無已審查的停車場資料。'
  return '附近停車場目前資料不足。'
})
const predictionMessage = computed(() => {
  if (queryResult.value?.eta?.status !== 'READY') {
    return '尚無抵達時間，無法估算抵達時車位機率。'
  }
  const arrivalAt = queryResult.value?.predictions?.targetArrivalAt
  if (arrivalAt && new Date(arrivalAt).getTime() < Date.now()) {
    return '原預計抵達時間已過，請重新規劃。'
  }
  return '尚無合格的抵達時車位機率；以下僅顯示目前資料。'
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
      message: queryResult.value?.offStreet?.status === 'STALE'
        ? '資料已過期，請更新後再參考。'
        : lot.availableCarSpaces === null || lot.availableCarSpaces === undefined
          ? '目前剩餘位數未知。'
          : '目前回報剩餘位數，不代表抵達時仍有位。',
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
  document.addEventListener('visibilitychange', handleVisibilityChange)
  if (!hasGoogleMapsKey) {
    logAcceptanceEvent('MAPS_SKIPPED_NO_KEY', '未設定 browser key，維持手動座標模式。')
    return
  }

  try {
    await initializeGoogleMap()
  } catch (error) {
    mapStatus.value = `Google 地圖載入失敗：${error.message}`
    logAcceptanceEvent('MAPS_LOAD_FAILED', 'Google Maps JavaScript API 載入失敗。')
  }
})

onBeforeUnmount(() => {
  locationRequestId++
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  clearProgressPolling()
})

watch([selectedDestination, queryResult], () => {
  renderMap()
}, { deep: true })

watch(queryResult, () => {
  scheduleProgressPolling()
  maybeAutoRefresh('state-change')
}, { deep: true })

async function createParkingQuery() {
  loading.value = true
  errorMessage.value = ''

  try {
    const payload = buildParkingQueryPayload({
      requestToken: buildRequestToken(window.crypto),
      origin: origin.value,
      destination: selectedDestination.value,
      departureType: departureType.value,
      scheduledDepartureLocal: scheduledDepartureLocal.value,
    })
    logAcceptanceEvent('QUERY_PAYLOAD_BUILT', '已建立查詢 payload 欄位摘要。', summarizeParkingQueryPayload(payload))

    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    })
    logAcceptanceEvent('QUERY_POST', '建立停車查詢 API 已回應。', {
      method: 'POST',
      status: response.status,
      ok: response.ok,
    })

    if (!response.ok) {
      throw await readApiError(response)
    }

    queryResult.value = await response.json()
    selectedItemId.value = ''
    refreshMessage.value = ''
    await nextTick()
    renderMap()
  } catch (error) {
    errorMessage.value = `建立查詢失敗：${error.message}`
  } finally {
    loading.value = false
    await maybeAutoRefresh('created-query')
  }
}

async function refreshParkingAvailability(trigger = 'MANUAL_REFRESH') {
  if (!queryResult.value?.query?.queryId) return

  loading.value = true
  errorMessage.value = ''
  refreshMessage.value = trigger === 'VISIBLE_AUTO_REFRESH'
    ? '頁面可見且資料需更新，正在發起受控自動刷新。'
    : '正在檢查路邊與路外官方來源刷新狀態。'

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${queryResult.value.query.queryId}/refresh?trigger=${encodeURIComponent(trigger)}`, {
      method: 'POST',
    })
    logAcceptanceEvent('REFRESH_POST', '刷新 API 已回應。', {
      method: 'POST',
      trigger,
      status: response.status,
      ok: response.ok,
    })

    if (!response.ok) {
      throw await readApiError(response)
    }

    queryResult.value = await response.json()
    refreshMessage.value = [
      formatSourceRefresh('路邊', queryResult.value.refresh?.roadside),
      formatSourceRefresh('路外', queryResult.value.refresh?.offStreet),
    ].join('；')
    renderMap()
  } catch (error) {
    errorMessage.value = `更新車位失敗：${error.message}`
    refreshMessage.value = '本次更新未完成；不會自動密集重試。'
    if (error.code === 'QUERY_EXPIRED' || error.code === 'QUERY_NOT_FOUND') {
      queryResult.value = null
      clearProgressPolling()
    }
  } finally {
    loading.value = false
  }
}

async function readParkingQuery() {
  if (!queryResult.value?.query?.queryId) return

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${queryResult.value.query.queryId}`)
    logAcceptanceEvent('QUERY_GET', '已讀取查詢狀態。', {
      method: 'GET',
      status: response.status,
      ok: response.ok,
    })
    if (!response.ok) {
      throw await readApiError(response)
    }
    queryResult.value = await response.json()
    refreshMessage.value = '已讀取最新查詢狀態。'
  } catch (error) {
    errorMessage.value = `讀取查詢狀態失敗：${error.message}`
    clearProgressPolling()
    if (error.code === 'QUERY_EXPIRED' || error.code === 'QUERY_NOT_FOUND') {
      queryResult.value = null
    }
  }
}

async function handleVisibilityChange() {
  if (!isPageVisible()) {
    clearProgressPolling()
    refreshMessage.value = '頁面已隱藏，暫停自動刷新與進度輪詢。'
    return
  }
  if (!queryResult.value?.query?.queryId) return

  refreshMessage.value = '頁面重新可見，先讀取最新查詢狀態。'
  await readParkingQuery()
  await maybeAutoRefresh('visible-again')
  scheduleProgressPolling()
}

function scheduleProgressPolling() {
  clearProgressPolling()
  if (!queryResult.value?.query?.hasPendingWork || !isPageVisible()) return

  pollTimer = window.setTimeout(async () => {
    await readParkingQuery()
    scheduleProgressPolling()
  }, POLL_INTERVAL_MS)
}

function clearProgressPolling() {
  if (!pollTimer) return
  window.clearTimeout(pollTimer)
  pollTimer = undefined
}

async function maybeAutoRefresh(reason) {
  const decision = getAutoRefreshDecision()
  if (!decision.shouldRefresh) return

  autoRefreshAttemptKey.value = decision.attemptKey
  refreshMessage.value = `偵測到 ${decision.sources.join('、')} 需要更新，執行一次受控自動刷新。`
  await refreshParkingAvailability('VISIBLE_AUTO_REFRESH')
}

function getAutoRefreshDecision() {
  return shouldAutoRefresh({
    queryResult: queryResult.value,
    loading: loading.value,
    visible: isPageVisible(),
    refreshInProgress: refreshInProgress.value,
    lastAttemptKey: autoRefreshAttemptKey.value,
  })
}

function isPageVisible() {
  return typeof document === 'undefined' || document.visibilityState === 'visible'
}

async function readApiError(response) {
  try {
    const payload = await response.json()
    const code = payload?.error?.code
    const messages = {
      QUERY_EXPIRED: '查詢已過期，請重新規劃行程。',
      QUERY_NOT_FOUND: '查詢已失效，請重新規劃行程。',
      UNSUPPORTED_AREA: '目的地超出目前支援範圍，請選擇其他目的地。',
      INVALID_DEPARTURE_TIME: '出發時間無效，請重新選擇。',
      SERVICE_TEMPORARILY_UNAVAILABLE: '查詢暫時無法使用，請稍後重試。',
    }
    const error = new Error(messages[code] || payload?.error?.message || `API 回應 ${response.status}`)
    error.code = code
    return error
  } catch {
    return new Error(`API 回應 ${response.status}`)
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
  mapReady.value = true
  logAcceptanceEvent('MAPS_LOADED', 'Google Maps JavaScript API 與 Places library 已載入。')

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
      googleMapsPromise = undefined
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
    logAcceptanceEvent('PLACE_SELECTED', '已選取 Google Places 目的地。', {
      label: selectedDestination.value.label,
      latitude: Number(selectedDestination.value.latitude.toFixed(6)),
      longitude: Number(selectedDestination.value.longitude.toFixed(6)),
    })
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
    radius: queryResult.value?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS,
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
  const requestId = ++locationRequestId
  navigator.geolocation.getCurrentPosition(
    (position) => {
      if (requestId !== locationRequestId) return
      origin.value = {
        label: '裝置目前位置',
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      }
      locating.value = false
    },
    (error) => {
      if (requestId !== locationRequestId) return
      errorMessage.value = locationErrorMessage(error)
      locating.value = false
    },
    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 60000,
    },
  )
}

function cancelLocationRequest() {
  locationRequestId++
  locating.value = false
  errorMessage.value = ''
}

function useTestOrigin() {
  cancelLocationRequest()
  origin.value = { ...defaultOrigin }
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

function formatSourceRefresh(label, refresh) {
  const action = refresh?.action || ''
  if (action.startsWith('REFRESHED')) return `${label}：已更新`
  if (action === 'COOLDOWN') return `${label}：冷卻中，可於 ${formatDateTime(refresh.nextAllowedAt)} 後再試`
  if (action === 'JOINED_RUNNING_REFRESH') return `${label}：正在共用既有更新`
  if (action.startsWith('FAILED')) return `${label}：更新失敗，保留上次資料`
  return `${label}：目前無法確認更新結果`
}

function logAcceptanceEvent(type, message, details = {}) {
  acceptanceEvents.value = appendAcceptanceEvent(acceptanceEvents.value, {
    type,
    message,
    details,
  })
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
        <span class="status-dot" :class="{ ready: mapReady }" aria-hidden="true"></span>
        {{ mapReady ? '地圖與地點搜尋已載入' : '地圖尚未載入' }}
      </div>
    </section>

    <section class="planner-panel" aria-label="行程規劃">
      <div class="field-group destination-field">
        <label>目的地</label>
        <div v-if="hasGoogleMapsKey" class="segmented-control destination-mode" role="group" aria-label="目的地輸入方式">
          <button type="button" :class="{ active: destinationMode === 'PLACES' }" @click="destinationMode = 'PLACES'">搜尋地點</button>
          <button type="button" :class="{ active: destinationMode === 'COORDINATES' }" @click="destinationMode = 'COORDINATES'">手動座標</button>
        </div>
        <div v-if="hasGoogleMapsKey" v-show="destinationMode === 'PLACES'" ref="autocompleteHost" class="autocomplete-host"></div>
        <div v-if="destinationMode === 'COORDINATES'" class="manual-grid">
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
          <button class="utility-button" type="button" @click="useTestOrigin">
            測試座標
          </button>
        </div>
        <div class="manual-grid compact">
          <input v-model="origin.label" type="text" aria-label="起點名稱" @input="cancelLocationRequest">
          <input v-model.number="origin.latitude" type="number" step="0.000001" aria-label="起點緯度" @input="cancelLocationRequest">
          <input v-model.number="origin.longitude" type="number" step="0.000001" aria-label="起點經度" @input="cancelLocationRequest">
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
      <p v-if="refreshMessage" class="refresh-message">{{ refreshMessage }}</p>
    </section>

    <section class="workspace" aria-label="地圖與結果">
      <section class="map-stage" aria-label="停車地圖">
        <div class="map-toolbar">
          <div>
            <strong>{{ resultDestination.label }}</strong>
            <span>目的地 {{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }} 公尺範圍</span>
          </div>
          <button class="utility-button" type="button" @click="renderMap">回到目的地</button>
        </div>

        <div v-if="hasGoogleMapsKey" ref="mapElement" class="google-map" aria-label="Google 地圖"></div>
        <div v-if="!mapReady" class="map-fallback" :class="{ overlay: hasGoogleMapsKey }">
          <div class="fallback-circle">{{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }}m</div>
          <strong>{{ mapStatus }}</strong>
          <span>下方結果列表仍可查看目前車位與資料時間。</span>
        </div>

        <div class="map-summary" aria-live="polite">
          <span>{{ mapStatus }}</span>
          <span>{{ etaMessage }}</span>
          <span>路外 {{ parkingLotCount }} 座，{{ roadsideCountLabel(queryResult?.roadside?.status, roadsideSpotCount) }}</span>
          <span v-if="queryResult && queryResult.area?.coverageStatus !== 'COMPLETE'">涵蓋範圍：{{ queryResult.area?.coverageStatus === 'NO_CATALOG_RESULTS' ? '尚無已審查目錄資料' : '部分區域，資料仍需驗證' }}</span>
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
            @click="refreshParkingAvailability()"
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
              <strong>{{ queryResult.eta.status === 'READY' ? '已估算' : '無法估算' }}</strong>
            </div>
            <div>
              <span>預測</span>
              <strong>{{ queryResult.predictions.status === 'INSUFFICIENT_DATA' ? '資料不足' : queryResult.predictions.status }}</strong>
            </div>
          </div>

          <article class="result-card">
            <strong>路邊區域</strong>
            <span>{{ roadsideMessage }}</span>
            <span>目前可用：{{ queryResult.roadside.currentlyAvailableSpaces ?? '未知' }}</span>
            <span>資料時間：{{ formatDateTime(queryResult.roadside.systemCollectedAt) }}</span>
          </article>

          <article class="result-card">
            <strong>路外停車場</strong>
            <span>{{ offStreetMessage }}</span>
            <span>資料時間：{{ formatDateTime(queryResult.offStreet.systemCollectedAt) }}</span>
          </article>

          <article class="result-card warning">
            <strong>抵達時預測</strong>
            <span>{{ predictionMessage }}</span>
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
          <span>自動刷新：頁面可見且來源狀態需更新時，每個查詢狀態組合只嘗試一次。</span>
        </article>

        <article class="result-card acceptance-log">
          <strong>人工驗收事件</strong>
          <span>只記錄狀態碼、trigger 與 payload 欄位摘要；不顯示 key 或完整 API 回應。</span>
          <ol v-if="acceptanceEvents.length">
            <li v-for="(event, index) in acceptanceEvents" :key="`${event.occurredAt}-${event.type}-${index}`">
              <span>{{ formatDateTime(event.occurredAt) }} · {{ event.type }}</span>
              <strong>{{ event.message }}</strong>
              <code>{{ JSON.stringify(event.details) }}</code>
            </li>
          </ol>
          <span v-else>尚無事件。送出查詢或載入 Places 後會在此出現。</span>
        </article>
      </aside>
    </section>
  </main>
</template>
