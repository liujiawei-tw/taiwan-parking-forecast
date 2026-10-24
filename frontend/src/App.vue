<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { readApiError, requestFailureMessage } from './apiErrors.js'
import {
  appendAcceptanceEvent,
  summarizeParkingQueryPayload,
} from './acceptanceLog.js'
import {
  buildParkingQueryPayload,
  buildRequestToken,
  DEFAULT_ORIGIN,
  hasUsableGoogleMapsKey,
  locationErrorMessage,
  roadsideCountLabel,
  shouldAutoRefresh,
} from './planner.js'
import {
  createOriginUpdateCoordinator,
  createPlaceSelectionHandler,
  requestCurrentLocation,
} from './placesAutocomplete.js'
import {
  clusterRoadsideMarkers,
  countMapMarkerCategories,
  describeMapMarker,
  formatMapCapacity,
  filterMapMarkers,
  refreshSelectedMapPopup,
} from './mapMarkers.js'
import {
  applyMapDisplayFreshness,
  createLoadingRequestOwner,
  createMapDataRequestCache,
  createQueryRequestGuard,
  getRoadsideDisplaySummary,
  hasPlanningInputsChanged,
  isMapSourceDisplayStale,
  MAP_DISPLAY_FRESHNESS_MS,
  shouldApplyQueryResult,
  snapshotPlanningInputs,
  snapshotDestination,
} from './mapDataLifecycle.js'
import {
  loadMapViewportPages,
  mapViewportRequestKey,
  normalizeMapViewportBounds,
} from './mapViewport.js'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
const apiBaseUrlLabel = apiBaseUrl || '同源 /api'
const googleMapsApiKey = import.meta.env.VITE_GOOGLE_MAPS_BROWSER_API_KEY || ''
const hasGoogleMapsKey = hasUsableGoogleMapsKey(googleMapsApiKey)

const defaultDestination = {
  label: '新莊體育館',
  address: '新北市新莊區中華路一段75號',
  latitude: 25.036,
  longitude: 121.4505,
}

const POLL_INTERVAL_MS = 5000
const DEFAULT_RADIUS_METERS = 500
const MAP_DATA_RETRY_DELAY_MS = 15000
const MAP_VIEWPORT_IDLE_DELAY_MS = 250

const mapElement = ref(null)
const originAutocompleteHost = ref(null)
const destinationAutocompleteHost = ref(null)
const selectedDestination = ref({ ...defaultDestination })
const origin = ref({ ...DEFAULT_ORIGIN })
const departureType = ref('NOW')
const scheduledDepartureLocal = ref('')
const queryResult = ref(null)
const loading = ref(false)
const locating = ref(false)
const errorMessage = ref('')
const refreshMessage = ref('')
const googleMapsLoadError = ref('')
const originPlaceError = ref('')
const destinationPlaceError = ref('')
const mapStatus = ref(hasGoogleMapsKey ? '正在載入 Google 地圖' : '未設定瀏覽器 Google Maps key')
const mapReady = ref(false)
const selectedItemId = ref('')
const autoRefreshAttemptKey = ref('')
const acceptanceEvents = ref([])
const mapData = ref(null)
const mapDataLoading = ref(false)
const mapDataError = ref('')
const mapViewportRevision = ref(0)
const mapFreshnessNow = ref(Date.now())
const activeMapPanel = ref('')
const showResultPanel = ref(false)
const mapFilters = ref({
  layers: { roadside: true, offStreet: true },
  statuses: { AVAILABLE: true, OCCUPIED: true, FULL: true, UNKNOWN: true, STALE: true },
})

let googleMapsPromise
let mapDataRequestId = 0
let mapDataRetryTimer
let mapDataRetryAttemptKey = ''
let mapFreshnessTimer
let mapViewportLoadTimer
let mapIdleListener
const queryRequestGuard = createQueryRequestGuard()
const loadingRequestOwner = createLoadingRequestOwner()
const mapDataRequestCache = createMapDataRequestCache()
const originUpdateCoordinator = createOriginUpdateCoordinator()
let map
let destinationMarker
let radiusCircle
let infoWindow
let parkingMarkers = []
let clusterMarkers = []
let pollTimer
let destinationSelectionVersion = 0
const markersById = new Map()

const originPlaceSelectionHandler = createPlaceSelectionHandler({
  onSelectionStart() {
    const updateId = originUpdateCoordinator.begin()
    locating.value = false
    originPlaceError.value = ''
    errorMessage.value = ''
    return updateId
  },
  isSelectionCurrent: (updateId) => originUpdateCoordinator.isCurrent(updateId),
  onSelected(place) {
    origin.value = place
    logAcceptanceEvent('ORIGIN_PLACE_SELECTED', '已選取 Google Places 出發地。')
  },
  onError: (message) => { originPlaceError.value = message },
})

