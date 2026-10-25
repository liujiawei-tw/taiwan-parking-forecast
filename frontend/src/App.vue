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
  buildDeparture,
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
  getMapZoomForGroundDistance,
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
const DEPARTURE_TYPE = 'NOW'

const mapElement = ref(null)
const mapStageElement = ref(null)
const workspaceElement = ref(null)
const mobileSheetSnap = ref('half')
const mobileSheetMapHeight = ref(null)
const mobileSheetDragging = ref(false)
const originAutocompleteHost = ref(null)
const destinationAutocompleteHost = ref(null)
const selectedDestination = ref({ ...defaultDestination })
const googlePlacesByQueryId = ref({})
const origin = ref({ ...DEFAULT_ORIGIN })
const queryResult = ref(null)
const loading = ref(false)
const locating = ref(false)
const errorMessage = ref('')
const refreshMessage = ref('')
const planningMessage = ref('')
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
const activeNavigation = ref('map')
const activeParkingTypeFilter = ref('ALL')
const favoriteItemIds = ref([])
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
let automaticPlanningEnabled = false
let automaticPlanningTimer
let pendingPlanningInputKey = ''
let lastSubmittedPlanningInputKey = ''
let activePlanningInputKey = ''
let planningRequestSequence = 0
let activePlanningRequestId = 0
let sheetDragState = null
let suppressSheetClickUntil = 0
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
    automaticPlanningEnabled = true
    selectedDestination.value = place
    focusMapAtGroundDistance(place, 50)
    logAcceptanceEvent('PLACE_SELECTED', '已選取 Google Places 目的地。')
    scheduleAutomaticParkingQuery({ force: true })
  },
  onError: (message) => { destinationPlaceError.value = message },
})

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
    return '原預計抵達時間已過，請更新出發時間或重新選取目的地。'
  }
  if (eta.status === 'READY') return `Google 預估抵達：${formatDateTime(eta.estimatedArrivalAt)}`
  if (/external calls are disabled/i.test(eta.message ?? '')) {
    return '抵達時間尚未估算：本機 Google Routes 呼叫目前關閉；目前觀測與資料品質仍可查看。'
  }
  if (/not configured|missing (?:an? )?api key/i.test(eta.message ?? '')) {
    return '抵達時間尚未估算：Google Routes 尚未完成伺服器設定。'
  }
  return 'Google 路線無法估算；調整起點或出發時間後會自動重新規劃，目前車位仍可參考。'
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
    return '原預計抵達時間已過，請更新出發時間或重新選取目的地。'
  }
  if (queryResult.value?.predictions?.status === 'EXPERIMENTAL') {
    return '部分路外場站有實驗性估計；支援快照後約 30／60 分鐘抵達（前後 15 分鐘），尚未通過正式模型驗證。'
  }
  return '尚無合格的抵達時車位機率；以下僅顯示目前資料。'
})
const googlePlacesMessage = computed(() => {
  const section = currentGooglePlacesSection()
  const count = section?.items?.length ?? googlePlacesByQueryId.value[queryId.value]?.items?.length ?? 0
  if (count > 0) return `另列 ${count} 個 Google Places 停車場地點；此地點資料不含即時空位，也不代表新北市官方觀測。`
  if (section?.status === 'DISABLED') return 'Google Places 輔助搜尋目前未啟用。'
  if (section?.status === 'NOT_CONFIGURED') return 'Google Places 輔助搜尋尚未完成伺服器設定。'
  if (section?.status === 'FAILED') return 'Google Places 輔助搜尋失敗；請檢查 Places API (New) 啟用狀態、伺服器 key 限制與可用額度。'
  if (section?.status === 'NOT_RETRIEVED') return 'Google Places 地點資料未保存；若需要輔助地點，請重新規劃查詢。'
  if (section?.status === 'NO_RESULTS') return section.message || 'Google Places 未回傳 500 公尺內的停車設施地點。'
  if (section?.message) return section.message
  return 'Google Places 未提供可顯示的停車場地點；路邊逐格資料仍以官方目錄為準。'
})

function rememberGooglePlaces(result) {
  const id = result?.query?.queryId
  const section = result?.googlePlaces
  if (!id || !Array.isArray(section?.items) || section.items.length === 0) return
  googlePlacesByQueryId.value = { ...googlePlacesByQueryId.value, [id]: section }
}

function currentGooglePlacesSection() {
  const id = queryId.value
  const current = queryResult.value?.googlePlaces
  if (current?.items?.length) return current
  return googlePlacesByQueryId.value[id] ?? current ?? null
}

function googlePlacesMapItems() {
  const section = currentGooglePlacesSection()
  const destination = resultDestination.value
  return (section?.items ?? []).flatMap((place) => {
    const latitude = Number(place.latitude ?? place.location?.latitude)
    const longitude = Number(place.longitude ?? place.location?.longitude)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
        || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return []

    const calculatedDistance = distanceBetweenMeters(destination.latitude, destination.longitude, latitude, longitude)
    const hasDistance = place.distanceMeters !== null && place.distanceMeters !== undefined
      && Number.isFinite(Number(place.distanceMeters))
    const distanceMeters = hasDistance
      ? Math.round(Number(place.distanceMeters))
      : calculatedDistance
    if (distanceMeters > DEFAULT_RADIUS_METERS) return []

    const placeId = String(place.placeId ?? place.id ?? `${latitude.toFixed(6)}-${longitude.toFixed(6)}`)
    const hasNavigationPoint = place.navigationAvailable === true
      && place.navigationLatitude !== null && place.navigationLatitude !== undefined
      && Number.isFinite(Number(place.navigationLatitude))
      && Math.abs(Number(place.navigationLatitude)) <= 90
      && place.navigationLongitude !== null && place.navigationLongitude !== undefined
      && Number.isFinite(Number(place.navigationLongitude))
      && Math.abs(Number(place.navigationLongitude)) <= 180
    return [{
      id: `google-place-${placeId}`,
      rawId: placeId,
      type: 'OFF_STREET',
      label: place.name ?? place.displayName?.text ?? 'Google Places 停車場',
      latitude,
      longitude,
      navigationLatitude: hasNavigationPoint ? Number(place.navigationLatitude) : null,
      navigationLongitude: hasNavigationPoint ? Number(place.navigationLongitude) : null,
      distanceMeters,
      availabilityStatus: 'UNKNOWN',
      availableCarSpaces: null,
      totalCarSpaces: null,
      carEligibleStatus: 'UNKNOWN',
      navigationAvailable: hasNavigationPoint,
      source: 'GOOGLE_PLACES',
      dataOrigin: 'GOOGLE_PLACES',
      googlePlaceId: placeId,
      googleMapsUri: place.googleMapsUri ?? null,
      sourceObservedAt: null,
      address: place.address ?? place.formattedAddress ?? '',
      systemCollectedAt: place.systemCollectedAt ?? section.generatedAt ?? null,
      message: hasNavigationPoint
        ? 'Google Places 地點目錄不提供即時空位；路邊逐格資料仍以官方目錄為準。'
        : 'Google Places 地點目錄不提供即時空位或確認過的開車入口；請在 Google Maps 地點頁確認入口。',
      currentValue: '即時空位未知',
    }]
  })
}

