const STATUS_STYLE = {
  AVAILABLE: { category: 'AVAILABLE', color: '#247a54', label: '目前有位' },
  OCCUPIED: { category: 'OCCUPIED', color: '#b33e36', label: '已占用' },
  FULL: { category: 'FULL', color: '#b33e36', label: '已滿' },
  STALE: { category: 'STALE', color: '#806a49', label: '資料過期' },
  UNKNOWN: { category: 'UNKNOWN', color: '#657386', label: '狀態未知' },
  NO_OBSERVATION: { category: 'UNKNOWN', color: '#657386', label: '尚無逐筆觀測' },
  NO_REALTIME_AVAILABILITY: { category: 'UNKNOWN', color: '#657386', label: '尚無即時資料' },
  OUT_OF_SERVICE: { category: 'UNKNOWN', color: '#657386', label: '停止服務' },
}

const STATUS_MARKER_TEXT = {
  AVAILABLE: '有',
  OCCUPIED: '占',
  FULL: '滿',
  STALE: '舊',
  UNKNOWN: '?',
  NO_OBSERVATION: '?',
  NO_REALTIME_AVAILABILITY: '?',
  OUT_OF_SERVICE: '停',
}

export function describeMapMarker(item) {
  const status = STATUS_STYLE[item.availabilityStatus] ?? STATUS_STYLE.UNKNOWN
  const eligibilityConfirmed = item.carEligibleStatus === 'CONFIRMED'
  const category = eligibilityConfirmed ? status.category : 'UNKNOWN'
  const color = eligibilityConfirmed ? status.color : STATUS_STYLE.UNKNOWN.color
  const navigationAvailable = item.navigationAvailable === true && eligibilityConfirmed

  let value = status.label
  if (!eligibilityConfirmed) {
    value = '一般汽車適用性未確認'
  } else if (item.type === 'OFF_STREET') {
    if (item.availabilityStatus === 'FULL' || item.availableCarSpaces === 0) {
      value = '已滿（0 位）'
    } else if (item.availabilityStatus === 'AVAILABLE' && Number.isInteger(item.availableCarSpaces)) {
      value = `剩餘 ${item.availableCarSpaces} 位`
    } else if (item.availableCarSpaces === null || item.availableCarSpaces === undefined) {
      value = status.label
    }
  }

  const markerText = item.type === 'OFF_STREET'
    ? item.availabilityStatus === 'AVAILABLE' && Number.isInteger(item.availableCarSpaces)
      ? `P${item.availableCarSpaces}`
      : item.availabilityStatus === 'FULL' ? '滿' : 'P'
    : eligibilityConfirmed
      ? (STATUS_MARKER_TEXT[item.availabilityStatus] ?? '?')
      : '?'

  return {
    category,
    color,
    statusLabel: eligibilityConfirmed ? status.label : '車種適用性未確認',
    value,
    markerText,
    navigationAvailable,
  }
}

export function filterMapMarkers(items, filters) {
  return items.filter((item) => {
    const typeEnabled = item.type === 'ROADSIDE'
      ? filters.layers.roadside
      : filters.layers.offStreet
    if (!typeEnabled) return false
    const category = describeMapMarker(item).category
    return filters.statuses[category] !== false
  })
}

export function countMapMarkerCategories(items) {
  return items.reduce((counts, item) => {
    const category = describeMapMarker(item).category
    counts[category] = (counts[category] ?? 0) + 1
    return counts
  }, { AVAILABLE: 0, OCCUPIED: 0, FULL: 0, UNKNOWN: 0, STALE: 0 })
}

export function formatMapCapacity(item) {
  const available = Number.isInteger(item.availableCarSpaces) && item.availableCarSpaces >= 0
    ? item.availableCarSpaces
    : null
  const reportedTotal = Number.isInteger(item.totalCarSpaces) && item.totalCarSpaces >= 0
    ? item.totalCarSpaces
    : null
  const total = reportedTotal ?? (item.type === 'ROADSIDE' ? 1 : null)
  const unit = item.type === 'ROADSIDE' ? '格' : '位'

  if (available === null && total === null) return '未提供'
  return `${available ?? '未知'} / ${total ?? '未知'} ${unit}`
}

