import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RequestAccessProvider, useRequestAccess } from './RequestAccessContext'

function AccessProbe() {
  const access = useRequestAccess()
  const token = access.getAccessToken(1042)
  const revision = access.getGrantRevision(1042)

  return (
    <>
      <output aria-label="현재 조회 권한">{token ?? '없음'}</output>
      <output aria-label="현재 권한 revision">{revision ?? '없음'}</output>
      <button
        type="button"
        onClick={() => access.setAccessToken(1042, 'memory-only-token')}
      >
        권한 설정
      </button>
      <button
        type="button"
        onClick={() => access.setAccessToken(1042, 'replacement-memory-token')}
      >
        권한 교체
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
    expect(
      screen.getByRole('status', { name: '현재 권한 revision' }),
    ).toHaveTextContent('1')

    await user.click(screen.getByRole('button', { name: '권한 교체' }))
    expect(
      screen.getByRole('status', { name: '현재 권한 revision' }),
    ).toHaveTextContent('2')

    view.unmount()
    render(
      <RequestAccessProvider>
        <AccessProbe />
      </RequestAccessProvider>,
    )

    expect(
      screen.getByRole('status', { name: '현재 조회 권한' }),
    ).toHaveTextContent('없음')
    expect(
      screen.getByRole('status', { name: '현재 권한 revision' }),
    ).toHaveTextContent('없음')
  })
})
