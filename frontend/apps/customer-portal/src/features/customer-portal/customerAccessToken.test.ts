import { describe, expect, it, vi } from 'vitest'
import {
  consumeRequestAccessTokenFragment,
  readRequestAccessToken,
  requestAccessTokenStorageKey,
} from './customerAccessToken'

const token = 'a'.repeat(43)

function storage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    removeItem: (key: string) => values.delete(key),
    setItem: (key: string, value: string) => values.set(key, value),
  }
}

describe('customer request access token storage', () => {
  it('removes a fragment before retaining its valid token in ticket-scoped session storage', () => {
    const sessionStorage = storage()
    const replaceState = vi.fn()

    const captured = consumeRequestAccessTokenFragment({
      history: { replaceState, state: { navigation: 'test' } },
      location: {
        hash: `#token=${token}`,
        pathname: '/requests/1042',
        search: '',
      },
      sessionStorage,
      ticketNumber: 1042,
    })

    expect(captured).toBe(token)
    expect(replaceState).toHaveBeenCalledWith(
      { navigation: 'test' },
      '',
      '/requests/1042',
    )
    expect(readRequestAccessToken(sessionStorage, 1042)).toBe(token)
    expect(readRequestAccessToken(sessionStorage, 1043)).toBeNull()
  })

  it('removes malformed token fragments without retaining them', () => {
    const sessionStorage = storage()
    const replaceState = vi.fn()

    const captured = consumeRequestAccessTokenFragment({
      history: { replaceState, state: null },
      location: {
        hash: '#token=too-short',
        pathname: '/requests/1042',
        search: '?from=email',
      },
      sessionStorage,
      ticketNumber: 1042,
    })

    expect(captured).toBeNull()
    expect(replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/requests/1042?from=email',
    )
    expect(
      sessionStorage.getItem(requestAccessTokenStorageKey(1042)),
    ).toBeNull()
  })
})