const destinationPlaceSelectionHandler = createPlaceSelectionHandler({
  onSelectionStart() {
    destinationSelectionVersion++
    destinationPlaceError.value = ''
    return destinationSelectionVersion
  },
  isSelectionCurrent: (version) => version === destinationSelectionVersion,
  onSelected(place) {
    selectedDestination.value = place
    logAcceptanceEvent('PLACE_SELECTED', '已選取 Google Places 目的地。')
  },
  onError: (message) => { destinationPlaceError.value = message },
})

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
const roadsideDisplaySummary = computed(() => {
  const roadside = queryResult.value?.roadside
  const mapSource = mapData.value?.queryId === queryId.value ? mapData.value.roadside : null
  const summary = getRoadsideDisplaySummary(roadside, mapSource, mapFreshnessNow.value)
  return {
    ...summary,
    message: summary.freshnessMessage || roadsideMessage.value,
  }
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
  if (mapData.value && queryResult.value?.query?.queryId
      && mapData.value.queryId === queryResult.value.query.queryId) {
    const freshItems = applyMapDisplayFreshness(
      mapData.value.items,
      mapFreshnessNow.value,
      {
        roadside: mapData.value.roadside?.systemCollectedAt,
        offStreet: mapData.value.offStreet?.systemCollectedAt,
      },
      MAP_DISPLAY_FRESHNESS_MS,
    )
    return freshItems.map((item) => ({
      ...item,
      rawId: item.id.replace(/^(spot-|lot-)/, ''),
      currentValue: describeMapMarker(item).value,
    }))
  }

  const lots = queryResult.value?.offStreet?.parkingLots ?? []
  const spots = queryResult.value?.roadside?.availableSpots ?? []
  const fallbackItems = [
    ...lots.map((lot) => ({
      id: `lot-${lot.id}`,
      rawId: lot.id,
      type: 'OFF_STREET',
      label: lot.name,
      latitude: lot.latitude,
      longitude: lot.longitude,
      distanceMeters: lot.distanceMeters,
      availabilityStatus: lot.availabilityStatus ?? 'UNKNOWN',
      availableCarSpaces: lot.availableCarSpaces ?? null,
      totalCarSpaces: lot.totalCarSpaces ?? null,
      carEligibleStatus: lot.navigationAvailable ? 'CONFIRMED' : 'UNKNOWN',
      source: queryResult.value?.offStreet?.source,
      sourceObservedAt: null,
      address: lot.address,
      parkingSpaceType: lot.parkingSpaceType,
      feeDescription: lot.feeDescription,
      chargingHours: lot.chargingHours,
      notes: lot.notes,
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
      availabilityStatus: spot.availabilityStatus ?? 'UNKNOWN',
      availableSpot: spot.availabilityStatus === 'AVAILABLE' ? true : spot.availabilityStatus === 'OCCUPIED' ? false : null,
      availableCarSpaces: spot.availableCarSpaces ?? null,
      totalCarSpaces: spot.totalCarSpaces ?? null,
      carEligibleStatus: spot.navigationAvailable ? 'CONFIRMED' : 'UNKNOWN',
      source: queryResult.value?.roadside?.source,
      sourceObservedAt: null,
      parkingSpaceType: spot.parkingSpaceType,
      feeStatus: spot.feeStatus,
      feeDescription: spot.feeDescription,
      chargingDays: spot.chargingDays,
      chargingHours: spot.chargingHours,
      notes: spot.notes,
      systemCollectedAt: spot.systemCollectedAt,
      navigationAvailable: spot.navigationAvailable,
      message: '路邊格位只代表目前觀測，不代表抵達時仍有位。',
    })),
  ]
  return fallbackItems.map((item) => ({ ...item, currentValue: describeMapMarker(item).value }))
})
const visibleMapItems = computed(() => filterMapMarkers(resultItems.value, mapFilters.value))
const mapMarkerCounts = computed(() => countMapMarkerCategories(resultItems.value))
const selectedMapItem = computed(() => resultItems.value.find((item) => item.id === selectedItemId.value) ?? null)
const mapViewportSupportMessage = computed(() => {
  void mapViewportRevision.value
  const supportedArea = mapData.value?.supportedArea
  const bounds = map?.getBounds?.()
  const southWest = bounds?.getSouthWest?.()
  const northEast = bounds?.getNorthEast?.()
  if (!supportedArea || !southWest || !northEast) return ''

  const current = normalizeMapViewportBounds({
    south: southWest.lat(),
    west: southWest.lng(),
    north: northEast.lat(),
    east: northEast.lng(),
  })
  if (!current) return ''

  const areaName = mapData.value?.serviceArea || '目前試點'
  const intersects = current.south < supportedArea.north
    && current.north > supportedArea.south
    && current.west < supportedArea.east
    && current.east > supportedArea.west
  if (!intersects) {
    return `目前地圖位於${areaName}支援範圍外，尚未提供此區域資料；請移回試點範圍查看。`
  }

  const fullySupported = current.south >= supportedArea.south
    && current.west >= supportedArea.west
    && current.north <= supportedArea.north
    && current.east <= supportedArea.east
  if (!fullySupported) {
    return `目前地圖部分超出${areaName}支援範圍；只顯示範圍內資料，範圍外不代表沒有車位。`
  }
  return `目前只顯示${areaName}支援範圍內的已審查資料；未收錄處不代表沒有車位。`
})
const queryId = computed(() => queryResult.value?.query?.queryId ?? '')
const currentPlanningInputs = computed(() => snapshotPlanningInputs({
  origin: origin.value,
  destination: selectedDestination.value,
  departureType: departureType.value,
  scheduledDepartureLocal: scheduledDepartureLocal.value,
}))

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  if (!hasGoogleMapsKey) {
    mapStatus.value = '未設定 Google Maps browser key，地點搜尋不可用'
    logAcceptanceEvent('MAPS_SKIPPED_NO_KEY', '未設定 browser key，地點搜尋不可用。')
    return
  }

  try {
    await initializeGoogleMap()
  } catch (error) {
    const message = 'Google Maps／Places 載入失敗，請確認 browser key、網域限制與 Places API 設定後重新整理。'
    googleMapsLoadError.value = message
    mapStatus.value = message
    logAcceptanceEvent('MAPS_LOAD_FAILED', 'Google Maps JavaScript API 載入失敗。')
  }
})

onBeforeUnmount(() => {
  originUpdateCoordinator.invalidate()
  destinationSelectionVersion++
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  clearProgressPolling()
  clearMapFreshnessTimer()
  clearMapDataRetryTimer(true)
  if (mapViewportLoadTimer) window.clearTimeout(mapViewportLoadTimer)
  mapIdleListener?.remove?.()
})

watch([resultDestination, visibleMapItems], () => {
  renderMap()
}, { deep: true })

watch(currentPlanningInputs, (next, previous) => {
  if (hasPlanningInputsChanged(next, previous)) {
    queryRequestGuard.invalidate()
    cancelLoading()
    queryResult.value = null
    clearMapDataState()
    selectedItemId.value = ''
    showResultPanel.value = false
    refreshMessage.value = ''
    clearProgressPolling()
  }
}, { flush: 'sync' })

watch(() => snapshotDestination(selectedDestination.value), (next) => {
  if (map && Number.isFinite(next.latitude) && Number.isFinite(next.longitude)) {
    map.panTo({ lat: next.latitude, lng: next.longitude })
  }
  renderMap()
}, { flush: 'sync' })

watch(queryResult, () => {
  mapFreshnessNow.value = Date.now()
  scheduleMapFreshnessTimer()
  scheduleProgressPolling()
  maybeAutoRefresh('state-change')
}, { deep: true })

async function createParkingQuery() {
  const submittedDestination = { ...selectedDestination.value }
  const submittedOrigin = { ...origin.value }
  const submittedPlanningInputs = currentPlanningInputs.value
  const requestContext = queryRequestGuard.start(submittedPlanningInputs)
  const loadingRequestId = startLoading()
  errorMessage.value = ''

  try {
    const payload = buildParkingQueryPayload({
      requestToken: buildRequestToken(window.crypto),
      origin: submittedOrigin,
      destination: submittedDestination,
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
    const apiError = response.ok ? null : await readApiError(response)
    logAcceptanceEvent('QUERY_POST', '建立停車查詢 API 已回應。', {
      operation: 'create',
      method: 'POST',
      status: response.status,
      ok: response.ok,
      errorCode: apiError?.code,
    })

    if (apiError) throw apiError

    const result = await response.json()
    if (!queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value)) return

    queryResult.value = result
    selectedItemId.value = ''
    clearMapDataState()
    refreshMessage.value = ''
    showResultPanel.value = true
    await nextTick()
    await loadMapData()
    renderMap()
  } catch (error) {
    if (queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value)) {
      if (error.code && !Number.isInteger(error.status)) {
        logAcceptanceEvent('QUERY_VALIDATION_FAILED', '查詢條件未送出。', {
          operation: 'create',
          errorCode: error.code,
        })
      }
      errorMessage.value = `建立查詢失敗：${requestFailureMessage(error)}`
    }
  } finally {
    if (finishLoading(loadingRequestId, requestContext)) {
      await maybeAutoRefresh('created-query')
    }
  }
}

