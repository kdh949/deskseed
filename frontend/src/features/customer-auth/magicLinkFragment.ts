type LocationSnapshot = Pick<Location, 'hash' | 'pathname' | 'search'>
type HistoryAdapter = Pick<History, 'replaceState' | 'state'>

export function consumeMagicLinkFragment({
  history,
  location,
}: {
  history: HistoryAdapter
  location: LocationSnapshot
}) {
  const parameters = new URLSearchParams(location.hash.replace(/^#/, ''))
  const tokens = parameters.getAll('token')
  if (tokens.length === 0) return null

  try {
    history.replaceState(
      history.state,
      '',
      `${location.pathname}${location.search}`,
    )
  } catch {
    return null
  }

  const token = tokens.length === 1 ? (tokens.at(0) ?? null) : null
  return isMagicLinkToken(token) ? token : null
}

function isMagicLinkToken(value: string | null): value is string {
  return typeof value === 'string' && value.length >= 1 && value.length <= 256
}
