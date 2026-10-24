import assert from 'node:assert/strict'
import {
  countMapMarkerCategories,
  describeMapMarker,
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
popupItems.set('spot-1', marker({ availabilityStatus: 'STALE', navigationAvailable: false }))
refreshSelectedMapPopup(
  'spot-1',
  new Set(['spot-1']),
  popupItems,
  { setContent(content) { popupContent = content } },
  (item) => ({ ...describeMapMarker(item), item }),
)
assert.equal(popupContent.item.availabilityStatus, 'STALE', 'an open popup is rebuilt from the latest stale marker')
assert.equal(popupContent.navigationAvailable, false, 'a stale popup cannot retain a route action')
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

console.log('map marker tests passed')
