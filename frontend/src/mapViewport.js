import { readApiError } from './apiErrors.js'

export const MAP_VIEWPORT_PAGE_SIZE = 300
export const MAP_VIEWPORT_MAX_PAGES = 68

const DEFAULT_MAP_SCALE_REFERENCE_WIDTH_PX = 92
const GOOGLE_MAPS_TILE_SIZE_PX = 256
const WEB_MERCATOR_EARTH_RADIUS_METERS = 6378137
const MAX_WEB_MERCATOR_LATITUDE = 85.05112878
const DEFAULT_GOOGLE_MAPS_ZOOM = 15

/**
 * 計算讓指定像素寬度代表指定地面距離時的 Google Maps zoom。
 * 無效距離回傳 zoom 15；無效緯度以赤道計算，超出 Web Mercator 範圍的緯度會先夾限。
 * @param {number} distanceMeters 目標地面距離，單位為公尺。
 * @param {number} latitude 地圖中心緯度，單位為度。
 * @param {number} referenceWidthPixels 比例尺代表距離的像素寬度，預設 92px。
 * @returns {number} 夾限在 3–21 的 Google Maps zoom。
 */
export function getMapZoomForGroundDistance(
  distanceMeters,
  latitude,
  referenceWidthPixels = DEFAULT_MAP_SCALE_REFERENCE_WIDTH_PX,
) {
  if (!Number.isFinite(distanceMeters) || distanceMeters <= 0) {
    return DEFAULT_GOOGLE_MAPS_ZOOM
  }

  const safeReferenceWidthPixels = Number.isFinite(referenceWidthPixels) && referenceWidthPixels > 0
    ? referenceWidthPixels
    : DEFAULT_MAP_SCALE_REFERENCE_WIDTH_PX
  const safeLatitude = Number.isFinite(latitude) ? latitude : 0
  const clampedLatitude = Math.max(
    -MAX_WEB_MERCATOR_LATITUDE,
    Math.min(MAX_WEB_MERCATOR_LATITUDE, safeLatitude),
  )
  const latitudeRadians = (clampedLatitude * Math.PI) / 180
  const groundMetersPerPixelAtZoomZero =
    (2 * Math.PI * WEB_MERCATOR_EARTH_RADIUS_METERS * Math.cos(latitudeRadians))
      / GOOGLE_MAPS_TILE_SIZE_PX
  const rawZoom = Math.log2(
    (groundMetersPerPixelAtZoomZero * safeReferenceWidthPixels) / distanceMeters,
  )

  return Math.max(3, Math.min(21, rawZoom))
}

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
