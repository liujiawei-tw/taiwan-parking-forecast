import assert from 'node:assert/strict'
import {
  applyMapDisplayFreshness,
  createMapDataRequestCache,
  createQueryRequestGuard,
  getRoadsideDisplaySummary,
  hasDestinationChanged,
  isMapSourceDisplayStale,
  MAP_DISPLAY_FRESHNESS_MS,
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

const requestGuard = createQueryRequestGuard()
const postContext = requestGuard.start(destination)
const getContext = requestGuard.capture(destination, 'query-1')
const refreshContext = requestGuard.capture(destination, 'query-1')
let resolvePost
const pendingPost = new Promise((resolve) => { resolvePost = resolve })
const guardedPost = pendingPost.then((result) => requestGuard.isCurrent(postContext, destination) ? result : 'ignored')
destination.longitude = 121.451
assert.equal(requestGuard.isCurrent(postContext, destination), false, 'the primitive destination snapshot detects a nested coordinate edit')
requestGuard.invalidate()
resolvePost('query-for-old-destination')
assert.equal(await guardedPost, 'ignored', 'a POST response must be discarded after the destination changes')
assert.equal(requestGuard.isCurrent(getContext, destination, 'query-1'), false, 'an in-flight GET must be discarded after destination invalidation')
assert.equal(requestGuard.isCurrent(refreshContext, destination, 'query-1'), false, 'an in-flight refresh must be discarded after destination invalidation')

const currentContext = requestGuard.start(destination)
assert.equal(requestGuard.isCurrent(currentContext, destination), true)
const queryBoundContext = requestGuard.capture(destination, 'query-1')
assert.equal(requestGuard.isCurrent(queryBoundContext, destination, 'query-2'), false, 'responses for an outdated query ID are discarded')

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
