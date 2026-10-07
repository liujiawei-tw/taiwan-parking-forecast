import assert from 'node:assert/strict'
import {
  appendAcceptanceEvent,
  summarizeParkingQueryPayload,
} from '../src/acceptanceLog.js'
import {
  buildParkingQueryPayload,
  buildRequestToken,
  hasUsableGoogleMapsKey,
  roadsideCountLabel,
  shouldAutoRefresh,
} from '../src/planner.js'

function queryResult({
  queryId = 'query-1',
  roadsideStatus = 'READY',
  offStreetStatus = 'READY',
  refreshInProgress = false,
} = {}) {
  return {
    query: { queryId },
    roadside: { status: roadsideStatus },
    offStreet: { status: offStreetStatus },
    refresh: {
      roadside: { refreshInProgress },
      offStreet: { refreshInProgress },
    },
  }
}

assert.equal(hasUsableGoogleMapsKey(''), false)
assert.equal(hasUsableGoogleMapsKey('replace-with-local-browser-key'), false)
assert.equal(hasUsableGoogleMapsKey('browser-key-for-local-test'), true)
assert.equal(roadsideCountLabel(null, 0), '路邊尚未查詢')
assert.equal(roadsideCountLabel('STALE', 0), '路邊目前有位：未知')
assert.equal(roadsideCountLabel('NO_CATALOG_RESULTS', 0), '路邊目前有位：未知')
assert.equal(roadsideCountLabel('READY', 0), '路邊目前有位格位 0 個')

assert.equal(
  buildRequestToken({ randomUUID: () => '00000000-0000-4000-8000-000000000001' }),
  'web-00000000-0000-4000-8000-000000000001',
)
assert.equal(
  buildRequestToken(null, () => 1234, () => 0.5),
  'web-1234-8',
)

const payload = buildParkingQueryPayload({
  requestToken: 'token-1',
  origin: {
    label: '目前位置',
    latitude: '25.04',
    longitude: '121.45',
    placeId: 'must-not-be-sent',
    eta: { durationSeconds: 1 },
  },
  destination: {
    label: '新莊體育館',
    latitude: '25.036',
    longitude: '121.4505',
    placeId: 'google-place-id',
    formattedAddress: '新北市新莊區中華路一段75號',
    currentlyAvailableSpaces: 99,
    predictionProbability: 0.9,
  },
  departureType: 'NOW',
  scheduledDepartureLocal: '',
})

assert.deepEqual(payload, {
  requestToken: 'token-1',
  origin: {
    label: '目前位置',
    latitude: 25.04,
    longitude: 121.45,
  },
  destination: {
    label: '新莊體育館',
    latitude: 25.036,
    longitude: 121.4505,
  },
  departure: { type: 'NOW' },
})
assert.equal(JSON.stringify(payload).includes('placeId'), false)
assert.equal(JSON.stringify(payload).includes('predictionProbability'), false)
assert.equal(JSON.stringify(payload).includes('currentlyAvailableSpaces'), false)
assert.equal(JSON.stringify(payload).includes('eta'), false)

assert.deepEqual(summarizeParkingQueryPayload(payload), {
  topLevelFields: ['departure', 'destination', 'origin', 'requestToken'],
  originFields: ['label', 'latitude', 'longitude'],
  destinationFields: ['label', 'latitude', 'longitude'],
  departureType: 'NOW',
  forbiddenFieldsPresent: [],
})

assert.deepEqual(
  summarizeParkingQueryPayload({
    ...payload,
    destination: {
      ...payload.destination,
      placeId: 'must-not-be-logged',
      predictionProbability: 0.8,
    },
  }).forbiddenFieldsPresent,
  ['placeId', 'predictionProbability'],
)

const acceptanceEvents = appendAcceptanceEvent([], {
  occurredAt: '2026-09-30T01:00:00.000Z',
  type: 'QUERY_POST',
  message: 'created',
  details: {
    method: 'POST',
    status: 200,
    requestToken: 'secret-ish-token',
    responseBody: { raw: true },
    nested: {
      googleKey: 'must-not-be-logged',
      ok: true,
    },
  },
})
assert.deepEqual(acceptanceEvents, [{
  occurredAt: '2026-09-30T01:00:00.000Z',
  type: 'QUERY_POST',
  message: 'created',
  details: {
    method: 'POST',
    status: 200,
    nested: {
      ok: true,
    },
  },
}])

const visibleStale = shouldAutoRefresh({
  queryResult: queryResult({ roadsideStatus: 'STALE', offStreetStatus: 'NO_REALTIME_SNAPSHOT' }),
  visible: true,
  loading: false,
  refreshInProgress: false,
  lastAttemptKey: '',
})
assert.equal(visibleStale.shouldRefresh, true)
assert.deepEqual(visibleStale.sources, ['roadside', 'offStreet'])
assert.equal(visibleStale.attemptKey, 'query-1:STALE:NO_REALTIME_SNAPSHOT')

assert.equal(shouldAutoRefresh({
  queryResult: queryResult({ roadsideStatus: 'STALE' }),
  visible: false,
}).shouldRefresh, false)

assert.equal(shouldAutoRefresh({
  queryResult: queryResult({ roadsideStatus: 'STALE' }),
  visible: true,
  loading: true,
}).shouldRefresh, false)

assert.equal(shouldAutoRefresh({
  queryResult: queryResult({ roadsideStatus: 'STALE', refreshInProgress: true }),
  visible: true,
  refreshInProgress: true,
}).shouldRefresh, false)

assert.equal(shouldAutoRefresh({
  queryResult: queryResult({ roadsideStatus: 'STALE', offStreetStatus: 'READY' }),
  visible: true,
  lastAttemptKey: 'query-1:STALE:READY',
}).shouldRefresh, false)

console.log('planner contract tests passed')
