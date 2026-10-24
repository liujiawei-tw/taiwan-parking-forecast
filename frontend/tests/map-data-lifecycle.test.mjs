import assert from 'node:assert/strict'
import {
  applyMapDisplayFreshness,
  createLoadingRequestOwner,
  createMapDataRequestCache,
  createQueryRequestGuard,
  getRoadsideDisplaySummary,
  hasDestinationChanged,
  hasPlanningInputsChanged,
  isMapSourceDisplayStale,
  MAP_DISPLAY_FRESHNESS_MS,
  shouldApplyQueryResult,
  snapshotPlanningInputs,
  snapshotDestination,
} from '../src/mapDataLifecycle.js'

const collectedAt = '2026-10-24T10:00:00.000Z'
const collectedAtMs = Date.parse(collectedAt)
const available = {
  id: 'spot-1',
  type: 'ROADSIDE',
  availabilityStatus: 'AVAILABLE',
  availableSpot: true,
  carEligibleStatus: 'CONFIRMED',
  navigationAvailable: true,
  systemCollectedAt: collectedAt,
}

const fresh = applyMapDisplayFreshness([available], collectedAtMs + MAP_DISPLAY_FRESHNESS_MS)
assert.equal(fresh[0].availabilityStatus, 'AVAILABLE', 'exact freshness boundary remains fresh like backend')
assert.equal(fresh[0].navigationAvailable, true)

const expired = applyMapDisplayFreshness([available], collectedAtMs + MAP_DISPLAY_FRESHNESS_MS + 1)[0]
assert.equal(expired.availabilityStatus, 'STALE')
assert.equal(expired.availableSpot, null)
assert.equal(expired.navigationAvailable, false)
assert.match(expired.message, /不再顯示為目前有位/)

const missingTime = applyMapDisplayFreshness([{
  ...available,
  systemCollectedAt: null,
}], collectedAtMs, { roadside: null })[0]
assert.equal(missingTime.availabilityStatus, 'UNKNOWN')
assert.equal(missingTime.navigationAvailable, false)

const source = { status: 'READY', systemCollectedAt: collectedAt, snapshotId: 42 }
assert.equal(isMapSourceDisplayStale(source, collectedAtMs + MAP_DISPLAY_FRESHNESS_MS), false)
assert.equal(isMapSourceDisplayStale(source, collectedAtMs + MAP_DISPLAY_FRESHNESS_MS + 1), true)
assert.equal(isMapSourceDisplayStale({ ...source, status: 'STALE' }, collectedAtMs), true)
assert.equal(isMapSourceDisplayStale(source, collectedAtMs), false, 'the source is fresh before the page is hidden')
assert.equal(
  isMapSourceDisplayStale(source, collectedAtMs + MAP_DISPLAY_FRESHNESS_MS + 1),
  true,
  'the same source is stale when the page becomes visible after its local freshness window',
)

const roadsideSummarySource = {
  status: 'READY',
  systemCollectedAt: collectedAt,
  currentlyAvailableSpaces: 8,
}
const freshRoadsideSummary = getRoadsideDisplaySummary(
  roadsideSummarySource,
  { status: 'READY', systemCollectedAt: collectedAt },
  collectedAtMs + MAP_DISPLAY_FRESHNESS_MS,
)
assert.equal(freshRoadsideSummary.availableSpaces, 8)
assert.equal(freshRoadsideSummary.isStale, false)
const staleRoadsideSummary = getRoadsideDisplaySummary(
  roadsideSummarySource,
  { status: 'READY', systemCollectedAt: collectedAt },
  collectedAtMs + MAP_DISPLAY_FRESHNESS_MS + 1,
)
assert.equal(staleRoadsideSummary.availableSpaces, null, 'expired area summaries must hide old available counts')
assert.match(staleRoadsideSummary.freshnessMessage, /目前可用數未知/)
const unknownRoadsideSummary = getRoadsideDisplaySummary({
  ...roadsideSummarySource,
  systemCollectedAt: null,
}, null, collectedAtMs)
assert.equal(unknownRoadsideSummary.availableSpaces, null, 'a count without a trustworthy time is not current')
assert.equal(unknownRoadsideSummary.isStale, true)