async function refreshParkingAvailability(trigger = 'MANUAL_REFRESH') {
  const requestQueryId = queryId.value
  if (!requestQueryId || loading.value) return
  const requestContext = queryRequestGuard.capture(currentPlanningInputs.value, requestQueryId)
  const loadingRequestId = startLoading()

  errorMessage.value = ''
  refreshMessage.value = trigger === 'VISIBLE_AUTO_REFRESH'
    ? '頁面可見且資料需更新，正在發起受控自動刷新。'
    : '正在檢查路邊與路外官方來源刷新狀態。'

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${requestQueryId}/refresh?trigger=${encodeURIComponent(trigger)}`, {
      method: 'POST',
    })
    const apiError = response.ok ? null : await readApiError(response)
    logAcceptanceEvent('REFRESH_POST', '刷新 API 已回應。', {
      operation: 'refresh',
      method: 'POST',
      trigger,
      status: response.status,
      ok: response.ok,
      errorCode: apiError?.code,
    })

    if (apiError) throw apiError

    const result = await response.json()
    if (!queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) return
    if (!shouldApplyQueryResult(queryResult.value, result)) return

    queryResult.value = result
    await loadMapData(true)
    if (!queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) return
    refreshMessage.value = [
      formatSourceRefresh('路邊', queryResult.value.refresh?.roadside),
      formatSourceRefresh('路外', queryResult.value.refresh?.offStreet),
    ].join('；')
    renderMap()
  } catch (error) {
    if (queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) {
      errorMessage.value = `更新車位失敗：${requestFailureMessage(error)}`
      refreshMessage.value = '本次更新未完成；不會自動密集重試。'
      if (error.code === 'QUERY_EXPIRED' || error.code === 'QUERY_NOT_FOUND') {
        queryResult.value = null
        clearMapDataState()
        clearProgressPolling()
      }
    }
  } finally {
    finishLoading(loadingRequestId, requestContext)
  }
}

function startLoading() {
  loading.value = true
  return loadingRequestOwner.acquire()
}

function finishLoading(requestId, requestContext) {
  if (!queryRequestGuard.isGenerationCurrent(requestContext, currentPlanningInputs.value)) return false
  if (!loadingRequestOwner.release(requestId)) return false
  loading.value = false
  return true
}

function cancelLoading() {
  loadingRequestOwner.invalidate()
  loading.value = false
}

async function readParkingQuery(forceMapData = false) {
  const requestQueryId = queryId.value
  if (!requestQueryId || loading.value) return
  const requestContext = queryRequestGuard.capture(currentPlanningInputs.value, requestQueryId)

  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${requestQueryId}`)
    const apiError = response.ok ? null : await readApiError(response)
    logAcceptanceEvent('QUERY_GET', '已讀取查詢狀態。', {
      operation: 'read',
      method: 'GET',
      status: response.status,
      ok: response.ok,
      errorCode: apiError?.code,
    })
    if (apiError) throw apiError
    const result = await response.json()
    if (!queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) return
    if (!shouldApplyQueryResult(queryResult.value, result)) return

    queryResult.value = result
    await loadMapData(forceMapData)
    if (!queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) return
    if (errorMessage.value.startsWith('讀取查詢狀態失敗：')) {
      errorMessage.value = ''
    }
    refreshMessage.value = '已讀取最新查詢狀態。'
  } catch (error) {
    if (queryRequestGuard.isCurrent(requestContext, currentPlanningInputs.value, queryId.value)) {
      errorMessage.value = `讀取查詢狀態失敗：${requestFailureMessage(error)}`
      clearProgressPolling()
      if (error.code === 'QUERY_EXPIRED' || error.code === 'QUERY_NOT_FOUND') {
        queryResult.value = null
        clearMapDataState()
      }
    }
  }
}

function getMapDataRequest(id) {
  const sourceVersion = [
    id,
    queryResult.value?.roadside?.snapshotId ?? '',
    queryResult.value?.roadside?.systemCollectedAt ?? '',
    queryResult.value?.offStreet?.snapshotId ?? '',
    queryResult.value?.offStreet?.systemCollectedAt ?? '',
  ].join(':')
  const currentBounds = map?.getBounds?.()
  const southWest = currentBounds?.getSouthWest?.()
  const northEast = currentBounds?.getNorthEast?.()
  const viewport = normalizeMapViewportBounds(southWest && northEast ? {
    south: southWest.lat(),
    west: southWest.lng(),
    north: northEast.lat(),
    east: northEast.lng(),
  } : null)

  if (viewport) {
    return {
      key: mapViewportRequestKey(id, viewport, sourceVersion),
      viewport,
      useViewport: true,
    }
  }
  return {
    key: `radius:${sourceVersion}:${queryResult.value?.query?.updatedAt ?? ''}`,
    viewport: null,
    useViewport: false,
  }
}

