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

export function snapshotPlanningInputs({
  origin,
  destination,
  departureType,
  scheduledDepartureLocal,
}) {
  return {
    origin: snapshotDestination(origin),
    destination: snapshotDestination(destination),
    departureType: String(departureType ?? ''),
    scheduledDepartureLocal: String(scheduledDepartureLocal ?? ''),
  }
}

export function hasPlanningInputsChanged(next, previous) {
  return Boolean(previous) && (
    hasDestinationChanged(next.origin, previous.origin)
      || hasDestinationChanged(next.destination, previous.destination)
      || next.departureType !== previous.departureType
      || next.scheduledDepartureLocal !== previous.scheduledDepartureLocal
  )
}

export function createQueryRequestGuard() {
  let generation = 0
  let requestSequence = 0
  const isGenerationCurrent = (context, planningInputs) => Boolean(context)
    && context.generation === generation
    && !hasPlanningInputsChanged(snapshotPlanningInputs(planningInputs), context.planningInputs)

  return {
    start(planningInputs) {
      generation += 1
      requestSequence += 1
      return {
        generation,
        requestSequence,
        planningInputs: snapshotPlanningInputs(planningInputs),
        queryId: null,
      }
    },
    capture(planningInputs, queryId = null) {
      requestSequence += 1
      return {
        generation,
        requestSequence,
        planningInputs: snapshotPlanningInputs(planningInputs),
        queryId,
      }
    },
    invalidate() {
      generation += 1
      requestSequence += 1
    },
    isGenerationCurrent,
    isCurrent(context, planningInputs, queryId = null) {
      return isGenerationCurrent(context, planningInputs)
        && context.requestSequence === requestSequence
        && (context.queryId === null || context.queryId === queryId)
    },
  }
}

export function shouldApplyQueryResult(current, incoming) {
  const currentId = current?.query?.queryId
  const incomingId = incoming?.query?.queryId
  if (!currentId || currentId !== incomingId) return true

  const currentUpdatedAt = Date.parse(current?.query?.updatedAt ?? '')
  const incomingUpdatedAt = Date.parse(incoming?.query?.updatedAt ?? '')
  if (Number.isFinite(currentUpdatedAt) && !Number.isFinite(incomingUpdatedAt)) return false
  if (Number.isFinite(currentUpdatedAt) && Number.isFinite(incomingUpdatedAt)) {
    return incomingUpdatedAt >= currentUpdatedAt
  }
  return true
}

export function createLoadingRequestOwner() {
  let nextRequestId = 0
  let currentRequestId = 0

  return {
    acquire() {
      currentRequestId = ++nextRequestId
      return currentRequestId
    },
    release(requestId) {
      if (currentRequestId !== requestId) return false
      currentRequestId = 0
      return true
    },
    invalidate() {
      currentRequestId = 0
      nextRequestId += 1
    },
    isOwner(requestId) {
      return currentRequestId === requestId
    },
  }
}

export function getRoadsideDisplaySummary(
  roadside,
  mapSource,
  nowMs,
  maxAgeMs = MAP_DISPLAY_FRESHNESS_MS,
) {
  const sourceTimes = [roadside?.systemCollectedAt, mapSource?.systemCollectedAt]
    .map((timestamp) => Date.parse(timestamp ?? ''))
    .filter(Number.isFinite)
  const expiredByAge = sourceTimes.some((sourceTimeMs) => nowMs - sourceTimeMs > maxAgeMs)
  const sourceExpired = isMapSourceDisplayStale(roadside, nowMs, maxAgeMs)
    || (mapSource && isMapSourceDisplayStale({
      status: mapSource.status,
      systemCollectedAt: mapSource.systemCollectedAt,
    }, nowMs, maxAgeMs))
  const isStale = Boolean(sourceExpired)
  const canShowCount = !isStale && ['READY', 'PARTIAL'].includes(roadside?.status)

  return {
    isStale,
    availableSpaces: canShowCount ? roadside?.currentlyAvailableSpaces ?? null : null,
    freshnessMessage: isStale
      ? expiredByAge || roadside?.status === 'STALE' || mapSource?.status === 'STALE'
        ? '路邊資料已過期，目前可用數未知。'
        : '路邊資料時效無法確認，目前可用數未知。'
      : '',
  }
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
