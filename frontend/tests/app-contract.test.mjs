import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')

assert.match(app, /ref="originAutocompleteHost"/)
assert.match(app, /ref="destinationAutocompleteHost"/)
assert.match(app, /selectionHandler: originPlaceSelectionHandler/)
assert.match(app, /selectionHandler: destinationPlaceSelectionHandler/)
assert.match(app, /v-if="hasGoogleMapsKey" ref="originAutocompleteHost"/)
assert.match(app, /v-else class="places-help"/)
assert.doesNotMatch(app, /type="number"|測試座標|手動座標|COORDINATES/)
assert.match(app, /@click="createParkingQuery"/)
assert.match(app, /operation: 'create'/)
assert.match(app, /operation: 'refresh'/)
assert.match(app, /errorCode: apiError\?\.code/)
assert.match(app, /requestFailureMessage\(error\)/)
const originSelectionEvent = app.match(/onSelected\(place\) \{\s*origin\.value = place([\s\S]*?)\n  \},/)?.[1]
const destinationSelectionEvent = app.match(/onSelected\(place\) \{\s*selectedDestination\.value = place([\s\S]*?)\n  \},/)?.[1]
assert.ok(originSelectionEvent, 'origin selection event handler must exist')
assert.ok(destinationSelectionEvent, 'destination selection event handler must exist')
assert.doesNotMatch(originSelectionEvent, /label|latitude|longitude|address/i, 'origin event must not log exact location data')
assert.doesNotMatch(destinationSelectionEvent, /label|latitude|longitude|address/i, 'destination event must not log exact location data')
const resetDestinationBody = app.match(/function useDefaultDestination\(\) \{([\s\S]*?)\n\}/)?.[1]
assert.ok(resetDestinationBody, 'default destination reset handler must exist')
assert.ok(
  resetDestinationBody.indexOf('destinationSelectionVersion++')
    < resetDestinationBody.indexOf('selectedDestination.value ='),
  'default destination reset must invalidate pending Places selections before changing the selection',
)

console.log('App Places UI contract tests passed')
