export const API_ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: '起點、目的地或出發時間資料不完整，請重新確認後再試。',
  INVALID_COORDINATE: '起點或目的地位置資料無效，請重新選取地點後再試。',
  INVALID_REQUEST_TOKEN: '查詢資料已失效，請重新規劃行程。',
  UNSUPPORTED_AREA: '目的地超出目前支援範圍，請選擇新莊服務範圍內的地點。',
  INVALID_DEPARTURE_TIME: '指定出發時間無效或已過去，請選擇未來時間。',
  INVALID_REFRESH_TRIGGER: '更新請求無效，請重新整理頁面後再試。',
  QUERY_NOT_FOUND: '查詢不存在或已失效，請重新規劃行程。',
  QUERY_EXPIRED: '查詢已過期，請重新規劃行程。',
  RATE_LIMITED: '建立查詢次數已達限制，請稍候再試。',
  SERVICE_TEMPORARILY_UNAVAILABLE: '查詢暫時無法使用，請稍後重試。',
})

const STATUS_MESSAGES = Object.freeze({
  400: '查詢資料無效，請確認起點、目的地與出發時間後再試。',
  403: '伺服器拒絕這個請求（HTTP 403）。請從目前網站網址重新開啟後再試；若持續發生，請回報此狀態碼。',
  404: API_ERROR_MESSAGES.QUERY_NOT_FOUND,
  410: API_ERROR_MESSAGES.QUERY_EXPIRED,
  422: '查詢條件不符合服務範圍或指定時間規則，請調整後再試。',
  429: API_ERROR_MESSAGES.RATE_LIMITED,
})

export async function readApiError(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Proxies and CORS failures may return plain text or an empty body.
  }

  const candidateCode = payload?.error?.code
  const code = typeof candidateCode === 'string' && Object.hasOwn(API_ERROR_MESSAGES, candidateCode)
    ? candidateCode
    : undefined
  const message = API_ERROR_MESSAGES[code]
    || STATUS_MESSAGES[response.status]
    || (response.status >= 500
      ? `服務暫時無法處理請求（HTTP ${response.status}），請稍後再試。`
      : `請求失敗（HTTP ${response.status}），請稍後再試。`)
  return Object.assign(new Error(message), { code, status: response.status })
}

export function requestFailureMessage(error) {
  if (error?.code && API_ERROR_MESSAGES[error.code]) return error.message
  if (Number.isInteger(error?.status)) return error.message
  return '目前無法連線到停車查詢服務，請檢查網路連線後再試。'
}
