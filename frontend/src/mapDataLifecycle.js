// Local display deadline only; official-source refresh remains governed by the backend coordinator and cooldown.
export const MAP_DISPLAY_FRESHNESS_MS = 5 * 60 * 1000

const AVAILABILITY_STATES_WITH_CURRENT_DATA = new Set(['AVAILABLE', 'OCCUPIED', 'FULL'])
const AUTO_REFRESHABLE_SOURCE_STATES = new Set(['READY', 'PARTIAL'])

export function applyMapDisplayFreshness(
  items,
  nowMs,
  sourceCollectedAt = {},
  maxAgeMs = MAP_DISPLAY_FRESHNESS_MS,
) {
  return items.map((item) => {
    if (!AVAILABILITY_STATES_WITH_CURRENT_DATA.has(item.availabilityStatus)) return item

    const sourceTime = item.systemCollectedAt
      ?? sourceCollectedAt[item.type === 'ROADSIDE' ? 'roadside' : 'offStreet']
    const collectedAtMs = Date.parse(sourceTime ?? '')
    if (!Number.isFinite(collectedAtMs)) {
      return {
        ...item,
        availabilityStatus: 'UNKNOWN',
        availableCarSpaces: null,
        availableSpot: null,
        navigationAvailable: false,
        message: '缺少可確認的系統取得時間，不顯示目前車位狀態。',
      }
    }

    if (nowMs - collectedAtMs <= maxAgeMs) return item

    return {
      ...item,
      availabilityStatus: 'STALE',
      availableCarSpaces: null,
      availableSpot: null,
      navigationAvailable: false,
      message: '系統快照已超過 5 分鐘展示時效，不再顯示為目前有位。',
    }
  })
}

export function isMapSourceDisplayStale(source, nowMs, maxAgeMs = MAP_DISPLAY_FRESHNESS_MS) {
  if (!source) return false
  if (source.status === 'STALE') return true
  if (!AUTO_REFRESHABLE_SOURCE_STATES.has(source.status)) return false

  const collectedAtMs = Date.parse(source.systemCollectedAt ?? '')
  return !Number.isFinite(collectedAtMs) || nowMs - collectedAtMs > maxAgeMs
}

export function snapshotDestination(destination) {
  return {
    label: String(destination?.label ?? ''),
    latitude: Number(destination?.latitude),
    longitude: Number(destination?.longitude),
  }
}

export function hasDestinationChanged(next, previous) {
  return Boolean(previous) && (
    !Object.is(next.label, previous.label)
      || !Object.is(next.latitude, previous.latitude)
      || !Object.is(next.longitude, previous.longitude)
  )
}

export function createMapDataRequestCache() {
  let loadedKey = ''
  let inFlightKey = ''
  let failedKey = ''
  let retryAfterMs = 0

  return {
    get loadedKey() {
      return loadedKey
    },
    shouldLoad(key, force = false, nowMs = Date.now()) {
      if (!key || key === inFlightKey) return false
      if (force) return true
      if (key === failedKey) return nowMs >= retryAfterMs
      return key !== loadedKey
    },
    begin(key) {
      inFlightKey = key
    },
    complete(key) {
      loadedKey = key
      if (failedKey === key) {
        failedKey = ''
        retryAfterMs = 0
      }
      if (inFlightKey === key) inFlightKey = ''
    },
    fail(key, nextRetryAtMs = 0) {
      failedKey = key
      retryAfterMs = nextRetryAtMs
      if (inFlightKey === key) inFlightKey = ''
    },
    invalidate() {
      loadedKey = ''
      inFlightKey = ''
      failedKey = ''
      retryAfterMs = 0
    },
  }
}
