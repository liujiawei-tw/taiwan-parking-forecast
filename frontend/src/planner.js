export const AUTO_REFRESH_SOURCE_STATUSES = new Set(['STALE', 'NO_REALTIME_SNAPSHOT'])
export const PLACEHOLDER_GOOGLE_MAPS_KEY = 'replace-with-local-browser-key'

// Taipei MRT O18 station address is listed by Taipei City DORTS; map point is
// the OpenStreetMap public-transport station node 3812564402.
export const DEFAULT_ORIGIN = Object.freeze({
  label: '捷運新莊站（O18）',
  address: '新北市新莊區中正路138號',
  latitude: 25.03633,
  longitude: 121.45336,
})

export function roadsideCountLabel(status, count) {
  if (!status) return '路邊尚未查詢'
  if (status !== 'READY') return '路邊目前有位：未知'
  return `路邊目前有位格位 ${count} 個`
}

export function hasUsableGoogleMapsKey(apiKey) {
  return Boolean(apiKey && apiKey !== PLACEHOLDER_GOOGLE_MAPS_KEY)
}

export function locationErrorMessage(error) {
  if (error?.code === 1) return '定位權限未開啟，請改用地點搜尋選擇起點。'
  if (error?.code === 2) return '目前無法取得位置，請改用地點搜尋選擇起點。'
  if (error?.code === 3) return '定位逾時，請改用地點搜尋選擇起點。'
  return '無法取得目前位置，請改用地點搜尋選擇起點。'
}

export function buildRequestToken(cryptoObject = globalThis.crypto, now = Date.now, random = Math.random) {
  if (cryptoObject?.randomUUID) return `web-${cryptoObject.randomUUID()}`
  return `web-${now()}-${random().toString(16).slice(2)}`
}

function inputError(code, message) {
  return Object.assign(new Error(message), { code })
}

function normalizeLocation(location, name) {
  const label = typeof location?.label === 'string' ? location.label.trim() : ''
  if (!label) {
    throw inputError('INVALID_REQUEST', `請確認${name}名稱完整後再試。`)
  }

  const latitudeInput = location?.latitude
  const longitudeInput = location?.longitude
  const latitude = Number(latitudeInput)
  const longitude = Number(longitudeInput)
  if (latitudeInput === null || latitudeInput === undefined || String(latitudeInput).trim() === ''
      || longitudeInput === null || longitudeInput === undefined || String(longitudeInput).trim() === ''
      || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
      || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw inputError('INVALID_COORDINATE', `請重新選取有效的${name}位置。`)
  }

  return { label, latitude, longitude }
}

export function buildDeparture(departureType, scheduledDepartureLocal, now = Date.now()) {
  if (departureType === 'NOW') return { type: 'NOW' }
  if (departureType !== 'SCHEDULED') {
    throw inputError('INVALID_DEPARTURE_TIME', '出發時間類型無效，請重新選擇。')
  }
  if (typeof scheduledDepartureLocal !== 'string' || !scheduledDepartureLocal.trim()) {
    throw inputError('INVALID_DEPARTURE_TIME', '請選擇指定出發時間，或改用「立即」。')
  }

  const departureAt = new Date(scheduledDepartureLocal)
  if (!Number.isFinite(departureAt.getTime()) || departureAt.getTime() <= now) {
    throw inputError('INVALID_DEPARTURE_TIME', '指定出發時間無效或已過去，請選擇未來時間。')
  }

  return {
    type: 'SCHEDULED',
    departureAt: departureAt.toISOString(),
  }
}

export function buildParkingQueryPayload({
  requestToken,
  origin,
  destination,
  departureType,
  scheduledDepartureLocal,
}) {
  if (typeof requestToken !== 'string' || !requestToken.trim()) {
    throw inputError('INVALID_REQUEST', '查詢資料不完整，請重新規劃。')
  }

  return {
    requestToken: requestToken.trim(),
    origin: normalizeLocation(origin, '起點'),
    destination: normalizeLocation(destination, '目的地'),
    departure: buildDeparture(departureType, scheduledDepartureLocal),
  }
}

export function getAutoRefreshEligibleSources(queryResult, refreshInProgress) {
  if (!queryResult || refreshInProgress) return []
  return [
    ['roadside', queryResult.roadside?.status],
    ['offStreet', queryResult.offStreet?.status],
  ]
    .filter(([, status]) => AUTO_REFRESH_SOURCE_STATUSES.has(status))
    .map(([source]) => source)
}

export function buildAutoRefreshAttemptKey(queryResult) {
  if (!queryResult?.query?.queryId) return ''
  const key = [
    queryResult.query.queryId,
    queryResult.roadside?.status,
    queryResult.offStreet?.status,
  ]
  const sourceVersions = [
    queryResult.roadside?.snapshotId,
    queryResult.roadside?.systemCollectedAt,
    queryResult.offStreet?.snapshotId,
    queryResult.offStreet?.systemCollectedAt,
  ]
  if (sourceVersions.some(Boolean)) key.push(...sourceVersions)
  return key.join(':')
}

export function shouldAutoRefresh({
  queryResult,
  loading = false,
  visible = true,
  refreshInProgress = false,
  lastAttemptKey = '',
}) {
  if (!queryResult?.query?.queryId || !visible || loading) {
    return { shouldRefresh: false, sources: [], attemptKey: '' }
  }

  const sources = getAutoRefreshEligibleSources(queryResult, refreshInProgress)
  if (sources.length === 0) {
    return { shouldRefresh: false, sources, attemptKey: '' }
  }

  const attemptKey = buildAutoRefreshAttemptKey(queryResult)
  return {
    shouldRefresh: attemptKey !== lastAttemptKey,
    sources,
    attemptKey,
  }
}
