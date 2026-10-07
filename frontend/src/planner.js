export const AUTO_REFRESH_SOURCE_STATUSES = new Set(['STALE', 'NO_REALTIME_SNAPSHOT'])
export const PLACEHOLDER_GOOGLE_MAPS_KEY = 'replace-with-local-browser-key'

export function roadsideCountLabel(status, count) {
  if (!status) return '路邊尚未查詢'
  if (status !== 'READY') return '路邊目前有位：未知'
  return `路邊目前有位格位 ${count} 個`
}

export function hasUsableGoogleMapsKey(apiKey) {
  return Boolean(apiKey && apiKey !== PLACEHOLDER_GOOGLE_MAPS_KEY)
}

export function buildRequestToken(cryptoObject = globalThis.crypto, now = Date.now, random = Math.random) {
  if (cryptoObject?.randomUUID) return `web-${cryptoObject.randomUUID()}`
  return `web-${now()}-${random().toString(16).slice(2)}`
}

export function buildDeparture(departureType, scheduledDepartureLocal) {
  if (departureType === 'SCHEDULED' && scheduledDepartureLocal) {
    return {
      type: 'SCHEDULED',
      departureAt: new Date(scheduledDepartureLocal).toISOString(),
    }
  }
  return { type: 'NOW' }
}

export function buildParkingQueryPayload({
  requestToken,
  origin,
  destination,
  departureType,
  scheduledDepartureLocal,
}) {
  return {
    requestToken,
    origin: {
      label: origin.label,
      latitude: Number(origin.latitude),
      longitude: Number(origin.longitude),
    },
    destination: {
      label: destination.label,
      latitude: Number(destination.latitude),
      longitude: Number(destination.longitude),
    },
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
  return [
    queryResult.query.queryId,
    queryResult.roadside?.status,
    queryResult.offStreet?.status,
  ].join(':')
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