async function loadMapData(force = false, retryAfterFailure = true, allowAutoRefresh = true) {
  const id = queryId.value
  if (!id) return
  const request = getMapDataRequest(id)
  const responseKey = request.key
  if (!mapDataRequestCache.shouldLoad(responseKey, force)) return

  if (force && retryAfterFailure) clearMapDataRetryTimer(true)
  const requestId = ++mapDataRequestId
  mapDataRequestCache.begin(responseKey)
  mapDataLoading.value = true
  mapDataError.value = ''

  try {
    let payload
    if (request.useViewport) {
      payload = await loadMapViewportPages(fetch, {
        apiBaseUrl,
        queryId: id,
        bounds: request.viewport,
      })
    } else {
      const response = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${id}/map-data`)
      if (!response.ok) throw await readApiError(response)
      payload = await response.json()
    }
    if (requestId !== mapDataRequestId || queryId.value !== id) return
    mapDataRequestCache.complete(responseKey)
    clearMapDataRetryTimer(true)
    mapFreshnessNow.value = Date.now()
    mapData.value = payload
    scheduleMapFreshnessTimer()
    renderMap()
    if (allowAutoRefresh) await maybeAutoRefresh('map-data-loaded')
  } catch (error) {
    if (requestId !== mapDataRequestId || queryId.value !== id) return
    mapDataRequestCache.fail(responseKey, Date.now() + MAP_DATA_RETRY_DELAY_MS)
    mapDataError.value = `完整地圖標記載入失敗：${error.message}`
    if (retryAfterFailure) scheduleMapDataRetry(responseKey, id)
  } finally {
    if (requestId === mapDataRequestId) mapDataLoading.value = false
  }
}

async function handleVisibilityChange() {
  if (!isPageVisible()) {
    clearProgressPolling()
    clearMapFreshnessTimer()
    clearMapDataRetryTimer(true)
    refreshMessage.value = '頁面已隱藏，暫停自動刷新與進度輪詢。'
    return
  }
  if (!queryResult.value?.query?.queryId) return

  mapFreshnessNow.value = Date.now()
  refreshMessage.value = '頁面重新可見，先讀取最新查詢狀態。'
  await readParkingQuery(true)
  await maybeAutoRefresh('visible-again')
  scheduleProgressPolling()
  scheduleMapFreshnessTimer()
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

function clearMapFreshnessTimer() {
  if (!mapFreshnessTimer) return
  window.clearTimeout(mapFreshnessTimer)
  mapFreshnessTimer = undefined
}

function scheduleMapFreshnessTimer() {
  clearMapFreshnessTimer()
  if (!mapData.value && !queryResult.value) return
  if (!isPageVisible()) return

  const timestamps = (mapData.value?.items ?? [])
    .filter((item) => ['AVAILABLE', 'OCCUPIED', 'FULL'].includes(item.availabilityStatus))
    .map((item) => item.systemCollectedAt
      ?? mapData.value?.[item.type === 'ROADSIDE' ? 'roadside' : 'offStreet']?.systemCollectedAt)
  for (const sourceName of ['roadside', 'offStreet']) {
    const source = mapData.value?.queryId === queryId.value
      ? mapData.value[sourceName]
      : null
    if (['READY', 'PARTIAL'].includes(source?.status) && source.systemCollectedAt) {
      timestamps.push(source.systemCollectedAt)
    }
    const querySource = queryResult.value?.[sourceName]
    if (['READY', 'PARTIAL'].includes(querySource?.status) && querySource.systemCollectedAt) {
      timestamps.push(querySource.systemCollectedAt)
    }
  }

  const now = Date.now()
  const nextExpiry = timestamps
    .map((timestamp) => Date.parse(timestamp ?? '') + MAP_DISPLAY_FRESHNESS_MS + 1)
    .filter((deadline) => Number.isFinite(deadline) && deadline > now)
    .sort((left, right) => left - right)[0]
  if (!nextExpiry) return

  mapFreshnessTimer = window.setTimeout(() => {
    mapFreshnessTimer = undefined
    mapFreshnessNow.value = Date.now()
    scheduleMapFreshnessTimer()
    void maybeAutoRefresh('map-snapshot-expired')
  }, Math.max(0, nextExpiry - now))
}

function clearMapDataRetryTimer(resetAttempt = false) {
  if (mapDataRetryTimer) {
    window.clearTimeout(mapDataRetryTimer)
    mapDataRetryTimer = undefined
  }
  if (resetAttempt) mapDataRetryAttemptKey = ''
}

function scheduleMapDataRetry(responseKey, id) {
  if (!isPageVisible() || mapDataRetryAttemptKey === responseKey) return
  mapDataRetryAttemptKey = responseKey
  mapDataRetryTimer = window.setTimeout(() => {
    mapDataRetryTimer = undefined
    if (!isPageVisible() || queryId.value !== id) return
    if (getMapDataRequest(id).key !== responseKey) return
    void loadMapData(true, false, false)
  }, MAP_DATA_RETRY_DELAY_MS)
}

function clearMapDataState() {
  mapDataRequestId++
  mapDataRequestCache.invalidate()
  if (mapViewportLoadTimer) {
    window.clearTimeout(mapViewportLoadTimer)
    mapViewportLoadTimer = undefined
  }
  mapData.value = null
  mapDataLoading.value = false
  mapDataError.value = ''
  mapFreshnessNow.value = Date.now()
  clearMapFreshnessTimer()
  clearMapDataRetryTimer(true)
}

async function maybeAutoRefresh(reason) {
  const decision = getAutoRefreshDecision()
  if (!decision.shouldRefresh) return

  autoRefreshAttemptKey.value = decision.attemptKey
  refreshMessage.value = `偵測到 ${decision.sources.join('、')} 需要更新，執行一次受控自動刷新。`
  await refreshParkingAvailability('VISIBLE_AUTO_REFRESH')
}

function getAutoRefreshDecision() {
  const currentQuery = queryResult.value
  if (!currentQuery) {
    return shouldAutoRefresh({
      queryResult: currentQuery,
      loading: loading.value,
      visible: isPageVisible(),
      refreshInProgress: refreshInProgress.value,
      lastAttemptKey: autoRefreshAttemptKey.value,
    })
  }

  const refreshableQuery = { ...currentQuery }
  for (const sourceName of ['roadside', 'offStreet']) {
    const source = mapData.value?.[sourceName] ?? currentQuery[sourceName]
    if (!isMapSourceDisplayStale(source, mapFreshnessNow.value, MAP_DISPLAY_FRESHNESS_MS)) continue
    refreshableQuery[sourceName] = {
      ...currentQuery[sourceName],
      status: 'STALE',
      snapshotId: source?.snapshotId ?? currentQuery[sourceName]?.snapshotId,
      systemCollectedAt: source?.systemCollectedAt ?? currentQuery[sourceName]?.systemCollectedAt,
    }
  }

  return shouldAutoRefresh({
    queryResult: refreshableQuery,
    loading: loading.value,
    visible: isPageVisible(),
    refreshInProgress: refreshInProgress.value,
    lastAttemptKey: autoRefreshAttemptKey.value,
  })
}

function isPageVisible() {
  return typeof document === 'undefined' || document.visibilityState === 'visible'
}

async function initializeGoogleMap() {
  await loadGoogleMapsScript()
  const { Map } = await window.google.maps.importLibrary('maps')
  const { PlaceAutocompleteElement } = await window.google.maps.importLibrary('places')
  if (typeof PlaceAutocompleteElement !== 'function') {
    throw new Error('Google Places Autocomplete 元件不可用')
  }

  map = new Map(mapElement.value, {
    center: getDestinationLatLng(),
    zoom: 15,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: true,
  })
  mapIdleListener = map.addListener('idle', () => {
    mapViewportRevision.value++
    renderMap()
    scheduleMapViewportLoad()
  })
  infoWindow = new window.google.maps.InfoWindow()
  setupPlaceAutocompletes(PlaceAutocompleteElement)
  mapStatus.value = 'Google 地圖已載入'
  mapReady.value = true
  logAcceptanceEvent('MAPS_LOADED', 'Google Maps JavaScript API 與 Places library 已載入。')

  renderMap()
}

function scheduleMapViewportLoad() {
  if (!map || !queryId.value) return
  if (mapViewportLoadTimer) window.clearTimeout(mapViewportLoadTimer)
  mapViewportLoadTimer = window.setTimeout(() => {
    mapViewportLoadTimer = undefined
    if (!isPageVisible() || !queryId.value) return
    void loadMapData(false, true, false)
  }, MAP_VIEWPORT_IDLE_DELAY_MS)
}

function loadGoogleMapsScript() {
  if (window.google?.maps?.importLibrary) return Promise.resolve()
  if (googleMapsPromise) return googleMapsPromise

  googleMapsPromise = new Promise((resolve, reject) => {
    const callbackName = `initParkingMap${Date.now()}`
    let settled = false
    const previousAuthFailure = window.gm_authFailure
    const timeoutId = window.setTimeout(() => {
      settle(new Error('Google Maps JavaScript API 載入逾時'))
    }, 15000)

    const settle = (error) => {
      if (settled) return
      settled = true
      window.clearTimeout(timeoutId)
      delete window[callbackName]
      if (window.gm_authFailure === handleAuthFailure) {
        if (previousAuthFailure) window.gm_authFailure = previousAuthFailure
        else delete window.gm_authFailure
      }
      if (error) {
        googleMapsPromise = undefined
        reject(error)
      } else {
        resolve()
      }
    }
    const handleAuthFailure = () => settle(new Error('Google Maps API 驗證失敗'))
    window[callbackName] = () => settle()
    window.gm_authFailure = handleAuthFailure

    const script = document.createElement('script')
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(googleMapsApiKey)}&v=weekly&language=zh-TW&region=TW&callback=${callbackName}`
    script.async = true
    script.onerror = () => settle(new Error('無法載入 Maps JavaScript API'))
    document.head.appendChild(script)
  })

  return googleMapsPromise
}

