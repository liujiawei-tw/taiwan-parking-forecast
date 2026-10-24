import assert from 'node:assert/strict'
import { API_ERROR_MESSAGES, readApiError, requestFailureMessage } from '../src/apiErrors.js'

async function responseError(status, payload) {
  return readApiError({
    status,
    async json() {
      if (payload instanceof Error) throw payload
      return payload
    },
  })
}

for (const [status, code] of [
  [400, 'INVALID_REQUEST'],
  [400, 'INVALID_COORDINATE'],
  [422, 'UNSUPPORTED_AREA'],
  [422, 'INVALID_DEPARTURE_TIME'],
  [429, 'RATE_LIMITED'],
  [404, 'QUERY_NOT_FOUND'],
  [410, 'QUERY_EXPIRED'],
]) {
  const error = await responseError(status, { error: { code, message: 'server text not shown' } })
  assert.equal(error.status, status)
  assert.equal(error.code, code)
  assert.equal(error.message, API_ERROR_MESSAGES[code])
  assert.equal(requestFailureMessage(error), API_ERROR_MESSAGES[code])
}

const nonJsonForbidden = await responseError(403, new SyntaxError('not JSON'))
assert.equal(nonJsonForbidden.status, 403)
assert.equal(nonJsonForbidden.code, undefined)
assert.match(nonJsonForbidden.message, /HTTP 403/)
assert.match(requestFailureMessage(nonJsonForbidden), /HTTP 403/)
assert.doesNotMatch(nonJsonForbidden.message, /連線|網路/)

const unknownCode = await responseError(422, { error: { code: 'UNRECOGNIZED_CODE' } })
assert.equal(unknownCode.code, undefined, 'only documented API codes are retained in acceptance events')
assert.match(unknownCode.message, /服務範圍|指定時間/)

assert.match(requestFailureMessage(new TypeError('Failed to fetch')), /無法連線/)

console.log('API error message tests passed')
