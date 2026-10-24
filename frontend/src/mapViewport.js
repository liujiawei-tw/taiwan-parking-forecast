import { readApiError } from './apiErrors.js'

export const MAP_VIEWPORT_PAGE_SIZE = 300
export const MAP_VIEWPORT_MAX_PAGES = 68

export function normalizeMapViewportBounds(bounds) {
  if (!bounds) return null
  const normalized = {
    south: Number(bounds.south),
    west: Number(bounds.west),
    north: Number(bounds.north),
    east: Number(bounds.east),
  }
  if (![normalized.south, normalized.west, normalized.north, normalized.east].every(Number.isFinite)
      || normalized.south < -90 || normalized.north > 90
      || normalized.west < -180 || normalized.east > 180
      || normalized.south >= normalized.north || normalized.west >= normalized.east) {
    return null
  }
  return normalized
}

export function mapViewportRequestKey(queryId, bounds, sourceVersion = '') {
  const normalized = normalizeMapViewportBounds(bounds)
  if (!queryId || !normalized) return ''
  const coordinates = [normalized.south, normalized.west, normalized.north, normalized.east]
    .map((value) => value.toFixed(5))
    .join(',')
  return `viewport:${queryId}:${coordinates}:${sourceVersion}`
}

export async function loadMapViewportPages(
  fetchImpl,
  { apiBaseUrl = '', queryId, bounds, pageSize = MAP_VIEWPORT_PAGE_SIZE, maxPages = MAP_VIEWPORT_MAX_PAGES },
) {
  const viewport = normalizeMapViewportBounds(bounds)
  if (typeof fetchImpl !== 'function' || !queryId || !viewport) {
    throw new Error('地圖範圍或查詢識別碼無效。')
  }
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 500
      || !Number.isInteger(maxPages) || maxPages < 1) {
    throw new Error('地圖分頁設定無效。')
  }

  const items = []
  const seenIds = new Set()
  let firstPage = null
  let offset = 0
  let pageCount = 0
  let hasMore = true

  while (hasMore && pageCount < maxPages) {
    const params = new URLSearchParams({
      south: String(viewport.south),
      west: String(viewport.west),
      north: String(viewport.north),
      east: String(viewport.east),
      offset: String(offset),
      limit: String(pageSize),
    })
    const response = await fetchImpl(
      `${apiBaseUrl}/api/v1/parking-queries/${encodeURIComponent(queryId)}/map-data/viewport?${params}`,
      { method: 'GET' },
    )
    if (!response.ok) throw await readApiError(response)

    const page = await response.json()
    if (!page || page.queryId !== queryId || !Array.isArray(page.items) || page.offset !== offset) {
      throw new Error('地圖範圍回應格式不符，請重新載入。')
    }
    if (firstPage && page.serviceArea !== firstPage.serviceArea) {
      throw new Error('地圖範圍分頁來源不一致，請重新載入。')
    }
    if (!firstPage) firstPage = page

    for (const item of page.items) {
      if (typeof item?.id !== 'string' || seenIds.has(item.id)) continue
      seenIds.add(item.id)
      items.push(item)
    }

    hasMore = page.hasMore === true
    pageCount++
    if (hasMore) {
      if (!Number.isInteger(page.nextOffset) || page.nextOffset <= offset || page.items.length === 0) {
        throw new Error('地圖範圍分頁無法繼續，請重新載入。')
      }
      offset = page.nextOffset
    }
  }

  if (!firstPage || hasMore) {
    throw new Error('地圖範圍資料超過目前載入上限，請縮小地圖範圍。')
  }

  return {
    ...firstPage,
    offset: 0,
    pageSize,
    returnedItemCount: items.length,
    nextOffset: null,
    hasMore: false,
    items,
  }
}