function setupPlaceAutocompletes(PlaceAutocompleteElement) {
  setupOriginPlaceAutocomplete(PlaceAutocompleteElement)
  setupDestinationPlaceAutocomplete(PlaceAutocompleteElement)
}

function setupOriginPlaceAutocomplete(PlaceAutocompleteElement) {
  setupPlaceAutocomplete({
    host: originAutocompleteHost.value,
    PlaceAutocompleteElement,
    placeholder: '搜尋出發地、地址或地標',
    selectionHandler: originPlaceSelectionHandler,
  })
}

function setupDestinationPlaceAutocomplete(PlaceAutocompleteElement) {
  setupPlaceAutocomplete({
    host: destinationAutocompleteHost.value,
    PlaceAutocompleteElement,
    placeholder: '搜尋目的地、地址或地標',
    selectionHandler: destinationPlaceSelectionHandler,
  })
}

function setupPlaceAutocomplete({ host, PlaceAutocompleteElement, placeholder, selectionHandler }) {
  if (!host) throw new Error('Google Places 搜尋欄位尚未就緒')

  host.replaceChildren()
  const autocomplete = new PlaceAutocompleteElement()
  autocomplete.placeholder = placeholder
  autocomplete.style.width = '100%'
  autocomplete.style.maxWidth = '100%'
  autocomplete.style.minWidth = '0'
  autocomplete.setAttribute('aria-label', placeholder)
  autocomplete.addEventListener('gmp-select', selectionHandler)
  host.appendChild(autocomplete)
}

function renderMap() {
  if (!map || !window.google?.maps) return

  const destinationPosition = getDestinationLatLng()
  if (!destinationMarker) {
    destinationMarker = new window.google.maps.Marker({
      map,
      position: destinationPosition,
      title: resultDestination.value.label,
      label: 'D',
    })
  } else {
    destinationMarker.setPosition(destinationPosition)
    destinationMarker.setTitle(resultDestination.value.label)
  }

  if (!radiusCircle) {
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
  } else {
    radiusCircle.setCenter(destinationPosition)
    radiusCircle.setRadius(queryResult.value?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS)
  }

  const itemsById = new Map(resultItems.value.map((item) => [item.id, item]))
  const visibleIds = new Set(visibleMapItems.value.map((item) => item.id))
  const clusterGroups = clusterRoadsideMarkers(
    visibleMapItems.value.filter((item) => item.id !== selectedItemId.value),
    map.getZoom() ?? 15,
  )
  const clusteredIds = new Set(clusterGroups.flatMap((group) => group.items.map((item) => item.id)))

  clusterMarkers.forEach((marker) => marker.setMap(null))
  clusterMarkers = []

  for (const [id, marker] of markersById.entries()) {
    if (itemsById.has(id)) continue
    marker.setMap(null)
    markersById.delete(id)
    parkingMarkers = parkingMarkers.filter((entry) => entry !== marker)
  }

  itemsById.forEach((item, id) => {
    const style = describeMapMarker(item)
    const markerText = style.markerText
    let marker = markersById.get(id)
    if (!marker) {
      marker = new window.google.maps.Marker({
        map: null,
        position: { lat: item.latitude, lng: item.longitude },
        title: item.label,
        label: { text: markerText, color: '#ffffff', fontSize: '10px', fontWeight: '800' },
        icon: createMarkerIcon(item, style.color),
        zIndex: item.type === 'OFF_STREET' ? 20 : 10,
      })
      marker.addListener('click', () => selectResultItem(id, true))
      markersById.set(id, marker)
      parkingMarkers.push(marker)
    } else {
      marker.setPosition({ lat: item.latitude, lng: item.longitude })
      marker.setTitle(`${item.label} · ${style.statusLabel}`)
      marker.setLabel({ text: markerText, color: '#ffffff', fontSize: '10px', fontWeight: '800' })
      marker.setIcon(createMarkerIcon(item, style.color))
    }
    marker.setMap(visibleIds.has(id) && !clusteredIds.has(id) ? map : null)
  })

  clusterGroups.forEach((group) => {
    const marker = new window.google.maps.Marker({
      map,
      position: { lat: group.center.latitude, lng: group.center.longitude },
      title: describeRoadsideCluster(group),
      label: { text: String(group.items.length), color: '#ffffff', fontSize: '11px', fontWeight: '800' },
      icon: {
        path: window.google.maps.SymbolPath.CIRCLE,
        scale: 17,
        fillColor: '#173d35',
        fillOpacity: 0.96,
        strokeColor: '#fffaf1',
        strokeWeight: 3,
      },
      zIndex: 30,
    })
    marker.addListener('click', () => zoomToRoadsideCluster(group))
    clusterMarkers.push(marker)
  })

  if (refreshSelectedMapPopup(
    selectedItemId.value,
    visibleIds,
    itemsById,
    infoWindow,
    createInfoWindowContent,
  )) selectedItemId.value = ''
}

function describeRoadsideCluster(group) {
  const counts = group.counts
  const statuses = [
    ['有位', counts.AVAILABLE],
    ['占用', counts.OCCUPIED],
    ['已滿', counts.FULL],
    ['未知', counts.UNKNOWN],
    ['過期', counts.STALE],
  ].filter(([, count]) => count > 0).map(([label, count]) => `${label} ${count}`).join('、')
  return `${group.items.length} 個路邊格位群聚；${statuses}。點選放大查看個別格位。`
}

function zoomToRoadsideCluster(group) {
  if (!map || !group.items.length) return
  const bounds = new window.google.maps.LatLngBounds()
  group.items.forEach((item) => bounds.extend({ lat: item.latitude, lng: item.longitude }))
  map.fitBounds(bounds, { top: 92, right: 92, bottom: 92, left: 92 })
}