function distanceBetweenMeters(startLatitude, startLongitude, endLatitude, endLongitude) {
  const toRadians = (degrees) => degrees * Math.PI / 180
  const latitudeDelta = toRadians(endLatitude - Number(startLatitude))
  const longitudeDelta = toRadians(endLongitude - Number(startLongitude))
  const start = toRadians(Number(startLatitude))
  const end = toRadians(endLatitude)
  const arc = 2 * Math.asin(Math.sqrt(
    Math.sin(latitudeDelta / 2) ** 2
      + Math.cos(start) * Math.cos(end) * Math.sin(longitudeDelta / 2) ** 2,
  ))
  return Math.round(6371000 * arc)
}

function mergeGooglePlaces(officialItems, googleItems) {
  const distinctGoogleItems = googleItems.filter((place) => !officialItems.some((official) => {
    if (official.type !== 'OFF_STREET') return false
    const distance = distanceBetweenMeters(official.latitude, official.longitude, place.latitude, place.longitude)
    const sameName = String(official.label ?? '').trim().toLocaleLowerCase()
      === String(place.label ?? '').trim().toLocaleLowerCase()
    return distance <= 12 || (sameName && distance <= 80)
  }))
  return [...officialItems, ...distinctGoogleItems]
}

const resultItems = computed(() => {
  const googleItems = googlePlacesMapItems()
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
    const officialItems = freshItems.map((item) => ({
      ...item,
      rawId: item.id.replace(/^(spot-|lot-)/, ''),
      currentValue: describeMapMarker(item).value,
    }))
    return mergeGooglePlaces(officialItems, googleItems)
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
  const officialItems = fallbackItems.map((item) => ({ ...item, currentValue: describeMapMarker(item).value }))
  return mergeGooglePlaces(officialItems, googleItems)
})
const visibleMapItems = computed(() => filterMapMarkers(resultItems.value, mapFilters.value)
  .filter((item) => activeParkingTypeFilter.value === 'ALL'
    || (activeParkingTypeFilter.value === 'ROADSIDE' && item.type === 'ROADSIDE')
    || (activeParkingTypeFilter.value === 'OFF_STREET' && item.type === 'OFF_STREET')))
const favoriteMapItems = computed(() => visibleMapItems.value.filter((item) => favoriteItemIds.value.includes(item.id)))
const nearbyListItems = computed(() => visibleMapItems.value
  .slice()
  .sort((left, right) => {
    const leftAvailable = describeMapMarker(left).category === 'AVAILABLE'
      && !(left.type === 'OFF_STREET' && Number.isFinite(left.availableCarSpaces) && left.availableCarSpaces <= 0)
    const rightAvailable = describeMapMarker(right).category === 'AVAILABLE'
      && !(right.type === 'OFF_STREET' && Number.isFinite(right.availableCarSpaces) && right.availableCarSpaces <= 0)
    if (leftAvailable !== rightAvailable) return leftAvailable ? -1 : 1

    const leftDistance = left.distanceMeters !== null && left.distanceMeters !== undefined
      && Number.isFinite(Number(left.distanceMeters)) ? Number(left.distanceMeters) : Number.POSITIVE_INFINITY
    const rightDistance = right.distanceMeters !== null && right.distanceMeters !== undefined
      && Number.isFinite(Number(right.distanceMeters)) ? Number(right.distanceMeters) : Number.POSITIVE_INFINITY
    if (leftDistance !== rightDistance) return leftDistance - rightDistance
    return String(left.label ?? '').localeCompare(String(right.label ?? ''), 'zh-TW')
  }))
const nearbyListSummary = computed(() => {
  if (!queryResult.value) return '官方資料 · 查詢後載入'
  return `${nearbyListItems.value.length} 個符合篩選 · 有位優先，距離近者在前`
})
const displayedMapItems = computed(() => {
  if (activeNavigation.value === 'favorites') return favoriteMapItems.value
  return nearbyListItems.value
})
const mapMarkerCounts = computed(() => countMapMarkerCategories(resultItems.value))
const selectedMapItem = computed(() => resultItems.value.find((item) => item.id === selectedItemId.value) ?? null)
const selectedOffStreetPrediction = computed(() => {
  if (selectedMapItem.value?.type !== 'OFF_STREET') return null
  const parkingLotId = String(selectedMapItem.value.rawId ?? selectedMapItem.value.id?.replace(/^lot-/, ''))
  return queryResult.value?.predictions?.offStreet?.find((prediction) => prediction.parkingLotId === parkingLotId) ?? null
})
const selectedOffStreetPredictionLead = computed(() => {
  const match = selectedOffStreetPrediction.value?.modelVersion?.match(/lead-(\d+)m/)
  return match ? Number(match[1]) : null
})
const selectedEtaArrivalLabel = computed(() => {
  if (queryResult.value?.eta?.status === 'READY') {
    return formatDateTime(queryResult.value.eta.estimatedArrivalAt)
  }
  return queryResult.value ? '尚未估算' : '尚未規劃'
})
const selectedEtaDescription = computed(() => {
  if (!queryResult.value) return '選取目的地後估算行車時間。'
  if (queryResult.value.eta?.status === 'READY') return '依本次行程的 Google Routes 結果。'
  return etaMessage.value
})
const selectedPredictionValue = computed(() => {
  const prediction = selectedOffStreetPrediction.value
  if (prediction?.status === 'EXPERIMENTAL') {
    return Number.isFinite(prediction.probability)
      ? `${Math.round(prediction.probability * 100)}%`
      : '實驗性估計'
  }
  if (queryResult.value?.predictions?.status === 'READY') return '通過驗證'
  return queryResult.value ? '資料不足' : '尚未規劃'
})
const selectedPredictionDescription = computed(() => {
  const prediction = selectedOffStreetPrediction.value
  if (prediction?.status === 'EXPERIMENTAL') {
    const lead = selectedOffStreetPredictionLead.value
      ? `採用 ${selectedOffStreetPredictionLead.value} 分鐘離線模型；`
      : '離線實驗估計；'
    return lead + '此機率表示模型估計您抵達時該場仍有空位的可能性；不保證有位，模型尚未正式驗證。'
  }
  if (queryResult.value) return predictionMessage.value
  return '選取目的地並完成查詢後顯示。'
})
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
  departureType: DEPARTURE_TYPE,
  scheduledDepartureLocal: '',
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
  if (automaticPlanningTimer) window.clearTimeout(automaticPlanningTimer)
  if (mapViewportLoadTimer) window.clearTimeout(mapViewportLoadTimer)
  sheetDragState = null
  mapIdleListener?.remove?.()
})

watch([resultDestination, visibleMapItems], () => {
  renderMap()
}, { deep: true })

watch(selectedItemId, () => {
  renderMap()
})

