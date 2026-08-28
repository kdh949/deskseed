import { describe, expect, it, vi } from 'vitest'
import { consumeMagicLinkFragment } from './magicLinkFragment'

describe('customer magic-link fragment handling', () => {
  it('removes a single token fragment before returning it for POST consumption', () => {
    const replaceState = vi.fn()

    const token = consumeMagicLinkFragment({
      history: { replaceState, state: null },
      location: {
        hash: '#token=opaque-magic-link-token',
        pathname: '/customer/sign-in/consume',
        search: '',
      },
    })

    expect(replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/customer/sign-in/consume',
    )
    expect(token).toBe('opaque-magic-link-token')
  })

  it('fails closed after removing an ambiguous token fragment', () => {
    const replaceState = vi.fn()

    const token = consumeMagicLinkFragment({
      history: { replaceState, state: { route: 'customer' } },
      location: {
        hash: '#token=first&token=second',
        pathname: '/customer/sign-in/consume',
        search: '?campaign=email',
      },
    })

    expect(replaceState).toHaveBeenCalledWith(
      { route: 'customer' },
      '',
      '/customer/sign-in/consume?campaign=email',
    )
    expect(token).toBeNull()
  })
})
