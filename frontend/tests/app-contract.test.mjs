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

console.log('App Places UI contract tests passed')
