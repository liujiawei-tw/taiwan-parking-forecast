import assert from 'node:assert/strict'
import {
  countMapMarkerCategories,
  clusterRoadsideMarkers,
  describeMapMarker,
  formatMapCapacity,
  filterMapMarkers,
  refreshSelectedMapPopup,
} from '../src/mapMarkers.js'

const marker = (overrides = {}) => ({
  id: 'spot-1',
  type: 'ROADSIDE',
  availabilityStatus: 'AVAILABLE',
  carEligibleStatus: 'CONFIRMED',
  availableCarSpaces: null,
  navigationAvailable: true,
  ...overrides,
})

assert.equal(describeMapMarker(marker()).category, 'AVAILABLE')
assert.equal(describeMapMarker(marker()).color, '#247a54')
assert.equal(formatMapCapacity(marker({ availableCarSpaces: 1, totalCarSpaces: 1 })), '1 / 1 格')
assert.equal(formatMapCapacity(marker()), '未知 / 1 格')
assert.equal(formatMapCapacity(marker({
  type: 'OFF_STREET', availableCarSpaces: 8, totalCarSpaces: 120,
})), '8 / 120 位')
assert.equal(formatMapCapacity(marker({ type: 'OFF_STREET' })), '未提供')
assert.equal(describeMapMarker(marker({ availabilityStatus: 'OCCUPIED' })).category, 'OCCUPIED')
assert.equal(describeMapMarker(marker({ availabilityStatus: 'FULL' })).markerText, '滿')
assert.equal(describeMapMarker(marker({ availabilityStatus: 'STALE' })).category, 'STALE')
assert.equal(describeMapMarker(marker({ availabilityStatus: 'NO_REALTIME_AVAILABILITY' })).category, 'UNKNOWN')

let popupContent
let popupClosed = false
const popupItems = new Map([['spot-1', marker()]])
const popupWasClosed = refreshSelectedMapPopup(
  'spot-1',
  new Set(['spot-1']),
  popupItems,
  {
    setContent(content) { popupContent = content },
    close() { popupClosed = true },
  },
  (item) => ({ ...describeMapMarker(item), item }),
)
assert.equal(popupWasClosed, false)
assert.equal(popupContent.navigationAvailable, true, 'a fresh popup includes its qualified route action')
popupItems.set('spot-1', marker({ availabilityStatus: 'STALE' }))
refreshSelectedMapPopup(
  'spot-1',
  new Set(['spot-1']),
  popupItems,
  { setContent(content) { popupContent = content } },
  (item) => ({ ...describeMapMarker(item), item }),
)
assert.equal(popupContent.item.availabilityStatus, 'STALE', 'an open popup is rebuilt from the latest stale marker')
assert.equal(popupContent.navigationAvailable, true, 'a stale popup retains navigation to its verified location')
assert.equal(popupClosed, false)
assert.equal(refreshSelectedMapPopup('spot-1', new Set(), new Map(), {
  close() { popupClosed = true },
}, () => null), true)
assert.equal(popupClosed, true, 'a popup closes when its marker is no longer visible')

const unverified = describeMapMarker(marker({ carEligibleStatus: 'UNKNOWN' }))
assert.equal(unverified.category, 'UNKNOWN')
assert.equal(unverified.navigationAvailable, false)
assert.equal(unverified.value, '一般汽車適用性未確認')

const fullLot = describeMapMarker(marker({
  id: 'lot-1',
  type: 'OFF_STREET',
  availabilityStatus: 'FULL',
  availableCarSpaces: 0,
}))
assert.equal(fullLot.value, '已滿（0 位）')
assert.equal(fullLot.markerText, '滿')

const unknownLot = describeMapMarker(marker({
  id: 'lot-2',
  type: 'OFF_STREET',
  availabilityStatus: 'UNKNOWN',
  availableCarSpaces: null,
}))
assert.equal(unknownLot.value, '狀態未知')
assert.equal(unknownLot.markerText, 'P')

const items = [
  marker({ id: 'road-available' }),
  marker({ id: 'road-occupied', availabilityStatus: 'OCCUPIED' }),
  marker({ id: 'road-stale', availabilityStatus: 'STALE' }),
  marker({ id: 'lot-available', type: 'OFF_STREET', availableCarSpaces: 4 }),
]
const filters = {
  layers: { roadside: true, offStreet: false },
  statuses: { AVAILABLE: true, OCCUPIED: false, FULL: true, UNKNOWN: true, STALE: true },
}
assert.deepEqual(filterMapMarkers(items, filters).map((item) => item.id), ['road-available', 'road-stale'])
assert.equal(items.length, 4, 'filtering must not mutate source marker data')
assert.deepEqual(countMapMarkerCategories(items), {
  AVAILABLE: 2,
  OCCUPIED: 1,
  FULL: 0,
  UNKNOWN: 0,
  STALE: 1,
})

const closeSpots = [
  marker({ id: 'spot-a', latitude: 25.036, longitude: 121.45 }),
  marker({ id: 'spot-b', latitude: 25.036001, longitude: 121.450001, availabilityStatus: 'OCCUPIED' }),
  marker({ id: 'lot-nearby', type: 'OFF_STREET', latitude: 25.036, longitude: 121.45 }),
]
const lowZoomGroups = clusterRoadsideMarkers(closeSpots, 15)
assert.equal(lowZoomGroups.length, 1, 'nearby roadside cells group at neighborhood zoom')
assert.deepEqual(lowZoomGroups[0].items.map((item) => item.id), ['spot-a', 'spot-b'])
assert.equal(lowZoomGroups[0].counts.AVAILABLE, 1)
assert.equal(lowZoomGroups[0].counts.OCCUPIED, 1)
assert.equal(clusterRoadsideMarkers(closeSpots, 21).length, 0, 'individual roadside cells show at close zoom')
assert.equal(clusterRoadsideMarkers(closeSpots, 15, 0).length, 0, 'invalid cluster cell size is ignored')

const zoom = 15
const worldSize = 256 * (2 ** zoom)
const bucketSize = 56 / 2
const worldX = ((121.45 + 180) / 360) * worldSize
const boundaryX = Math.ceil(worldX / bucketSize) * bucketSize
const longitudeAtX = (x) => (x / worldSize) * 360 - 180
const acrossBoundary = clusterRoadsideMarkers([
  marker({ id: 'boundary-a', latitude: 25.036, longitude: longitudeAtX(boundaryX - 2) }),
  marker({ id: 'boundary-b', latitude: 25.036, longitude: longitudeAtX(boundaryX + 2) }),
], zoom)
assert.equal(acrossBoundary.length, 1, 'nearby roadside cells group across a spatial bucket boundary')
assert.equal(acrossBoundary[0].items.length, 2)

console.log('map marker tests passed')
