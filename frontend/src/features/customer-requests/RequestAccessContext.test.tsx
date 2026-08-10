import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RequestAccessProvider, useRequestAccess } from './RequestAccessContext'

function AccessProbe() {
  const access = useRequestAccess()
  const token = access.getAccessToken(1042)

  return (
    <>
      <output aria-label="현재 조회 권한">{token ?? '없음'}</output>
      <button
        type="button"
        onClick={() => access.setAccessToken(1042, 'memory-only-token')}
      >
        권한 설정
      </button>
    </>
  )
}

describe('RequestAccessProvider', () => {
  it('keeps request grants in memory only and forgets them with the provider', async () => {
    const user = userEvent.setup()
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem')

    const view = render(
      <RequestAccessProvider>
        <AccessProbe />
      </RequestAccessProvider>,
    )

    await user.click(screen.getByRole('button', { name: '권한 설정' }))
    expect(
      screen.getByRole('status', { name: '현재 조회 권한' }),
    ).toHaveTextContent('memory-only-token')
    expect(localStorageSpy).not.toHaveBeenCalled()

    view.unmount()
    render(
      <RequestAccessProvider>
        <AccessProbe />
      </RequestAccessProvider>,
    )

    expect(
      screen.getByRole('status', { name: '현재 조회 권한' }),
    ).toHaveTextContent('없음')
  })
})
