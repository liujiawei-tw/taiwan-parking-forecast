import assert from 'node:assert/strict'
import {
  loadMapViewportPages,
  mapViewportRequestKey,
  normalizeMapViewportBounds,
} from '../src/mapViewport.js'

const bounds = { south: 25.02, west: 121.42, north: 25.06, east: 121.48 }
assert.deepEqual(normalizeMapViewportBounds(bounds), bounds)
assert.equal(normalizeMapViewportBounds({ ...bounds, west: 121.49 }), null)
assert.equal(normalizeMapViewportBounds(null), null)
assert.equal(
  mapViewportRequestKey('query-1', bounds, 'snapshot-2'),
  'viewport:query-1:25.02000,121.42000,25.06000,121.48000:snapshot-2',
)
assert.equal(mapViewportRequestKey('', bounds), '')

const calls = []
const pages = [
  {
    queryId: 'query-1', serviceArea: '新莊區', offset: 0, pageSize: 2,
    supportedArea: { south: 25.01, west: 121.4, north: 25.08, east: 121.5 },
    candidateCount: 3, verifiedCandidateCount: 3, excludedUnverifiedCount: 0,
    hasMore: true, nextOffset: 2, items: [{ id: 'spot-1' }, { id: 'spot-2' }],
  },
  {
    queryId: 'query-1', serviceArea: '新莊區', offset: 2, pageSize: 2,
    candidateCount: 3, verifiedCandidateCount: 3, excludedUnverifiedCount: 0,
    hasMore: false, nextOffset: null, items: [{ id: 'spot-2' }, { id: 'lot-1' }],
  },
]
const result = await loadMapViewportPages(async (url, options) => {
  calls.push({ url: new URL(url, 'http://localhost'), options })
  return { ok: true, async json() { return pages[calls.length - 1] } }
}, {
  apiBaseUrl: 'http://localhost:8080',
  queryId: 'query-1',
  bounds,
  pageSize: 2,
})
assert.equal(calls.length, 2)
assert.equal(calls[0].options.method, 'GET')
assert.equal(calls[0].url.pathname, '/api/v1/parking-queries/query-1/map-data/viewport')
assert.equal(calls[0].url.searchParams.get('south'), '25.02')
assert.equal(calls[0].url.searchParams.get('limit'), '2')
assert.deepEqual(result.items.map((item) => item.id), ['spot-1', 'spot-2', 'lot-1'])
assert.equal(result.returnedItemCount, 3)
assert.equal(result.hasMore, false)
assert.equal(result.nextOffset, null)
assert.deepEqual(result.supportedArea, pages[0].supportedArea)

let invalidBoundsFetchCount = 0
await assert.rejects(loadMapViewportPages(async () => {
  invalidBoundsFetchCount++
}, { queryId: 'query-1', bounds: { ...bounds, east: 121.4 } }), /無效/)
assert.equal(invalidBoundsFetchCount, 0)

await assert.rejects(loadMapViewportPages(async () => ({
  ok: true,
  async json() { return { queryId: 'other-query', offset: 0, items: [], hasMore: false } },
}), { queryId: 'query-1', bounds }), /回應格式/)

await assert.rejects(loadMapViewportPages(async () => ({
  ok: true,
  async json() { return { queryId: 'query-1', serviceArea: '新莊區', offset: 0, items: [], hasMore: true, nextOffset: 0 } },
}), { queryId: 'query-1', bounds }), /分頁無法繼續/)

await assert.rejects(loadMapViewportPages(async () => ({
  ok: false,
  status: 404,
  async json() { return { error: { code: 'QUERY_NOT_FOUND' } } },
}), { queryId: 'query-1', bounds }), /不存在或已失效/)

console.log('map viewport tests passed')