function createMarkerIcon(item, color) {
  return {
    path: item.type === 'OFF_STREET'
      ? window.google.maps.SymbolPath.BACKWARD_CLOSED_ARROW
      : window.google.maps.SymbolPath.CIRCLE,
    scale: item.type === 'OFF_STREET' ? 12 : 10,
    fillColor: color,
    fillOpacity: 1,
    strokeColor: '#ffffff',
    strokeWeight: 2,
  }
}

function clearMarkers() {
  destinationMarker?.setMap(null)
  radiusCircle?.setMap(null)
  parkingMarkers.forEach((marker) => marker.setMap(null))
  clusterMarkers.forEach((marker) => marker.setMap(null))
  parkingMarkers = []
  clusterMarkers = []
  markersById.clear()
}

function toggleMapPanel(panel) {
  activeMapPanel.value = activeMapPanel.value === panel ? '' : panel
}

function focusDestination() {
  if (!map) return
  map.panTo(getDestinationLatLng())
  map.setZoom(Math.max(map.getZoom() ?? 15, 15))
}

function selectResultItem(itemId, fromMarker = false) {
  selectedItemId.value = itemId
  showResultPanel.value = true
  const item = resultItems.value.find((candidate) => candidate.id === itemId)
  const marker = markersById.get(itemId)
  if (!item || !marker || !map) return

  marker.setMap(map)
  map.panTo(marker.getPosition())
  if (!fromMarker) map.setZoom(Math.max(map.getZoom() ?? 15, 21))
  infoWindow?.setContent(createInfoWindowContent(item))
  infoWindow?.open({ map, anchor: marker })
}

function createInfoWindowContent(item) {
  const wrapper = document.createElement('div')
  wrapper.className = 'marker-info'
  const visual = describeMapMarker(item)

  const title = document.createElement('strong')
  title.className = 'marker-info-title'
  title.textContent = item.label
  wrapper.appendChild(title)

  const type = document.createElement('span')
  type.textContent = item.type === 'OFF_STREET' ? '路外停車場' : '路邊格位'
  wrapper.appendChild(type)

  const status = document.createElement('strong')
  status.className = 'marker-info-status'
  status.style.color = visual.color
  status.textContent = `${visual.statusLabel} · ${visual.value}`
  wrapper.appendChild(status)

  const source = document.createElement('span')
  source.textContent = `資料來源：${item.source || '新北市 Open Data（系統快照）'}`
  wrapper.appendChild(source)

  const observedTime = document.createElement('span')
  observedTime.textContent = `官方逐筆觀測：${item.sourceObservedAt ? formatDateTime(item.sourceObservedAt) : '未提供可信時間'}`
  wrapper.appendChild(observedTime)

  const systemTime = document.createElement('span')
  systemTime.textContent = `系統取得：${formatDateTime(item.systemCollectedAt)}`
  wrapper.appendChild(systemTime)

  const eligibility = document.createElement('span')
  eligibility.textContent = `一般汽車適用性：${formatEligibility(item.carEligibleStatus)}`
  wrapper.appendChild(eligibility)

  if (item.qualityStatus) {
    const quality = document.createElement('span')
    quality.textContent = `資料品質：${item.qualityStatus}`
    wrapper.appendChild(quality)
  }

  const message = document.createElement('span')
  message.className = 'marker-info-message'
  message.textContent = item.message || '目前觀測不代表抵達時仍有位。'
  wrapper.appendChild(message)

  if (visual.navigationAvailable) {
    const route = document.createElement('a')
    route.className = 'marker-route-link'
    route.href = buildExternalRouteUrl(item)
    route.target = '_blank'
    route.rel = 'noopener noreferrer'
    route.textContent = '在 Google Maps 開啟路線'
    wrapper.appendChild(route)
  }
  return wrapper
}

function formatEligibility(status) {
  if (status === 'CONFIRMED') return '已確認適用'
  if (status === 'NOT_ELIGIBLE') return '不適用'
  return '未確認'
}

function formatQuality(status) {
  if (status === 'PASS') return '通過'
  if (status === 'PARTIAL_PASS') return '部分通過'
  if (status === 'FAIL') return '未通過'
  if (status === 'UNKNOWN') return '未知'
  return '尚無逐筆品質紀錄'
}

function getDestinationLatLng() {
  const destination = resultDestination.value
  return {
    lat: Number(destination.latitude),
    lng: Number(destination.longitude),
  }
}

function useDefaultDestination() {
  destinationSelectionVersion++
  selectedDestination.value = { ...defaultDestination }
  renderMap()
}

function useCurrentLocation() {
  originPlaceError.value = ''
  errorMessage.value = ''
  requestCurrentLocation(navigator.geolocation, originUpdateCoordinator, {
    onStart() {
      locating.value = true
    },
    onSuccess(position) {
      origin.value = {
        label: '裝置目前位置',
        address: '',
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      }
      locating.value = false
    },
    onError(error) {
      errorMessage.value = error?.unsupported
        ? '此瀏覽器不支援定位，請改用地點搜尋選擇起點。'
        : locationErrorMessage(error)
      locating.value = false
    },
  })
}

function buildExternalRouteUrl(item) {
  const latitude = item.navigationLatitude ?? item.latitude
  const longitude = item.navigationLongitude ?? item.longitude
  return `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(`${latitude},${longitude}`)}&travelmode=driving`
}

