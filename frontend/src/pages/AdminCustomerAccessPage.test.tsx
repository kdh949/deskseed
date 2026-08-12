import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import { AdminCustomerAccessPage } from './AdminCustomerAccessPage'

const apiMocks = vi.hoisted(() => ({
  getCustomerAccessModeSetting: vi.fn(),
  updateCustomerAccessModeSetting: vi.fn(),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../api/client')
  return { ...actual, ...apiMocks }
})

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <AdminCustomerAccessPage />
    </QueryClientProvider>,
  )
}

describe('AdminCustomerAccessPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('previews operational impact before saving a versioned mode change', async () => {
    const user = userEvent.setup()
    apiMocks.getCustomerAccessModeSetting
      .mockResolvedValueOnce({
        mode: 'ANONYMOUS_ALLOWED',
        version: 0,
        updatedAt: '2026-08-10T00:00:00Z',
      })
      .mockResolvedValueOnce({
        mode: 'REGISTRATION_REQUIRED',
        version: 1,
        updatedAt: '2026-08-10T01:00:00Z',
      })
    apiMocks.updateCustomerAccessModeSetting.mockResolvedValue({
      mode: 'REGISTRATION_REQUIRED',
      version: 1,
      updatedAt: '2026-08-10T01:00:00Z',
    })
    renderPage()

    await user.click(await screen.findByRole('radio', { name: /가입 필수/ }))
    expect(
      screen.getByRole('status', { name: '변경 영향 미리보기' }),
    ).toHaveTextContent('로그인하지 않은 고객은 새 문의를 접수할 수 없습니다.')
    await user.click(screen.getByRole('button', { name: '접근 모드 저장' }))
    expect(apiMocks.updateCustomerAccessModeSetting).toHaveBeenCalledWith({
      mode: 'REGISTRATION_REQUIRED',
      expectedVersion: 0,
    })
    expect(
      await screen.findByText('고객 접근 모드를 저장했습니다.'),
    ).toBeVisible()
  })
})
