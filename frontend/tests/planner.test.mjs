import assert from 'node:assert/strict'
import {
  appendAcceptanceEvent,
  summarizeParkingQueryPayload,
} from '../src/acceptanceLog.js'
import {
  buildDeparture,
  buildParkingQueryPayload,
  buildAutoRefreshAttemptKey,
  buildRequestToken,
  hasUsableGoogleMapsKey,
  locationErrorMessage,
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
assert.equal(locationErrorMessage({ code: 1 }), '定位權限未開啟，請改用地點搜尋選擇起點。')
assert.equal(locationErrorMessage({ code: 3 }), '定位逾時，請改用地點搜尋選擇起點。')
assert.equal(locationErrorMessage({ code: 99 }), '無法取得目前位置，請改用地點搜尋選擇起點。')
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

const scheduledDeparture = new Date(Date.now() + 120_000)
const scheduledDepartureLocal = new Date(scheduledDeparture.getTime() - scheduledDeparture.getTimezoneOffset() * 60_000)
  .toISOString()
  .slice(0, 16)
assert.deepEqual(buildDeparture('SCHEDULED', scheduledDepartureLocal), {
  type: 'SCHEDULED',
  departureAt: new Date(scheduledDepartureLocal).toISOString(),
})
assert.throws(
  () => buildDeparture('SCHEDULED', ''),
  (error) => error.code === 'INVALID_DEPARTURE_TIME',
  'an empty scheduled departure must not silently become NOW',
)
assert.throws(
  () => buildDeparture('SCHEDULED', 'not-a-date'),
  (error) => error.code === 'INVALID_DEPARTURE_TIME',
)
const pastDeparture = new Date(Date.now() - 120_000)
const pastDepartureLocal = new Date(pastDeparture.getTime() - pastDeparture.getTimezoneOffset() * 60_000)
  .toISOString()
  .slice(0, 16)
assert.throws(
  () => buildDeparture('SCHEDULED', pastDepartureLocal),
  (error) => error.code === 'INVALID_DEPARTURE_TIME',
)
assert.throws(
  () => buildParkingQueryPayload({
    requestToken: 'token-2',
    origin: { label: '起點', latitude: '', longitude: 121.45 },
    destination: { label: '目的地', latitude: 25.036, longitude: 121.4505 },
    departureType: 'NOW',
    scheduledDepartureLocal: '',
  }),
  (error) => error.code === 'INVALID_COORDINATE',
  'empty coordinates must be rejected before a request is sent',
)
assert.throws(
  () => buildParkingQueryPayload({
    requestToken: 'token-3',
    origin: { label: '   ', latitude: 25.03633, longitude: 121.45336 },
    destination: { label: '目的地', latitude: 25.036, longitude: 121.4505 },
    departureType: 'NOW',
    scheduledDepartureLocal: '',
  }),
  (error) => error.code === 'INVALID_REQUEST',
  'empty labels must be rejected before a request is sent',
)

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
    operation: 'create',
    errorCode: 'UNSUPPORTED_AREA',
    origin: { label: '精確位置', latitude: 25.123456, longitude: 121.123456 },
    clientIp: '192.0.2.12',
    nested: {
      googleKey: 'must-not-be-logged',
      address: '不得記錄的地址',
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
    operation: 'create',
    errorCode: 'UNSUPPORTED_AREA',
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

const expiredSnapshotQuery = {
  ...queryResult({ roadsideStatus: 'STALE', offStreetStatus: 'NO_REALTIME_SNAPSHOT' }),
  roadside: { status: 'STALE', snapshotId: 101, systemCollectedAt: '2026-10-24T10:00:00Z' },
  offStreet: { status: 'NO_REALTIME_SNAPSHOT' },
}
const refreshedSnapshotQuery = {
  ...expiredSnapshotQuery,
  roadside: { ...expiredSnapshotQuery.roadside, snapshotId: 102, systemCollectedAt: '2026-10-24T10:06:00Z' },
}
const previousSnapshotAttempt = buildAutoRefreshAttemptKey(expiredSnapshotQuery)
assert.notEqual(buildAutoRefreshAttemptKey(refreshedSnapshotQuery), previousSnapshotAttempt)
assert.equal(shouldAutoRefresh({
  queryResult: refreshedSnapshotQuery,
  visible: true,
  loading: false,
  refreshInProgress: false,
  lastAttemptKey: previousSnapshotAttempt,
}).shouldRefresh, true, 'a newer source snapshot can be refreshed after the previous attempt')

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