export function clusterRoadsideMarkers(items, zoom, cellSize = 56) {
  if (!Number.isFinite(zoom) || zoom >= 21 || !Number.isFinite(cellSize) || cellSize <= 0) return []

  const worldSize = 256 * (2 ** zoom)
  const bucketSize = cellSize / 2
  const buckets = new Map()
  const points = []
  for (const item of items) {
    if (item.type !== 'ROADSIDE'
        || !Number.isFinite(item.latitude)
        || !Number.isFinite(item.longitude)) continue

    const latitude = Math.max(-85.05112878, Math.min(85.05112878, item.latitude))
    const sinLatitude = Math.sin(latitude * Math.PI / 180)
    const x = ((item.longitude + 180) / 360) * worldSize
    const y = (0.5 - Math.log((1 + sinLatitude) / (1 - sinLatitude)) / (4 * Math.PI)) * worldSize
    const column = Math.floor(x / bucketSize)
    const row = Math.floor(y / bucketSize)
    const key = `${column}:${row}`
    const point = { item, x, y, index: points.length }
    points.push(point)
    const bucket = buckets.get(key) ?? {
      column,
      row,
      points: [],
      minX: x,
      maxX: x,
      minY: y,
      maxY: y,
    }
    bucket.points.push(point)
    bucket.minX = Math.min(bucket.minX, x)
    bucket.maxX = Math.max(bucket.maxX, x)
    bucket.minY = Math.min(bucket.minY, y)
    bucket.maxY = Math.max(bucket.maxY, y)
    buckets.set(key, bucket)
  }

  const parents = points.map((point) => point.index)
  const ranks = points.map(() => 0)
  const find = (index) => {
    if (parents[index] !== index) parents[index] = find(parents[index])
    return parents[index]
  }
  const union = (left, right) => {
    const leftRoot = find(left)
    const rightRoot = find(right)
    if (leftRoot === rightRoot) return
    if (ranks[leftRoot] < ranks[rightRoot]) {
      parents[leftRoot] = rightRoot
    } else {
      parents[rightRoot] = leftRoot
      if (ranks[leftRoot] === ranks[rightRoot]) ranks[leftRoot]++
    }
  }
  const radiusSquared = cellSize ** 2
  const neighborRange = Math.ceil(cellSize / bucketSize)
  const bucketValues = [...buckets.values()]

  for (const bucket of bucketValues) {
    const first = bucket.points[0]
    for (const point of bucket.points.slice(1)) union(first.index, point.index)

    for (let columnOffset = -neighborRange; columnOffset <= neighborRange; columnOffset++) {
      for (let rowOffset = -neighborRange; rowOffset <= neighborRange; rowOffset++) {
        if (columnOffset === 0 && rowOffset === 0) continue
        const neighborKey = `${bucket.column + columnOffset}:${bucket.row + rowOffset}`
        const neighbor = buckets.get(neighborKey)
        if (!neighbor || neighbor.row < bucket.row
            || (neighbor.row === bucket.row && neighbor.column <= bucket.column)) continue

        const xGap = Math.max(0, bucket.minX - neighbor.maxX, neighbor.minX - bucket.maxX)
        const yGap = Math.max(0, bucket.minY - neighbor.maxY, neighbor.minY - bucket.maxY)
        if (xGap ** 2 + yGap ** 2 > radiusSquared) continue

        let connected = false
        for (const point of bucket.points) {
          for (const neighborPoint of neighbor.points) {
            const deltaX = point.x - neighborPoint.x
            const deltaY = point.y - neighborPoint.y
            if (deltaX ** 2 + deltaY ** 2 <= radiusSquared) {
              union(point.index, neighborPoint.index)
              connected = true
              break
            }
          }
          if (connected) break
        }
      }
    }
  }

  const groupsByRoot = new Map()
  for (const point of points) {
    const root = find(point.index)
    const group = groupsByRoot.get(root) ?? []
    group.push(point.item)
    groupsByRoot.set(root, group)
  }

  return [...groupsByRoot.values()]
    .filter((group) => group.length > 1)
    .map((group) => ({
      items: group,
      center: {
        latitude: group.reduce((sum, item) => sum + item.latitude, 0) / group.length,
        longitude: group.reduce((sum, item) => sum + item.longitude, 0) / group.length,
      },
      counts: countMapMarkerCategories(group),
    }))
}

export function refreshSelectedMapPopup(selectedId, visibleIds, itemsById, infoWindow, createContent) {
  if (!selectedId) return false
  if (!visibleIds.has(selectedId)) {
    infoWindow?.close()
    return true
  }

  const item = itemsById.get(selectedId)
  if (item) infoWindow?.setContent(createContent(item))
  return false
}
