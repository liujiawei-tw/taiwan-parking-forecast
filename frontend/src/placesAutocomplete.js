const PLACE_FIELDS = ['displayName', 'formattedAddress', 'location']

function readText(value) {
  if (typeof value === 'string') return value.trim()
  if (typeof value?.text === 'string') return value.text.trim()
  return ''
}

export function readPlaceCoordinates(location) {
  const latitude = typeof location?.lat === 'function' ? location.lat() : location?.lat
  const longitude = typeof location?.lng === 'function' ? location.lng() : location?.lng
  const numericLatitude = typeof latitude === 'number' ? latitude : Number.NaN
  const numericLongitude = typeof longitude === 'number' ? longitude : Number.NaN
  if (!Number.isFinite(numericLatitude)
      || !Number.isFinite(numericLongitude)
      || numericLatitude < -90 || numericLatitude > 90
      || numericLongitude < -180 || numericLongitude > 180) return null
  return { latitude: numericLatitude, longitude: numericLongitude }
}

export function createOriginUpdateCoordinator() {
  let latestUpdate = 0
  return {
    begin() {
      latestUpdate += 1
      return latestUpdate
    },
    isCurrent(updateId) {
      return updateId === latestUpdate
    },
    invalidate() {
      latestUpdate += 1
    },
  }
}

export function requestCurrentLocation(geolocation, coordinator, callbacks) {
  const updateId = coordinator.begin()
  const isCurrent = () => coordinator.isCurrent(updateId)

  if (typeof geolocation?.getCurrentPosition !== 'function') {
    callbacks.onError({ unsupported: true })
    return false
  }

  callbacks.onStart()
  try {
    geolocation.getCurrentPosition(
      (position) => {
        if (!isCurrent()) return
        callbacks.onSuccess(position)
      },
      (error) => {
        if (!isCurrent()) return
        callbacks.onError(error)
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 60000,
      },
    )
  } catch (error) {
    if (isCurrent()) callbacks.onError(error)
  }
  return true
}

export function createPlaceSelectionHandler({
  onSelectionStart = () => undefined,
  isSelectionCurrent = () => true,
  onSelected,
  onError,
}) {
  let latestSelectionId = 0

  return async (event) => {
    const selectionId = ++latestSelectionId
    const context = onSelectionStart()
    const isCurrent = () => selectionId === latestSelectionId && isSelectionCurrent(context)
    const prediction = event?.placePrediction || event?.detail?.placePrediction

    if (!prediction || typeof prediction.toPlace !== 'function') {
      if (isCurrent()) onError('無法讀取 Google Places 候選，請重新搜尋後再選擇。')
      return false
    }

    try {
      const place = prediction.toPlace()
      await place.fetchFields({ fields: PLACE_FIELDS })
      if (!isCurrent()) return false

      const coordinates = readPlaceCoordinates(place.location)
      if (!coordinates) {
        onError('此地點沒有可用座標，請改選其他候選。')
        return false
      }

      const address = readText(place.formattedAddress)
      onSelected({
        label: readText(place.displayName) || address || '已選取地點',
        address,
        ...coordinates,
      }, context)
      onError('')
      return true
    } catch {
      if (isCurrent()) onError('讀取所選地點失敗，請重新搜尋後再試。')
      return false
    }
  }
}
