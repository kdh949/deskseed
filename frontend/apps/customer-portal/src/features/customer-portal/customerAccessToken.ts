const ACCESS_TOKEN_MIN_LENGTH = 32
const ACCESS_TOKEN_MAX_LENGTH = 256
const ACCESS_TOKEN_STORAGE_PREFIX = 'deskseed:customer-request-access:'

type SessionStorageAdapter = Pick<Storage, 'getItem' | 'removeItem' | 'setItem'>

type LocationSnapshot = Pick<Location, 'hash' | 'pathname' | 'search'>

type HistoryAdapter = Pick<History, 'replaceState' | 'state'>

export function requestAccessTokenStorageKey(ticketNumber: number) {
  return `${ACCESS_TOKEN_STORAGE_PREFIX}${ticketNumber}`
}

export function readRequestAccessToken(
  sessionStorage: SessionStorageAdapter,
  ticketNumber: number,
) {
  if (!isTicketNumber(ticketNumber)) return null
  const key = requestAccessTokenStorageKey(ticketNumber)
  try {
    const token = sessionStorage.getItem(key)
    if (isRequestAccessToken(token)) return token
    if (token !== null) sessionStorage.removeItem(key)
  } catch {
    // Access-token recovery is optional. A storage failure must never widen access.
  }
  return null
}

export function storeRequestAccessToken(
  sessionStorage: SessionStorageAdapter,
  ticketNumber: number,
  token: string,
) {
  if (!isTicketNumber(ticketNumber) || !isRequestAccessToken(token))
    return false
  try {
    sessionStorage.setItem(requestAccessTokenStorageKey(ticketNumber), token)
    return true
  } catch {
    return false
  }
}

export function consumeRequestAccessTokenFragment({
  history,
  location,
  sessionStorage,
  ticketNumber,
}: {
  history: HistoryAdapter
  location: LocationSnapshot
  sessionStorage: SessionStorageAdapter
  ticketNumber: number
}) {
  if (!isTicketNumber(ticketNumber)) return null
  const parameters = new URLSearchParams(location.hash.replace(/^#/, ''))
  const tokens = parameters.getAll('token')
  if (tokens.length === 0)
    return readRequestAccessToken(sessionStorage, ticketNumber)

  try {
    history.replaceState(
      history.state,
      '',
      `${location.pathname}${location.search}`,
    )
  } catch {
    // Do not use a capability if the browser could not first remove it from the URL.
    return null
  }

  const key = requestAccessTokenStorageKey(ticketNumber)
  const token = tokens.length === 1 ? (tokens.at(0) ?? null) : null
  try {
    sessionStorage.removeItem(key)
    if (!isRequestAccessToken(token)) return null
    sessionStorage.setItem(key, token)
    return token
  } catch {
    return null
  }
}

function isTicketNumber(value: number) {
  return Number.isSafeInteger(value) && value > 0
}

function isRequestAccessToken(value: string | null): value is string {
  return (
    typeof value === 'string' &&
    value.length >= ACCESS_TOKEN_MIN_LENGTH &&
    value.length <= ACCESS_TOKEN_MAX_LENGTH &&
    !hasUnsafeTokenCharacter(value)
  )
}

function hasUnsafeTokenCharacter(value: string) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0)
    return codePoint === undefined || codePoint <= 0x20 || codePoint === 0x7f
  })
}