const destination = {
  label: '新莊體育館',
  latitude: 25.036,
  longitude: 121.4505,
}
const previousDestination = snapshotDestination(destination)
destination.latitude = 25.037
const nextDestination = snapshotDestination(destination)
assert.equal(hasDestinationChanged(nextDestination, previousDestination), true)
assert.equal(hasDestinationChanged(snapshotDestination(destination), nextDestination), false)

const planningInputs = {
  origin: { label: '捷運新莊站（O18）', latitude: 25.03633, longitude: 121.45336 },
  destination,
  departureType: 'NOW',
  scheduledDepartureLocal: '',
}
const previousPlanningInputs = snapshotPlanningInputs(planningInputs)
assert.equal(hasPlanningInputsChanged(snapshotPlanningInputs(planningInputs), previousPlanningInputs), false)
assert.equal(hasPlanningInputsChanged({
  ...previousPlanningInputs,
  origin: { ...previousPlanningInputs.origin, latitude: 25.04 },
}, previousPlanningInputs), true, 'changing origin invalidates the previous query and ETA')
assert.equal(hasPlanningInputsChanged({
  ...previousPlanningInputs,
  departureType: 'SCHEDULED',
}, previousPlanningInputs), true, 'changing departure type invalidates the previous query and ETA')
assert.equal(hasPlanningInputsChanged({
  ...previousPlanningInputs,
  scheduledDepartureLocal: '2026-10-24T15:00',
}, previousPlanningInputs), true, 'changing scheduled departure invalidates the previous query and ETA')

const postGuard = createQueryRequestGuard()
const postContext = postGuard.start(planningInputs)
let resolvePost
const pendingPost = new Promise((resolve) => { resolvePost = resolve })
const guardedPost = pendingPost.then((result) => postGuard.isCurrent(postContext, planningInputs) ? result : 'ignored')
planningInputs.origin.longitude = 121.454
assert.equal(postGuard.isCurrent(postContext, planningInputs), false, 'a changed origin invalidates an in-flight query request')
postGuard.invalidate()
resolvePost('query-for-old-destination')
assert.equal(await guardedPost, 'ignored', 'a POST response must be discarded after any planning input changes')

const responseGuard = createQueryRequestGuard()
let visibleQuery = { query: { queryId: 'query-1', updatedAt: '2026-10-24T10:00:00.000Z' } }
const oldGetContext = responseGuard.capture(planningInputs, 'query-1')
let resolveOldGet
const delayedOldGet = new Promise((resolve) => { resolveOldGet = resolve })
const guardedOldGet = delayedOldGet.then((result) => {
  if (!responseGuard.isCurrent(oldGetContext, planningInputs, 'query-1')
      || !shouldApplyQueryResult(visibleQuery, result)) return 'ignored'
  visibleQuery = result
  return 'applied'
})
const refreshContext = responseGuard.capture(planningInputs, 'query-1')
const refreshResult = { query: { queryId: 'query-1', updatedAt: '2026-10-24T10:01:00.000Z' } }
assert.equal(responseGuard.isCurrent(oldGetContext, planningInputs, 'query-1'), false, 'a refresh request supersedes an older GET for the same Query')
assert.equal(responseGuard.isCurrent(refreshContext, planningInputs, 'query-1'), true)
if (responseGuard.isCurrent(refreshContext, planningInputs, 'query-1')
    && shouldApplyQueryResult(visibleQuery, refreshResult)) visibleQuery = refreshResult
resolveOldGet({ query: { queryId: 'query-1', updatedAt: '2026-10-24T10:00:30.000Z' } })
assert.equal(await guardedOldGet, 'ignored', 'a delayed pre-refresh GET cannot overwrite the refresh response')
assert.equal(visibleQuery.query.updatedAt, refreshResult.query.updatedAt)

