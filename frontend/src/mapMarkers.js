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
  const navigationAvailable = item.navigationAvailable === true
    && eligibilityConfirmed
    && item.availabilityStatus === 'AVAILABLE'

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
