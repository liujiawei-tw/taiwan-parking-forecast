export const ACCEPTANCE_EVENT_LIMIT = 12

const FORBIDDEN_PAYLOAD_FIELDS = [
  'placeId',
  'eta',
  'currentlyAvailableSpaces',
  'predictionProbability',
  'availableCarSpaces',
]
const SENSITIVE_EVENT_DETAIL_KEYS = /key|secret|token|body|response|latitude|longitude|address|label|location|origin|destination|ip/i

export function appendAcceptanceEvent(events, event, limit = ACCEPTANCE_EVENT_LIMIT) {
  return [
    {
      occurredAt: event.occurredAt || new Date().toISOString(),
      type: event.type,
      message: event.message,
      details: sanitizeDetails(event.details || {}),
    },
    ...events,
  ].slice(0, limit)
}

export function summarizeParkingQueryPayload(payload) {
  const serialized = JSON.stringify(payload)
  return {
    topLevelFields: Object.keys(payload).sort(),
    originFields: Object.keys(payload.origin || {}).sort(),
    destinationFields: Object.keys(payload.destination || {}).sort(),
    departureType: payload.departure?.type || 'UNKNOWN',
    forbiddenFieldsPresent: FORBIDDEN_PAYLOAD_FIELDS.filter((field) => serialized.includes(field)),
  }
}

function sanitizeDetails(details) {
  return Object.fromEntries(
    Object.entries(details)
      .filter(([key]) => !SENSITIVE_EVENT_DETAIL_KEYS.test(key))
      .map(([key, value]) => [key, normalizeValue(value)]),
  )
}

function normalizeValue(value) {
  if (value === null || value === undefined) return value
  if (Array.isArray(value)) return value.map(normalizeValue)
  if (typeof value === 'object') return sanitizeDetails(value)
  return value
}