watch(currentPlanningInputs, (next, previous) => {
  if (hasPlanningInputsChanged(next, previous)) {
    queryRequestGuard.invalidate()
    cancelLoading()
    activePlanningInputKey = ''
    lastSubmittedPlanningInputKey = ''
    queryResult.value = null
    clearMapDataState()
    selectedItemId.value = ''
    refreshMessage.value = ''
    clearProgressPolling()
    if (automaticPlanningEnabled) scheduleAutomaticParkingQuery()
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

function scheduleAutomaticParkingQuery({ force = false } = {}) {
  if (!automaticPlanningEnabled) return

  const submittedPlanningInputs = currentPlanningInputs.value
  const inputKey = JSON.stringify(submittedPlanningInputs)
  if (!force && lastSubmittedPlanningInputKey === inputKey) return
  if (activePlanningInputKey === inputKey) return
  if (pendingPlanningInputKey === inputKey) return

  try {
    buildDeparture(DEPARTURE_TYPE)
  } catch (error) {
    if (automaticPlanningTimer) window.clearTimeout(automaticPlanningTimer)
    automaticPlanningTimer = undefined
    pendingPlanningInputKey = ''
    planningMessage.value = ''
    errorMessage.value = `${error.message} 設定完成後會自動規劃。`
    return
  }

  if (automaticPlanningTimer) window.clearTimeout(automaticPlanningTimer)
  pendingPlanningInputKey = inputKey
  errorMessage.value = ''
  planningMessage.value = force
    ? '目的地已選取，正在準備附近停車查詢。'
    : '行程條件已更新，正在重新規劃附近停車資訊。'
  automaticPlanningTimer = window.setTimeout(() => {
    automaticPlanningTimer = undefined
    if (pendingPlanningInputKey !== inputKey
      || JSON.stringify(currentPlanningInputs.value) !== inputKey
      || !automaticPlanningEnabled) return

    pendingPlanningInputKey = ''
    if (activePlanningInputKey === inputKey) return
    if (!force && lastSubmittedPlanningInputKey === inputKey) {
      planningMessage.value = ''
      return
    }

    lastSubmittedPlanningInputKey = inputKey
    void createParkingQuery()
  }, 300)
}

async function createParkingQuery() {
  const submittedDestination = { ...selectedDestination.value }
  const submittedOrigin = { ...origin.value }
  const submittedPlanningInputs = currentPlanningInputs.value
  const submittedInputKey = JSON.stringify(submittedPlanningInputs)
  const planningRequestId = ++planningRequestSequence
  activePlanningRequestId = planningRequestId
  activePlanningInputKey = submittedInputKey
  const requestContext = queryRequestGuard.start(submittedPlanningInputs)
  const loadingRequestId = startLoading()
  errorMessage.value = ''
  planningMessage.value = '正在規劃目的地附近的停車資訊。'

  try {
    const payload = buildParkingQueryPayload({
      requestToken: buildRequestToken(window.crypto),
      origin: submittedOrigin,
      destination: submittedDestination,
      departureType: DEPARTURE_TYPE,
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

    rememberGooglePlaces(result)
    queryResult.value = result
    planningMessage.value = ''
    selectedItemId.value = ''
    clearMapDataState()
    refreshMessage.value = ''
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
      planningMessage.value = ''
    }
  } finally {
    if (activePlanningRequestId === planningRequestId) {
      activePlanningInputKey = ''
    }
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

    rememberGooglePlaces(result)
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

    rememberGooglePlaces(result)
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
      try {
        payload = await loadMapViewportPages(fetch, {
          apiBaseUrl,
          queryId: id,
          bounds: request.viewport,
        })
      } catch (viewportError) {
        // Older API images do not expose the viewport route yet. Keep the
        // destination-area results usable while the API container is updated.
        const fallbackResponse = await fetch(`${apiBaseUrl}/api/v1/parking-queries/${id}/map-data`)
        if (!fallbackResponse.ok) throw viewportError
        payload = await fallbackResponse.json()
      }
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
    fullscreenControl: false,
    zoomControl: false,
    scaleControl: true,
    styles: [
      { featureType: 'all', elementType: 'labels.text.fill', stylers: [{ color: '#496357' }] },
      { featureType: 'all', elementType: 'labels.text.stroke', stylers: [{ color: '#fffdf7' }] },
      { featureType: 'landscape', elementType: 'geometry.fill', stylers: [{ color: '#f4f0e5' }] },
      { featureType: 'landscape.man_made', elementType: 'geometry.fill', stylers: [{ color: '#eae5d9' }] },
      { featureType: 'poi', elementType: 'geometry.fill', stylers: [{ color: '#e2ead8' }] },
      { featureType: 'poi.park', elementType: 'geometry.fill', stylers: [{ color: '#c9dfba' }] },
      { featureType: 'road', elementType: 'geometry.fill', stylers: [{ color: '#fffdf7' }] },
      { featureType: 'road', elementType: 'geometry.stroke', stylers: [{ color: '#d6cebc' }] },
      { featureType: 'road.highway', elementType: 'geometry.fill', stylers: [{ color: '#f2dca1' }] },
      { featureType: 'transit', elementType: 'geometry.fill', stylers: [{ color: '#d9d2c4' }] },
      { featureType: 'water', elementType: 'geometry.fill', stylers: [{ color: '#a9d8d2' }] },
    ],
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
  autocomplete.style.colorScheme = 'light'
  autocomplete.style.color = '#20394e'
  autocomplete.style.backgroundColor = 'transparent'
  autocomplete.style.border = '0'
  autocomplete.style.borderRadius = '0'
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
      strokeColor: '#16824b',
      strokeOpacity: 0.8,
      strokeWeight: 2,
      fillColor: '#62a881',
      fillOpacity: 0.08,
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
    const isSelected = id === selectedItemId.value
    let marker = markersById.get(id)
    if (!marker) {
      marker = new window.google.maps.Marker({
        map: null,
        position: { lat: item.latitude, lng: item.longitude },
        title: item.dataOrigin === 'GOOGLE_PLACES'
          ? `${item.label} · Google Places 地點資料，空位未知`
          : item.label,
        label: item.type === 'OFF_STREET' ? null : { text: markerText, color: '#ffffff', fontSize: isSelected ? '13px' : '12px', fontWeight: '800' },
        icon: createMarkerIcon(item, style.color, isSelected),
        zIndex: isSelected ? 100 : item.type === 'OFF_STREET' ? 20 : 10,
      })
      marker.addListener('click', () => selectResultItem(id))
      markersById.set(id, marker)
      parkingMarkers.push(marker)
    } else {
      marker.setPosition({ lat: item.latitude, lng: item.longitude })
      marker.setTitle(item.dataOrigin === 'GOOGLE_PLACES'
        ? `${item.label} · Google Places 地點資料，空位未知`
        : `${item.label} · ${style.statusLabel}`)
      marker.setLabel(item.type === 'OFF_STREET' ? null : { text: markerText, color: '#ffffff', fontSize: isSelected ? '13px' : '12px', fontWeight: '800' })
      marker.setIcon(createMarkerIcon(item, style.color, isSelected))
      marker.setZIndex(isSelected ? 100 : item.type === 'OFF_STREET' ? 20 : 10)
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

function createMarkerIcon(item, color, selected = false) {
  if (item.type === 'OFF_STREET') {
    const zoomLevel = map?.getZoom?.() ?? 15
    const zoomScale = 1 + Math.min(0.27, Math.max(0, zoomLevel - 15) * 0.045)
    const baseSize = selected ? { width: 64, height: 72, anchorX: 32, anchorY: 63 } : { width: 56, height: 64, anchorX: 28, anchorY: 58 }
    const selectedHalo = selected
      ? '<circle cx="32" cy="32" r="30" fill="#fffefa" stroke="#173b35" stroke-width="2" filter="url(#marker-shadow)"/>'
      : ''
    const markerTransform = selected
      ? ' transform="translate(5.75 5) scale(1.25)"'
      : ' transform="translate(1.75 0) scale(1.25)"'
    const viewBox = selected ? '0 0 64 72' : '0 0 56 64'
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}">${selected ? '<defs><filter id="marker-shadow" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="#173b35" flood-opacity=".28"/></filter></defs>' : ''}${selectedHalo}<path${markerTransform} d="M21 1.5C10.2 1.5 1.5 10.1 1.5 20.7c0 12 19.5 25.7 19.5 25.7s19.5-13.7 19.5-25.7C40.5 10.1 31.8 1.5 21 1.5Z" fill="#3c6f9e" stroke="#fffefa" stroke-width="2"/><text${markerTransform} x="21" y="21.5" fill="#fffefa" font-family="Arial,sans-serif" font-size="18" font-weight="800" text-anchor="middle" dominant-baseline="central">P</text></svg>`
    return {
      url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`,
      scaledSize: new window.google.maps.Size(baseSize.width * zoomScale, baseSize.height * zoomScale),
      anchor: new window.google.maps.Point(baseSize.anchorX * zoomScale, baseSize.anchorY * zoomScale),
    }
  }

  if (selected) {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 56 56"><defs><filter id="marker-shadow" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="#173b35" flood-opacity=".28"/></filter></defs><circle cx="28" cy="28" r="24" fill="#fffefa" stroke="#173b35" stroke-width="2" filter="url(#marker-shadow)"/><circle cx="28" cy="28" r="17" fill="${color}" stroke="#fffefa" stroke-width="3"/></svg>`
    return {
      url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`,
      scaledSize: new window.google.maps.Size(56, 56),
      anchor: new window.google.maps.Point(28, 28),
    }
  }

  return {
    path: window.google.maps.SymbolPath.CIRCLE,
    scale: 20,
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

const mobileSheetSnapRatios = { peek: 0.5, half: 0.34, expanded: 0.18 }

function getMobileSheetMapHeight(snap) {
  const workspaceHeight = workspaceElement.value?.clientHeight ?? window.innerHeight
  const minimum = Math.min(150, Math.max(112, workspaceHeight * 0.24))
  const maximum = Math.max(minimum, workspaceHeight - 220)
  const desired = workspaceHeight * mobileSheetSnapRatios[snap]
  return Math.round(Math.min(maximum, Math.max(minimum, desired)))
}

function startMobileSheetDrag(event) {
  if (window.matchMedia('(min-width: 781px)').matches) return
  if (event.button !== undefined && event.button !== 0) return

  const currentMapHeight = mapStageElement.value?.getBoundingClientRect().height
  if (!Number.isFinite(currentMapHeight)) return

  event.currentTarget.setPointerCapture?.(event.pointerId)
  sheetDragState = {
    pointerId: event.pointerId,
    startY: event.clientY,
    startMapHeight: currentMapHeight,
    moved: false,
  }
  mobileSheetDragging.value = true
}

function moveMobileSheetDrag(event) {
  if (!sheetDragState || event.pointerId !== sheetDragState.pointerId) return

  const deltaY = event.clientY - sheetDragState.startY
  if (Math.abs(deltaY) > 6) sheetDragState.moved = true
  const workspaceHeight = workspaceElement.value?.clientHeight ?? window.innerHeight
  const minimum = Math.min(150, Math.max(112, workspaceHeight * 0.24))
  const maximum = Math.max(minimum, workspaceHeight - 220)
  mobileSheetMapHeight.value = Math.round(Math.min(
    maximum,
    Math.max(minimum, sheetDragState.startMapHeight + deltaY),
  ))
}

function finishMobileSheetDrag(event) {
  if (!sheetDragState || event.pointerId !== sheetDragState.pointerId) return

  if (sheetDragState.moved) {
    const currentHeight = mobileSheetMapHeight.value ?? sheetDragState.startMapHeight
    mobileSheetSnap.value = Object.keys(mobileSheetSnapRatios).reduce((closest, snap) => (
      Math.abs(getMobileSheetMapHeight(snap) - currentHeight)
        < Math.abs(getMobileSheetMapHeight(closest) - currentHeight) ? snap : closest
    ), 'peek')
    suppressSheetClickUntil = Date.now() + 450
  }

  sheetDragState = null
  mobileSheetMapHeight.value = null
  mobileSheetDragging.value = false
}

function toggleMobileSheet(event) {
  if (event.detail > 0 && Date.now() < suppressSheetClickUntil) {
    event.preventDefault()
    return
  }
  const snaps = ['peek', 'half', 'expanded']
  const currentIndex = snaps.indexOf(mobileSheetSnap.value)
  mobileSheetSnap.value = snaps[(currentIndex + 1) % snaps.length]
}

function toggleFavorite(itemId) {
  favoriteItemIds.value = favoriteItemIds.value.includes(itemId)
    ? favoriteItemIds.value.filter((id) => id !== itemId)
    : [...favoriteItemIds.value, itemId]
}

function getSpotBadge(item) {
  if (!item) return '⌖'
  if (item.type === 'OFF_STREET') return 'P'
  const match = String(item.label ?? '').match(/\d+/)
  return match?.[0] ?? '格'
}

async function shareSelectedItem() {
  const item = selectedMapItem.value
  if (!item) return
  const shareText = `${item.label} · ${item.currentValue} · 距離約 ${item.distanceMeters} 公尺`
  try {
    if (navigator.share) {
      await navigator.share({ title: item.label, text: shareText, url: window.location.href })
      return
    }
    await navigator.clipboard.writeText(`${shareText}\n${window.location.href}`)
    refreshMessage.value = '已複製停車資訊連結。'
  } catch (error) {
    if (error?.name !== 'AbortError') errorMessage.value = '無法分享停車資訊，請稍後再試。'
  }
}

function focusDestination() {
  focusMapAtGroundDistance(getDestinationLatLng(), 50)
}

function focusMapAtGroundDistance(location, distanceMeters) {
  if (!map || !location) return
  const latitude = Number(location.latitude ?? location.lat)
  const longitude = Number(location.longitude ?? location.lng)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return

  map.panTo({ lat: latitude, lng: longitude })
  map.setZoom(getMapZoomForGroundDistance(distanceMeters, latitude))
}

function selectResultItem(itemId) {
  selectedItemId.value = itemId
  const item = resultItems.value.find((candidate) => candidate.id === itemId)
  const marker = markersById.get(itemId)
  if (!item || !map) return

  marker?.setMap(map)
  const markerPosition = marker?.getPosition()
  const latitude = Number(markerPosition?.lat?.() ?? item.latitude)
  const longitude = Number(markerPosition?.lng?.() ?? item.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return
  map.panTo({ lat: latitude, lng: longitude })
  const isOffStreet = item.type === 'OFF_STREET'
  const targetScaleMeters = isOffStreet ? 20 : 5
  const referenceWidthPixels = isOffStreet ? 37 : undefined
  map.setZoom(getMapZoomForGroundDistance(targetScaleMeters, latitude, referenceWidthPixels))
  if (!marker) return
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
  status.textContent = isGooglePlace
    ? '即時空位未知'
    : `${visual.statusLabel} · ${visual.value}`
  wrapper.appendChild(status)

  const isGooglePlace = item.dataOrigin === 'GOOGLE_PLACES'
  const source = document.createElement('span')
  source.textContent = isGooglePlace
    ? '資料來源：Google Places 停車場地點目錄'
    : `資料來源：${item.source || '新北市 Open Data（系統快照）'}`
  wrapper.appendChild(source)

  const observedTime = document.createElement('span')
  observedTime.textContent = isGooglePlace
    ? '即時空位：Google Places 未提供'
    : `官方逐筆觀測：${item.sourceObservedAt ? formatDateTime(item.sourceObservedAt) : '未提供可信時間'}`
  wrapper.appendChild(observedTime)

  const systemTime = document.createElement('span')
  systemTime.textContent = `系統取得：${formatDateTime(item.systemCollectedAt)}`
  wrapper.appendChild(systemTime)

  const eligibility = document.createElement('span')
  eligibility.textContent = isGooglePlace
    ? '一般汽車適用性：Google Places 未提供'
    : `一般汽車適用性：${formatEligibility(item.carEligibleStatus)}`
  wrapper.appendChild(eligibility)

  if (item.qualityStatus && !isGooglePlace) {
    const quality = document.createElement('span')
    quality.textContent = `資料品質：${item.qualityStatus}`
    wrapper.appendChild(quality)
  }

  const message = document.createElement('span')
  message.className = 'marker-info-message'
  message.textContent = item.message || '目前觀測不代表抵達時仍有位。'
  wrapper.appendChild(message)

  if (hasExternalMapAction(item)) {
    const route = document.createElement('a')
    route.className = 'marker-route-link'
    route.href = buildExternalRouteUrl(item)
    route.target = '_blank'
    route.rel = 'noopener noreferrer'
    route.setAttribute('aria-label', getExternalMapActionDescription(item))
    route.title = getExternalMapActionDescription(item)
    route.textContent = getExternalMapActionLabel(item)
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
  automaticPlanningEnabled = true
  selectedDestination.value = { ...defaultDestination }
  focusMapAtGroundDistance(defaultDestination, 50)
  scheduleAutomaticParkingQuery({ force: true })
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
  if (!hasNavigationCoordinates(item)) return getGoogleMapsUri(item)

  const latitude = item.navigationLatitude ?? item.latitude
  const longitude = item.navigationLongitude ?? item.longitude
  const parameters = new URLSearchParams({
    api: '1',
    destination: `${latitude},${longitude}`,
    travelmode: 'driving',
  })
  if (item.googlePlaceId && item.navigationLatitude == null && item.navigationLongitude == null) {
    parameters.set('destination_place_id', item.googlePlaceId)
  }
  return `https://www.google.com/maps/dir/?${parameters.toString()}`
}

function openExternalRoute(item) {
  if (!hasExternalMapAction(item)) return
  window.open(buildExternalRouteUrl(item), '_blank', 'noopener,noreferrer')
}

function hasNavigationCoordinates(item) {
  if (item?.navigationAvailable === false) return false
  const latitude = Number(item?.navigationLatitude ?? item?.latitude)
  const longitude = Number(item?.navigationLongitude ?? item?.longitude)
  return Number.isFinite(latitude) && Math.abs(latitude) <= 90
    && Number.isFinite(longitude) && Math.abs(longitude) <= 180
}

function getGoogleMapsUri(item) {
  if (item?.dataOrigin !== 'GOOGLE_PLACES' || typeof item.googleMapsUri !== 'string') return null

  try {
    const uri = new URL(item.googleMapsUri)
    const isGoogleMapsHost = uri.hostname === 'maps.google.com'
      || ((uri.hostname === 'www.google.com' || uri.hostname === 'google.com')
        && uri.pathname.startsWith('/maps/'))
    return uri.protocol === 'https:' && isGoogleMapsHost ? uri.toString() : null
  } catch {
    return null
  }
}

function hasExternalMapAction(item) {
  return hasNavigationCoordinates(item) || Boolean(getGoogleMapsUri(item))
}

function getExternalMapActionLabel(item) {
  return hasNavigationCoordinates(item) ? '導航' : '查看地圖'
}

function getExternalMapActionDescription(item) {
  const action = hasNavigationCoordinates(item) ? '使用 Google Maps 導航至' : '在 Google Maps 查看'
  return `${action}${item?.label ?? '停車地點'}`
}

function getSourceTimingLabel(item) {
  if (item.dataOrigin === 'GOOGLE_PLACES') return 'Google Places 地點資料 · 不含即時空位'
  return `官方觀測 ${item.sourceObservedAt ? formatDateTime(item.sourceObservedAt) : '未提供'}`
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
  <main class="app-shell" :class="`view-${activeNavigation}`">
    <header class="top-bar" aria-labelledby="app-title">
      <div class="brand-lockup">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 40"><path d="M16 1.5C7.9 1.5 1.5 7.8 1.5 15.7c0 10.6 14.5 22.8 14.5 22.8s14.5-12.2 14.5-22.8C30.5 7.8 24.1 1.5 16 1.5Z"/><circle cx="16" cy="15.5" r="5.1"/></svg>
        </span>
        <h1 id="app-title">找車位</h1>
      </div>
      <div class="header-tools">
        <span class="header-caption">官方觀測 · 預測尚未驗證</span>
      </div>
    </header>

    <section class="planner-panel" :class="{ 'filter-panel-open': activeMapPanel === 'layers' }" aria-label="目的地與地圖篩選">
      <div class="destination-field search-field">
        <template v-if="hasGoogleMapsKey">
          <div ref="destinationAutocompleteHost" class="autocomplete-host"></div>
        </template>
        <template v-else>
          <svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="7.2"/><path d="m16.2 16.2 5 5"/></svg>
          <div class="places-unavailable" role="status"><small>地點搜尋未設定</small></div>
        </template>
        <button class="search-reset" type="button" aria-label="回到預設目的地" @click="useDefaultDestination">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>
        </button>
      </div>
      <p v-if="googleMapsLoadError" class="places-error" role="alert">{{ googleMapsLoadError }}</p>
      <p v-else-if="destinationPlaceError" class="places-error" role="alert">{{ destinationPlaceError }}</p>
      <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
      <p v-if="planningMessage" class="refresh-message" role="status">{{ planningMessage }}</p>
      <p v-if="refreshMessage" class="refresh-message" role="status">{{ refreshMessage }}</p>

      <nav class="quick-filters" aria-label="地圖快速篩選">
        <button class="filter-chip location-chip" type="button" :disabled="locating" @click="useCurrentLocation">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7.5"/><circle cx="12" cy="12" r="2.3"/><path d="M12 1.8v3M12 19.2v3M1.8 12h3M19.2 12h3"/></svg>
          <span>{{ locating ? '定位中' : '我的位置' }}</span><span class="chip-chevron" aria-hidden="true"></span>
        </button>
        <button class="filter-chip" :class="{ selected: activeParkingTypeFilter !== 'ALL' }" type="button" :aria-pressed="activeParkingTypeFilter !== 'ALL'" @click="activeParkingTypeFilter = activeParkingTypeFilter === 'ALL' ? 'OFF_STREET' : activeParkingTypeFilter === 'OFF_STREET' ? 'ROADSIDE' : 'ALL'">
          <span class="parking-chip-icon">P</span>
          <span>{{ activeParkingTypeFilter === 'ALL' ? '全部停車場' : activeParkingTypeFilter === 'OFF_STREET' ? '路外停車場' : '路邊格位' }}</span><span class="chip-chevron" aria-hidden="true"></span>
        </button>
        <button class="filter-chip more-chip" :class="{ selected: activeMapPanel === 'layers' }" type="button" aria-controls="parking-filter-panel" :aria-expanded="activeMapPanel === 'layers'" @click="toggleMapPanel('layers')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h8m4 0h4M4 17h3m4 0h9"/><circle cx="14" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg><span>預測車位</span><span class="chip-chevron" aria-hidden="true"></span>
        </button>
      </nav>

      <section id="parking-filter-panel" v-show="activeMapPanel === 'layers'" class="map-control-panel filter-popover" aria-label="其他搜尋與狀態篩選">
        <header class="map-control-heading"><strong>搜尋與篩選</strong><button class="panel-close" type="button" @click="activeMapPanel = ''">完成</button></header>
        <div class="advanced-planning-grid">
          <div class="field-group origin-field">
            <label>出發地</label>
            <div v-if="hasGoogleMapsKey" ref="originAutocompleteHost" class="autocomplete-host origin-autocomplete"></div>
            <p v-else class="places-help" role="status">請設定 Google Maps browser key 後使用地點搜尋。</p>
            <p v-if="originPlaceError" class="places-error" role="alert">{{ originPlaceError }}</p>
            <span class="origin-current-label">目前出發地：{{ origin.label }}</span>
          </div>
          <div class="field-group departure-field">
            <label>出發時間</label>
            <span class="departure-now" role="status">立即</span>
          </div>
          <fieldset class="map-filter-group">
            <legend>資料類型</legend>
            <label class="map-filter-option map-filter-option-layer"><input v-model="mapFilters.layers.roadside" type="checkbox"><span>路邊格位</span></label>
            <label class="map-filter-option map-filter-option-layer"><input v-model="mapFilters.layers.offStreet" type="checkbox"><span>路外停車場</span></label>
          </fieldset>
          <fieldset class="map-filter-group status-filter-group">
            <legend>車位狀態</legend>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.AVAILABLE" type="checkbox"><i class="status-swatch available" aria-hidden="true"></i><span>有位</span><small>{{ mapMarkerCounts.AVAILABLE }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.OCCUPIED" type="checkbox"><i class="status-swatch occupied" aria-hidden="true"></i><span>已占用</span><small>{{ mapMarkerCounts.OCCUPIED }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.FULL" type="checkbox"><i class="status-swatch full" aria-hidden="true"></i><span>已滿</span><small>{{ mapMarkerCounts.FULL }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.UNKNOWN" type="checkbox"><i class="status-swatch unknown" aria-hidden="true"></i><span>未知／無即時資料</span><small>{{ mapMarkerCounts.UNKNOWN }}</small></label>
            <label class="map-filter-option"><input v-model="mapFilters.statuses.STALE" type="checkbox"><i class="status-swatch stale" aria-hidden="true"></i><span>資料過期</span><small>{{ mapMarkerCounts.STALE }}</small></label>
          </fieldset>
        </div>
      </section>
    </section>

    <section
      ref="workspaceElement"
      class="workspace"
      :class="[`mobile-sheet-${mobileSheetSnap}`, { 'sheet-dragging': mobileSheetDragging }]"
      :style="mobileSheetMapHeight === null ? undefined : { '--mobile-map-height': `${mobileSheetMapHeight}px` }"
      aria-label="地圖與停車資訊"
    >
      <section ref="mapStageElement" class="map-stage" aria-label="停車地圖">
        <div v-if="hasGoogleMapsKey" ref="mapElement" class="google-map" aria-label="Google 地圖"></div>
        <div v-if="!mapReady" class="map-fallback" :class="{ overlay: hasGoogleMapsKey }">
          <div class="fallback-circle">{{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }}m</div>
          <strong>{{ mapStatus }}</strong>
          <span>地圖上只會顯示查詢後取得的停車資料。</span>
        </div>
        <div class="map-toolbar">
          <div class="map-place-pin"><span class="destination-dot" aria-hidden="true"></span><div><strong>{{ resultDestination.label }}</strong><span>目的地周邊 {{ queryResult?.area?.radiusMeters ?? DEFAULT_RADIUS_METERS }} 公尺</span></div></div>
          <button class="map-return-button" type="button" aria-label="回到目的地" @click="focusDestination">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 1v3m0 16v3M1 12h3m16 0h3"/></svg>
          </button>
        </div>
        <section v-if="activeMapPanel === 'legend'" class="map-control-panel map-legend-panel" aria-label="地圖圖例">
          <header class="map-control-heading"><strong>停車格圖例</strong><button class="panel-close" type="button" @click="activeMapPanel = ''">關閉</button></header>
          <div class="legend-row"><i class="status-swatch available" aria-hidden="true"></i><span>有位 · 官方目前觀測</span></div>
          <div class="legend-row"><i class="status-swatch occupied" aria-hidden="true"></i><span>已占用或已滿</span></div>
          <div class="legend-row"><i class="status-swatch unknown" aria-hidden="true"></i><span>未知或沒有即時資料</span></div>
          <div class="legend-row"><i class="status-swatch stale" aria-hidden="true"></i><span>觀測資料已過期</span></div>
          <p>路邊位置依官方目錄座標顯示，尚未完成實地精度校驗。群聚數代表格位筆數，不代表空位數。</p>
        </section>
        <div class="map-legend" aria-label="停車狀態圖例">
          <span><i class="status-swatch available" aria-hidden="true"></i>有位</span>
          <span><i class="status-swatch occupied" aria-hidden="true"></i>已占用</span>
          <span><i class="status-swatch unknown" aria-hidden="true"></i>未知</span>
        </div>
        <div v-if="mapDataError || mapViewportSupportMessage" class="map-summary" aria-live="polite">
          <span class="status-dot" :class="{ ready: mapReady }" aria-hidden="true"></span>
          <span>{{ mapDataLoading ? '停車資料更新中' : queryResult ? `${visibleMapItems.length} 個官方觀測符合篩選` : '選取目的地後會自動查詢附近車位' }}</span>
          <span v-if="mapData?.generatedAt" class="map-read-time">系統取得 {{ formatDateTime(mapData.generatedAt) }}</span>
          <span v-if="mapViewportSupportMessage" class="map-support-message">{{ mapViewportSupportMessage }}</span>
          <span v-if="mapDataError" class="map-data-error">{{ mapDataError }}</span>
        </div>
      </section>

      <aside class="result-panel" aria-label="選取停車位與附近結果">
        <div v-if="activeNavigation === 'settings'" class="settings-view">
          <div class="panel-view-heading"><span class="panel-eyebrow">PARKING GUIDE</span><h2>使用設定與資料說明</h2></div>
          <article class="result-card"><strong>資料呈現方式</strong><span>官方目前觀測、資料品質與抵達時間預測分開顯示。沒有通過驗證的預測會標示「資料不足」。</span></article>
          <article class="result-card"><strong>地圖位置</strong><span>格位位置依官方目錄座標呈現，目前尚未完成實地精度校驗。</span></article>
          <article class="result-card"><strong>系統狀態</strong><span>地圖：{{ mapReady ? '已載入' : '尚未載入' }}</span><span>Google Places：{{ hasGoogleMapsKey ? '已設定' : '未設定' }}</span><span>API：{{ apiBaseUrlLabel }}</span></article>
          <article class="result-card muted"><strong>更新方式</strong><span>建立查詢後顯示附近資料；「更新車位」只更新目前官方觀測，不會重新估算 ETA。</span></article>
        </div>

        <div v-else-if="activeNavigation === 'favorites'" class="favorites-view">
          <div class="panel-view-heading"><span class="panel-eyebrow">SAVED PLACES</span><h2>我的最愛</h2><span>{{ favoriteMapItems.length }} 個已收藏項目</span></div>
          <article v-for="item in favoriteMapItems" :key="item.id" class="parking-item" :class="{ selected: selectedItemId === item.id }">
            <button class="parking-select" type="button" @click="selectResultItem(item.id)"><span class="spot-badge" :class="item.availabilityStatus?.toLowerCase()">{{ getSpotBadge(item) }}</span><span class="parking-item-copy"><strong>{{ item.label }}</strong><small>{{ item.distanceMeters }} 公尺 · {{ item.currentValue }}</small></span><span class="parking-row-chevron" aria-hidden="true">›</span></button>
          </article>
          <div v-if="favoriteMapItems.length === 0" class="empty-results"><span class="empty-icon">♡</span><strong>還沒有收藏停車位</strong><p>查詢附近車位後，點選車位詳情旁的愛心即可收藏。</p><button class="secondary-button" type="button" @click="activeNavigation = 'map'">回到地圖</button></div>
        </div>

        <div v-else class="map-results-view">
          <button
            class="sheet-handle"
            type="button"
            :aria-label="mobileSheetSnap === 'expanded' ? '收合資訊欄' : mobileSheetSnap === 'peek' ? '展開資訊欄' : '調整資訊欄高度'"
            :aria-expanded="mobileSheetSnap === 'expanded'"
            @pointerdown="startMobileSheetDrag"
            @pointermove="moveMobileSheetDrag"
            @pointerup="finishMobileSheetDrag"
            @pointercancel="finishMobileSheetDrag"
            @click="toggleMobileSheet"
          ><span></span></button>
          <article class="selected-spot-card">
            <header class="selected-spot-heading">
              <div class="selected-spot-mark" :class="selectedMapItem?.type === 'OFF_STREET' ? 'off-street' : selectedMapItem ? 'roadside' : 'destination'">{{ getSpotBadge(selectedMapItem) }}</div>
              <div class="selected-spot-title">
                <strong>{{ selectedMapItem?.label ?? resultDestination.label }}</strong>
                <span>{{ selectedMapItem ? `${selectedMapItem.type === 'OFF_STREET' ? '路外停車場' : '路邊格位'} · 距離約 ${selectedMapItem.distanceMeters} 公尺` : queryResult ? '目的地附近停車資訊' : '目的地 · 尚未查詢停車資料' }}</span>
              </div>
              <div class="selected-spot-actions">
                <button v-if="selectedMapItem" class="icon-button" type="button" :aria-label="favoriteItemIds.includes(selectedMapItem.id) ? '取消收藏' : '收藏停車位'" :aria-pressed="favoriteItemIds.includes(selectedMapItem.id)" @click="toggleFavorite(selectedMapItem.id)">
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.8 8.9c0 5.2-8.8 11.1-8.8 11.1S3.2 14.1 3.2 8.9a4.4 4.4 0 0 1 8.8-1 4.4 4.4 0 0 1 8.8 1Z"/></svg>
                </button>
                <button v-if="selectedMapItem" class="icon-button" type="button" aria-label="分享停車資訊" @click="shareSelectedItem">
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15V3m0 0L7.5 7.5M12 3l4.5 4.5"/><path d="M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/></svg>
                </button>
              </div>
            </header>
            <div class="selected-spot-status">
              <span class="selected-spot-status-label">{{ selectedMapItem?.dataOrigin === 'GOOGLE_PLACES' ? '空位資訊' : selectedMapItem?.type === 'OFF_STREET' ? '官方剩餘汽車位' : selectedMapItem ? '官方路邊格位狀態' : queryResult ? '官方路邊目前有位' : '停車狀態' }}</span>
              <strong :class="selectedMapItem?.availabilityStatus?.toLowerCase()">{{ selectedMapItem?.currentValue ?? (queryResult ? roadsideDisplaySummary.availableSpaces === null ? '未知' : `${roadsideDisplaySummary.availableSpaces} 格` : '尚未查詢') }}</strong>
            </div>
            <div class="selected-spot-outlook" role="group" aria-label="預計抵達與抵達時車位預測">
              <div class="selected-spot-outlook-item">
                <span class="outlook-label">預計抵達</span>
                <strong>{{ selectedEtaArrivalLabel }}</strong>
                <p>{{ selectedEtaDescription }}</p>
              </div>
              <div class="selected-spot-outlook-item prediction-outlook-item">
                <span class="outlook-label">抵達時車位預測</span>
                <strong :class="{ experimental: selectedOffStreetPrediction?.status === 'EXPERIMENTAL' }">{{ selectedPredictionValue }}</strong>
                <p>{{ selectedPredictionDescription }}</p>
              </div>
            </div>
            <div class="selected-spot-facts">
              <div><span class="fact-label"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 17a4 4 0 0 1 .4-8 5.4 5.4 0 0 1 10.3 1.2A3.5 3.5 0 1 1 18 17H7Z"/><path d="M12 12v6m0 0-2-2m2 2 2-2"/></svg>系統取得</span><strong>{{ selectedMapItem ? formatDateTime(selectedMapItem.systemCollectedAt) : mapData?.generatedAt ? formatDateTime(mapData.generatedAt) : '尚未取得' }}</strong></div>
              <div class="fee-fact"><span class="fact-label"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M15.5 8.5c-.8-.7-1.7-1-3-1-1.4 0-2.5.8-2.5 2s1.1 1.8 2.5 2.2 2.5.9 2.5 2.3-1.1 2.3-2.7 2.3c-1.2 0-2.4-.4-3.2-1.2M12 6v12"/></svg>收費</span><strong>{{ selectedMapItem?.feeDescription || selectedMapItem?.feeStatus || '來源未提供' }}</strong><a v-if="selectedMapItem && selectedMapItem.type !== 'OFF_STREET' && selectedMapItem.dataOrigin !== 'GOOGLE_PLACES'" class="fee-source-link" href="https://parkweb.traffic.ntpc.gov.tw/route.aspx" target="_blank" rel="noopener noreferrer">查看新北市官方路段費率</a></div>
            </div>
            <a v-if="selectedMapItem && hasExternalMapAction(selectedMapItem)" class="route-link selected-route-link" :href="buildExternalRouteUrl(selectedMapItem)" :aria-label="getExternalMapActionDescription(selectedMapItem)" :title="getExternalMapActionDescription(selectedMapItem)" target="_blank" rel="noopener noreferrer">{{ getExternalMapActionLabel(selectedMapItem) }}</a>
          </article>

          <section class="nearby-section" aria-labelledby="nearby-title">
            <header class="nearby-heading"><div><h2 id="nearby-title">{{ activeNavigation === 'list' ? '附近停車結果' : '附近停車位與停車場' }}</h2><span>{{ nearbyListSummary }}</span></div><span class="nearby-note">{{ queryResult ? '目前觀測' : '' }}</span><button v-if="queryResult" class="refresh-button refresh-compact" type="button" :disabled="loading" @click="refreshParkingAvailability()">{{ loading ? '更新中' : '更新車位' }}</button></header>
            <template v-if="queryResult && displayedMapItems.length">
              <article v-for="item in displayedMapItems" :key="item.id" class="parking-item" :class="{ selected: selectedItemId === item.id }">
                <button class="parking-select" type="button" @click="selectResultItem(item.id)">
                  <span class="spot-badge" :class="item.availabilityStatus?.toLowerCase()">{{ getSpotBadge(item) }}</span>
                  <span class="parking-item-copy"><strong>{{ item.label }}</strong><small>{{ item.distanceMeters }} 公尺 <span class="item-type">{{ item.type === 'OFF_STREET' ? '停車場' : '路邊格位' }}</span></small></span>
                  <span class="parking-row-status"><i class="status-swatch" :class="item.availabilityStatus?.toLowerCase()" aria-hidden="true"></i>{{ item.currentValue }}</span>
                  <span class="parking-row-fee">{{ item.feeDescription || item.feeStatus || '—' }}</span>
                  <span class="parking-row-chevron" aria-hidden="true">›</span>
                </button>
                <a v-if="hasExternalMapAction(item)" class="parking-route-button" :href="buildExternalRouteUrl(item)" :aria-label="getExternalMapActionDescription(item)" :title="getExternalMapActionDescription(item)" target="_blank" rel="noopener noreferrer" @click.stop>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9"/><path d="M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5"/></svg><span>{{ getExternalMapActionLabel(item) }}</span>
                </a>
                <span class="parking-item-times">{{ getSourceTimingLabel(item) }} · 系統取得 {{ formatDateTime(item.systemCollectedAt) }}</span>
              </article>
            </template>
            <div v-else-if="!queryResult" class="empty-results compact-empty">
              <strong>查詢目的地附近車位</strong><p>不會顯示合成車位數。選取目的地後，系統會自動讀取附近已審查的停車資料。</p>
            </div>
            <div v-else class="empty-results compact-empty"><strong>{{ resultItems.length ? '目前篩選沒有符合結果' : '附近暫無可顯示資料' }}</strong><p>{{ resultItems.length ? '請調整車位類型或狀態篩選。' : '沒有資料不代表附近沒有停車位。' }}</p></div>
            <p v-if="queryResult" class="coverage-note google-places-note">{{ googlePlacesMessage }}</p>
            <p v-if="queryResult && queryResult.area?.coverageStatus !== 'COMPLETE'" class="coverage-note">{{ queryResult.area?.coverageStatus === 'NO_CATALOG_RESULTS' ? '此區尚無已審查的停車目錄。' : '此區目錄僅部分收錄；未顯示不代表沒有車位。' }}</p>
          </section>

          <section v-if="activeNavigation === 'list' && queryResult" class="data-source-details">
            <article class="result-card"><strong>路邊區域</strong><span>{{ roadsideDisplaySummary.message }}</span><span>抵達時預測：{{ predictionMessage }}</span></article>
            <article class="result-card"><strong>路外停車場</strong><span>{{ offStreetMessage }}</span><span>{{ etaMessage }}</span></article>
            <article class="result-card muted acceptance-log"><strong>人工驗收事件</strong><span>僅記錄狀態碼與 payload 欄位摘要，不顯示金鑰或完整 API 回應。</span><ol v-if="acceptanceEvents.length"><li v-for="(event, index) in acceptanceEvents" :key="`${event.occurredAt}-${event.type}-${index}`"><span>{{ formatDateTime(event.occurredAt) }} · {{ event.type }}</span><strong>{{ event.message }}</strong><code>{{ JSON.stringify(event.details) }}</code></li></ol><span v-else>尚無事件。</span></article>
          </section>
        </div>
      </aside>
    </section>

    <nav class="bottom-navigation" aria-label="主要導覽">
      <button type="button" :class="{ active: activeNavigation === 'map' }" :aria-current="activeNavigation === 'map' ? 'page' : undefined" @click="activeNavigation = 'map'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3V6Z"/><path d="M9 3v15m6-12v15"/></svg><span>地圖</span>
      </button>
      <button type="button" :class="{ active: activeNavigation === 'list' }" :aria-current="activeNavigation === 'list' ? 'page' : undefined" @click="activeNavigation = 'list'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5h12M9 12h12M9 19h12"/><circle cx="4" cy="5" r=".8"/><circle cx="4" cy="12" r=".8"/><circle cx="4" cy="19" r=".8"/></svg><span>清單</span>
      </button>
      <button type="button" :class="{ active: activeNavigation === 'favorites' }" :aria-current="activeNavigation === 'favorites' ? 'page' : undefined" @click="activeNavigation = 'favorites'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9L12 3Z"/></svg><span>我的最愛</span>
      </button>
      <button type="button" :class="{ active: activeNavigation === 'settings' }" :aria-current="activeNavigation === 'settings' ? 'page' : undefined" @click="activeNavigation = 'settings'">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg><span>設定</span>
      </button>
    </nav>
  </main>
</template>