function openExternalRoute(item) {
  if (!describeMapMarker(item).navigationAvailable) return
  window.open(buildExternalRouteUrl(item), '_blank', 'noopener,noreferrer')
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
      <div class="brand-lockup">
        <h1 id="app-title">台灣停車預測</h1>
        <p class="top-tagline">官方觀測、資料品質與抵達時間預測分開呈現</p>
      </div>
      <div class="service-status">
        <span class="status-dot" :class="{ ready: mapReady }" aria-hidden="true"></span>
        {{ mapReady ? '地圖與地點搜尋已載入' : googleMapsLoadError ? '地點搜尋載入失敗' : hasGoogleMapsKey ? '地圖尚未載入' : '地點搜尋不可用' }}
      </div>
    </section>

    <section class="planner-panel" aria-label="行程規劃">
      <div class="field-group destination-field">
        <label>目的地</label>
        <div v-if="hasGoogleMapsKey" ref="destinationAutocompleteHost" class="autocomplete-host"></div>
        <p v-else class="places-help" role="status">
          地點搜尋目前不可用，請設定 Google Maps browser key；已選取的預設目的地仍可規劃查詢。
        </p>
        <p v-if="googleMapsLoadError" class="places-error" role="alert">{{ googleMapsLoadError }}</p>
        <p v-else-if="hasGoogleMapsKey && !mapReady" class="places-help" role="status">正在載入 Google 地點搜尋…</p>
        <p v-if="destinationPlaceError" class="places-error" role="alert">{{ destinationPlaceError }}</p>
        <div class="selected-place">
          <strong>{{ selectedDestination.label }}</strong>
          <span>{{ selectedDestination.address || '所選地點未提供地址' }}</span>
          <span>{{ Number(selectedDestination.latitude).toFixed(6) }}, {{ Number(selectedDestination.longitude).toFixed(6) }}</span>
        </div>
      </div>

      <details class="planning-details">
        <summary>起點與出發時間 · {{ origin.label }} · {{ departureType === 'NOW' ? '立即出發' : '指定時間' }}</summary>
        <div class="planning-details-grid">
          <div class="field-group origin-field">
            <label>起點</label>
            <div v-if="hasGoogleMapsKey" ref="originAutocompleteHost" class="autocomplete-host"></div>
            <p v-else class="places-help" role="status">
              地點搜尋目前不可用，請設定 Google Maps browser key；已選取的新莊捷運站仍可規劃查詢。
            </p>
            <p v-if="googleMapsLoadError" class="places-error" role="alert">{{ googleMapsLoadError }}</p>
            <p v-else-if="hasGoogleMapsKey && !mapReady" class="places-help" role="status">正在載入 Google 地點搜尋…</p>
            <p v-if="originPlaceError" class="places-error" role="alert">{{ originPlaceError }}</p>
            <div class="selected-place">
              <strong>{{ origin.label }}</strong>
              <span>{{ origin.address || '裝置未提供地址' }}</span>
              <span>{{ Number(origin.latitude).toFixed(6) }}, {{ Number(origin.longitude).toFixed(6) }}</span>
            </div>
            <div class="origin-row">
              <button class="utility-button" type="button" :disabled="locating" @click="useCurrentLocation">
                {{ locating ? '定位中' : '使用目前位置' }}
              </button>
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
        </div>
      </details>

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
            <span>目的地周邊 {{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }} 公尺 · 目前觀測</span>
          </div>
          <button class="utility-button map-return-button" type="button" @click="focusDestination">回到目的地</button>
        </div>

        <nav class="map-tools" aria-label="地圖工具">
          <button
            class="map-tool-button"
            type="button"
            :aria-expanded="showResultPanel"
            aria-label="顯示停車結果清單"
            @click="showResultPanel = !showResultPanel; activeMapPanel = ''"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" /></svg>
            <span>結果</span>
            <small>{{ resultItems.length }}</small>
          </button>
          <button
            class="map-tool-button"
            type="button"
            :aria-expanded="activeMapPanel === 'layers'"
            aria-label="圖層與狀態篩選"
            @click="toggleMapPanel('layers')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5M3 16l9 5 9-5" /></svg>
            <span>篩選</span>
          </button>
          <button
            class="map-tool-button"
            type="button"
            :aria-expanded="activeMapPanel === 'legend'"
            aria-label="圖例說明"
            @click="toggleMapPanel('legend')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 7h.01" /></svg>
            <span>圖例</span>
          </button>
        </nav>

        <section v-if="activeMapPanel === 'layers'" class="map-control-panel" aria-label="圖層與狀態篩選">
          <header class="map-control-heading">
            <strong>圖層與狀態</strong>
            <button class="panel-close" type="button" @click="activeMapPanel = ''">關閉</button>
          </header>
          <fieldset class="map-filter-group">
            <legend>資料類型</legend>
            <label class="map-filter-option map-filter-option-layer"><input v-model="mapFilters.layers.roadside" type="checkbox"><span>路邊格位</span></label>
            <label class="map-filter-option map-filter-option-layer"><input v-model="mapFilters.layers.offStreet" type="checkbox"><span>路外停車場</span></label>
          </fieldset>
          <fieldset class="map-filter-group">
            <legend>車位狀態</legend>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.AVAILABLE" type="checkbox"><i class="status-swatch available" aria-hidden="true"></i><span>有位</span><small>{{ mapMarkerCounts.AVAILABLE }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.OCCUPIED" type="checkbox"><i class="status-swatch occupied" aria-hidden="true"></i><span>已占用</span><small>{{ mapMarkerCounts.OCCUPIED }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.FULL" type="checkbox"><i class="status-swatch full" aria-hidden="true"></i><span>已滿</span><small>{{ mapMarkerCounts.FULL }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.UNKNOWN" type="checkbox"><i class="status-swatch unknown" aria-hidden="true"></i><span>未知／無即時資料</span><small>{{ mapMarkerCounts.UNKNOWN }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.STALE" type="checkbox"><i class="status-swatch stale" aria-hidden="true"></i><span>資料過期</span><small>{{ mapMarkerCounts.STALE }}</small></label>
          </fieldset>
          <p class="filter-result-count">地圖顯示 {{ visibleMapItems.length }} / {{ resultItems.length }} 筆</p>
        </section>

        <section v-if="activeMapPanel === 'legend'" class="map-control-panel map-legend-panel" aria-label="地圖圖例">
          <header class="map-control-heading">
            <strong>標記說明</strong>
            <button class="panel-close" type="button" @click="activeMapPanel = ''">關閉</button>
          </header>
          <div class="legend-row"><i class="status-swatch available" aria-hidden="true"></i><span>路邊「有」：目前觀測有位</span></div>
          <div class="legend-row"><i class="status-swatch occupied" aria-hidden="true"></i><span>「占」或「滿」：已占用／已滿</span></div>
          <div class="legend-row"><i class="status-swatch unknown" aria-hidden="true"></i><span>「?」：未知、未確認資格或無資料</span></div>
          <div class="legend-row"><i class="status-swatch stale" aria-hidden="true"></i><span>「舊」：快照已過期</span></div>
          <p>路邊格位會依縮放合併群聚，拉近後逐格顯示；路外停車場維持獨立標記。群聚數是格位筆數，不代表空位數。</p>
          <p>位置依官方目錄座標顯示，本專案尚未完成實地位置精度校驗。</p>
        </section>

        <div v-if="hasGoogleMapsKey" ref="mapElement" class="google-map" aria-label="Google 地圖"></div>
        <div v-if="!mapReady" class="map-fallback" :class="{ overlay: hasGoogleMapsKey }">
          <div class="fallback-circle">{{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }}m</div>
          <strong>{{ mapStatus }}</strong>
          <span>下方結果列表仍可查看目前車位與資料時間。</span>
        </div>

        <div class="map-summary" aria-live="polite">
          <strong>即時資料狀態</strong>
          <span>{{ mapDataLoading ? '地圖標記更新中' : `${visibleMapItems.length} / ${resultItems.length} 筆資料符合篩選；近距離格位依縮放合併顯示` }}</span>
          <span v-if="mapViewportSupportMessage" class="map-support-message">{{ mapViewportSupportMessage }}</span>
          <span v-if="mapData?.generatedAt">畫面讀取：{{ formatDateTime(mapData.generatedAt) }}</span>
          <span v-if="mapData?.roadside?.systemCollectedAt || mapData?.offStreet?.systemCollectedAt">
            系統取得：路邊 {{ formatDateTime(mapData?.roadside?.systemCollectedAt) }} · 路外 {{ formatDateTime(mapData?.offStreet?.systemCollectedAt) }}
          </span>
          <span v-if="mapDataError" class="map-data-error">{{ mapDataError }}</span>
          <span v-if="mapData && (mapData.excludedUnverifiedCount || mapData.omittedItemCount)">
            未顯示 {{ mapData.excludedUnverifiedCount }} 筆未通過 mapping，另省略 {{ mapData.omittedItemCount }} 筆以控制標記數量。
          </span>
          <span v-if="queryResult">{{ etaMessage }}；車位為目前觀測，不是抵達預測。</span>
          <span v-if="queryResult && queryResult.area?.coverageStatus !== 'COMPLETE'">
            目前只涵蓋已審查的目的地周邊 500 公尺：{{ queryResult.area?.coverageStatus === 'NO_CATALOG_RESULTS' ? '尚無已審查目錄資料' : '目錄僅部分收錄' }}
          </span>
        </div>
      </section>

      <aside v-if="showResultPanel" class="result-panel" aria-label="查詢結果">
        <div class="panel-header">
          <div>
            <h2>停車結果</h2>
            <span class="panel-subtitle">{{ mapData?.viewport ? '目前地圖範圍' : `目的地周邊 ${queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS} 公尺` }} · {{ visibleMapItems.length }} 筆符合篩選</span>
          </div>
          <div class="panel-actions">
            <button class="utility-button" type="button" :disabled="loading || !queryResult" @click="refreshParkingAvailability()">
              {{ loading ? '更新中' : '更新車位' }}
            </button>
            <button class="panel-close" type="button" aria-label="關閉停車結果" @click="showResultPanel = false">關閉</button>
          </div>
        </div>

        <article v-if="selectedMapItem" class="selected-spot-card">
          <header class="selected-spot-heading">
            <div class="selected-spot-mark" :class="selectedMapItem.type === 'OFF_STREET' ? 'off-street' : 'roadside'">
              {{ selectedMapItem.type === 'OFF_STREET' ? 'P' : '格' }}
            </div>
            <div class="selected-spot-title">
              <strong>{{ selectedMapItem.label }}</strong>
              <span>{{ selectedMapItem.type === 'OFF_STREET' ? '路外停車場' : '路邊格位' }} · 距離約 {{ selectedMapItem.distanceMeters }} 公尺</span>
            </div>
          </header>
          <div class="selected-spot-facts">
            <div><span>目前官方觀測</span><strong>{{ selectedMapItem.currentValue }}</strong></div>
            <div><span>目前可用／總車位</span><strong>{{ formatMapCapacity(selectedMapItem) }}</strong></div>
            <div><span>資料品質</span><strong>{{ formatQuality(selectedMapItem.qualityStatus) }}</strong></div>
            <div><span>一般汽車適用性</span><strong>{{ formatEligibility(selectedMapItem.carEligibleStatus) }}</strong></div>
            <div><span>官方逐筆時間</span><strong>{{ selectedMapItem.sourceObservedAt ? formatDateTime(selectedMapItem.sourceObservedAt) : '來源未提供可信時間' }}</strong></div>
            <div><span>系統取得時間</span><strong>{{ formatDateTime(selectedMapItem.systemCollectedAt) }}</strong></div>
            <div><span>位置</span><strong>{{ selectedMapItem.address || '依官方目錄座標顯示' }}</strong></div>
            <div><span>收費</span><strong>{{ selectedMapItem.feeDescription || selectedMapItem.feeStatus || '來源未提供' }}</strong></div>
            <div><span>收費時段</span><strong>{{ [selectedMapItem.chargingDays, selectedMapItem.chargingHours].filter(Boolean).join(' · ') || '來源未提供' }}</strong></div>
            <div><span>抵達時預測</span><strong>{{ queryResult?.predictions?.status === 'READY' ? '已完成驗證' : '資料不足' }}</strong></div>
          </div>
          <p v-if="selectedMapItem.qualityReason || selectedMapItem.notes || selectedMapItem.message" class="selected-spot-note">
            {{ selectedMapItem.qualityReason || selectedMapItem.notes || selectedMapItem.message }}
          </p>
          <button
            v-if="describeMapMarker(selectedMapItem).navigationAvailable"
            class="route-link selected-route-link"
            type="button"
            @click="openExternalRoute(selectedMapItem)"
          >在地圖中規劃路線</button>
        </article>

        <template v-if="queryResult">
          <div class="status-grid">
            <div>
              <span>資料查詢</span>
              <strong>{{ queryResult.query.status === 'READY' ? '已完成' : queryResult.query.status }}</strong>
            </div>
            <div>
              <span>Google 預估抵達</span>
              <strong>{{ queryResult.eta.status === 'READY' ? '已估算' : '尚無 ETA' }}</strong>
            </div>
            <div>
              <span>抵達時預測</span>
              <strong>{{ queryResult.predictions.status === 'INSUFFICIENT_DATA' ? '資料不足' : queryResult.predictions.status }}</strong>
            </div>
          </div>

          <article class="result-card">
            <strong>路邊區域</strong>
            <span>{{ roadsideDisplaySummary.message }}</span>
            <span>目前可用：{{ roadsideDisplaySummary.availableSpaces ?? '未知' }}</span>
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
            <span>{{ visibleMapItems.length }} / {{ resultItems.length }} 筆</span>
          </div>

          <article
            v-for="item in visibleMapItems"
            :key="item.id"
            class="parking-item"
            :class="{ selected: selectedItemId === item.id }"
          >
            <button class="parking-select" type="button" @click="selectResultItem(item.id)">
              <span class="item-type">{{ item.type === 'OFF_STREET' ? '路外' : '路邊' }}</span>
              <strong>{{ item.label }}</strong>
              <span>{{ item.distanceMeters }} 公尺 · {{ item.currentValue }}</span>
              <span>官方逐筆觀測：{{ item.sourceObservedAt ? formatDateTime(item.sourceObservedAt) : '未提供可信時間' }}</span>
              <span>系統取得：{{ formatDateTime(item.systemCollectedAt) }}</span>
              <span>{{ item.message }}</span>
            </button>
            <button
              v-if="describeMapMarker(item).navigationAvailable"
              class="route-link"
              type="button"
              @click="openExternalRoute(item)"
            >
              查看路線
            </button>
          </article>
          <article v-if="visibleMapItems.length === 0" class="result-card">
            <strong>{{ resultItems.length ? '目前篩選沒有符合標記' : '目前沒有可顯示的停車資料' }}</strong>
            <span>{{ resultItems.length ? '調整圖層或狀態篩選即可查看其他資料。' : '地圖只呈現已審查目錄與已發布快照；不會替缺失資料產生標記。' }}</span>
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
          <span>API：{{ apiBaseUrlLabel }}</span>
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
