import assert from 'node:assert/strict'
import { DEFAULT_ORIGIN, locationErrorMessage } from '../src/planner.js'
import {
  createOriginUpdateCoordinator,
  createPlaceSelectionHandler,
  readPlaceCoordinates,
  requestCurrentLocation,
} from '../src/placesAutocomplete.js'

function placePrediction({
  name = '測試地點',
  address = '新北市新莊區測試路1號',
  location = { lat: () => 25.04, lng: () => 121.45 },
  fetchFields = async () => {},
} = {}) {
  const fieldsRequests = []
  return {
    fieldsRequests,
    placePrediction: {
      toPlace() {
        return {
          displayName: name,
          formattedAddress: address,
          location,
          async fetchFields(options) {
            fieldsRequests.push(options)
            await fetchFields()
          },
        }
      },
    },
  }
}

assert.deepEqual(readPlaceCoordinates({ lat: () => 25.04, lng: () => 121.45 }), {
  latitude: 25.04,
  longitude: 121.45,
})
assert.equal(readPlaceCoordinates(null), null)
assert.equal(readPlaceCoordinates({ lat: () => null, lng: () => 121.45 }), null)

let origin = { ...DEFAULT_ORIGIN }
let destination = { label: '新莊體育館', latitude: 25.036, longitude: 121.4505 }
const originErrors = []
const destinationErrors = []
const coordinator = createOriginUpdateCoordinator()
const originHandler = createPlaceSelectionHandler({
  onSelectionStart: () => coordinator.begin(),
  isSelectionCurrent: (updateId) => coordinator.isCurrent(updateId),
  onSelected: (place) => { origin = place },
  onError: (message) => originErrors.push(message),
})
const destinationHandler = createPlaceSelectionHandler({
  onSelected: (place) => { destination = place },
  onError: (message) => destinationErrors.push(message),
})

const newOrigin = placePrediction({ name: '新莊捷運站', address: '新北市新莊區中正路138號' })
assert.equal(await originHandler(newOrigin), true)
assert.deepEqual(newOrigin.fieldsRequests, [{ fields: ['displayName', 'formattedAddress', 'location'] }])
assert.equal(origin.label, '新莊捷運站')
assert.equal(destination.label, '新莊體育館', 'origin selection must not update destination')

const newDestination = placePrediction({
  name: '新莊體育館',
  location: { lat: 25.036, lng: 121.4505 },
})
assert.equal(await destinationHandler(newDestination), true)
assert.equal(destination.label, '新莊體育館')
assert.equal(origin.label, '新莊捷運站', 'destination selection must not update origin')

let resetDestination = { label: '原目的地', latitude: 25.036, longitude: 121.4505 }
let resetDestinationVersion = 0
let resolvePendingResetPlace
const resetDestinationHandler = createPlaceSelectionHandler({
  onSelectionStart: () => ++resetDestinationVersion,
  isSelectionCurrent: (version) => version === resetDestinationVersion,
  onSelected: (place) => { resetDestination = place },
  onError: () => {},
})
const pendingResetPlace = placePrediction({
  name: '較晚回應的舊目的地',
  fetchFields: () => new Promise((resolve) => { resolvePendingResetPlace = resolve }),
})
const pendingResetSelection = resetDestinationHandler(pendingResetPlace)
resetDestinationVersion++
resetDestination = { label: '回到新莊體育館', latitude: 25.036, longitude: 121.4505 }
resolvePendingResetPlace()
assert.equal(await pendingResetSelection, false)
assert.equal(resetDestination.label, '回到新莊體育館', 'resetting the destination invalidates an in-flight Places response')

const lastValidDestination = destination
const locationlessPlace = placePrediction({ location: null })
assert.equal(await destinationHandler(locationlessPlace), false)
assert.equal(destination, lastValidDestination, 'a place without coordinates must preserve the last valid selection')
assert.match(destinationErrors.at(-1), /沒有可用座標/)

const lastValidOrigin = origin
const failedOriginPlace = placePrediction({ fetchFields: async () => { throw new Error('API failure') } })
assert.equal(await originHandler(failedOriginPlace), false)
assert.equal(origin, lastValidOrigin, 'fetchFields failure must preserve the last valid origin')
assert.match(originErrors.at(-1), /讀取所選地點失敗/)

let resolveOlderPlace
const olderPlace = placePrediction({
  name: '舊選擇',
  fetchFields: () => new Promise((resolve) => { resolveOlderPlace = resolve }),
})
const newerPlace = placePrediction({ name: '較新選擇' })
const olderSelection = originHandler(olderPlace)
assert.equal(await originHandler(newerPlace), true)
resolveOlderPlace()
assert.equal(await olderSelection, false)
assert.equal(origin.label, '較新選擇', 'a delayed older Places response must not overwrite the latest selection')

let pendingLocationSuccess
let pendingLocationError
let locationError = ''
const locationRequest = requestCurrentLocation({
  getCurrentPosition(success, failure) {
    pendingLocationSuccess = success
    pendingLocationError = failure
  },
}, coordinator, {
  onStart() {},
  onSuccess(position) {
    origin = {
      label: '裝置目前位置',
      address: '',
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
    }
  },
  onError: (error) => { locationError = error?.unsupported ? 'unsupported' : locationErrorMessage(error) },
})
assert.equal(locationRequest, true)
pendingLocationSuccess({ coords: { latitude: 25.05, longitude: 121.46 } })
assert.equal(origin.latitude, 25.05, 'successful geolocation updates the origin')

const lastValidLocation = origin
requestCurrentLocation({
  getCurrentPosition(_success, failure) {
    pendingLocationError = failure
  },
}, coordinator, {
  onStart() {},
  onSuccess: () => assert.fail('failed geolocation must not report success'),
  onError: (error) => { locationError = locationErrorMessage(error) },
})
pendingLocationError({ code: 1 })
assert.equal(origin, lastValidLocation, 'failed or denied geolocation must retain the last valid origin')
assert.match(locationError, /改用地點搜尋/)

let staleLocationSuccess
requestCurrentLocation({
  getCurrentPosition(success) { staleLocationSuccess = success },
}, coordinator, {
  onStart() {},
  onSuccess: () => assert.fail('stale location response must not overwrite a newer selection'),
  onError: () => assert.fail('stale location response must not report an error'),
})
const latestOriginUpdate = coordinator.begin()
origin = { label: '較新的地點選擇', latitude: 25.06, longitude: 121.47 }
staleLocationSuccess({ coords: { latitude: 24, longitude: 120 } })
assert.equal(origin.label, '較新的地點選擇')
assert.equal(coordinator.isCurrent(latestOriginUpdate), true)

let unsupportedError
assert.equal(requestCurrentLocation(null, coordinator, {
  onStart: () => assert.fail('unsupported geolocation must not start a request'),
  onSuccess: () => assert.fail('unsupported geolocation must not report success'),
  onError: (error) => { unsupportedError = error },
}), false)
assert.equal(unsupportedError.unsupported, true)

console.log('Places autocomplete and location tests passed')