const currentContext = responseGuard.start(planningInputs)
assert.equal(responseGuard.isCurrent(currentContext, planningInputs), true)
const queryBoundContext = responseGuard.capture(planningInputs, 'query-1')
assert.equal(responseGuard.isCurrent(queryBoundContext, planningInputs, 'query-2'), false, 'responses for an outdated query ID are discarded')
assert.equal(shouldApplyQueryResult(visibleQuery, { query: { queryId: 'query-1', updatedAt: '2026-10-24T10:00:59.000Z' } }), false, 'an older query.updatedAt cannot replace the current result')

const loadingOwner = createLoadingRequestOwner()
const expiryGuard = createQueryRequestGuard()
const expiredRefreshContext = expiryGuard.capture(planningInputs, 'query-1')
const expiredRefreshLoadingId = loadingOwner.acquire()
let expiredRefreshQueryResult = { query: { queryId: 'query-1' } }
let expiredRefreshLoading = true
try {
  const error = Object.assign(new Error('query expired'), { code: 'QUERY_EXPIRED' })
  throw error
} catch (error) {
  if (expiryGuard.isCurrent(expiredRefreshContext, planningInputs, expiredRefreshQueryResult?.query?.queryId)
      && ['QUERY_EXPIRED', 'QUERY_NOT_FOUND'].includes(error.code)) {
    expiredRefreshQueryResult = null
  }
} finally {
  if (expiryGuard.isGenerationCurrent(expiredRefreshContext, planningInputs)
      && loadingOwner.release(expiredRefreshLoadingId)) {
    expiredRefreshLoading = false
  }
}
assert.equal(expiredRefreshQueryResult, null, 'QUERY_EXPIRED clears the current query result')
assert.equal(expiredRefreshLoading, false, 'the owning refresh releases loading after clearing its Query ID')

const oldLoadingId = loadingOwner.acquire()
const newLoadingId = loadingOwner.acquire()
assert.equal(loadingOwner.release(oldLoadingId), false, 'an older request cannot clear a newer request loading state')
assert.equal(loadingOwner.isOwner(newLoadingId), true)
assert.equal(loadingOwner.release(newLoadingId), true)

const cache = createMapDataRequestCache()
const requestKey = 'query-1:snapshot-1'
let simulatedNow = 1000
let requestCount = 0
async function loadMapData() {
  if (!cache.shouldLoad(requestKey, false, simulatedNow)) return 'cached'
  cache.begin(requestKey)
  requestCount++
  try {
    if (requestCount === 1) throw new Error('temporary GET failure')
    cache.complete(requestKey)
    return 'loaded'
  } catch (error) {
    cache.fail(requestKey, simulatedNow + 15000)
    throw error
  }
}

await assert.rejects(loadMapData(), /temporary GET failure/)
assert.equal(cache.shouldLoad(requestKey, false, simulatedNow + 14999), false, 'progress polling respects the retry cooldown')
simulatedNow += 15000
assert.equal(cache.shouldLoad(requestKey, false, simulatedNow), true, 'failed GET must become retryable after a short cooldown')
assert.equal(await loadMapData(), 'loaded')
assert.equal(await loadMapData(), 'cached')
assert.equal(requestCount, 2)
cache.begin(requestKey)
cache.fail(requestKey, simulatedNow + 15000)
assert.equal(cache.shouldLoad(requestKey, false, simulatedNow + 14999), false, 'failed refresh of an existing cache identity also observes cooldown')
simulatedNow += 15000
assert.equal(cache.shouldLoad(requestKey, false, simulatedNow), true, 'a failed forced GET cannot be hidden by the prior successful cache identity')
assert.equal(await loadMapData(), 'loaded')
assert.equal(requestCount, 3)
assert.equal(cache.shouldLoad(requestKey, true, simulatedNow), true, 'returning to a visible page can force a fresh map-data GET')

console.log('map data lifecycle tests passed')
