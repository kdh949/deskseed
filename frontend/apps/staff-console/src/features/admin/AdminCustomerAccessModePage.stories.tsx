import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { AdminCustomerAccessModePage } from './AdminCustomerAccessModePage'

const setting = {
  mode: 'ANONYMOUS_ALLOWED',
  version: 3,
  updatedAt: '2026-08-15T10:00:00Z',
}

const readyHandlers = [
  http.get('/api/v1/admin/settings/customer-access-mode', () =>
    HttpResponse.json(setting),
  ),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.put('/api/v1/admin/settings/customer-access-mode', () =>
    HttpResponse.json({
      ...setting,
      mode: 'REGISTRATION_OPTIONAL',
      version: 4,
    }),
  ),
]

const meta = {
  title: '06 Admin/Admin Customer Access Mode Page',
  component: AdminCustomerAccessModePage,
  parameters: {
    docs: {
      description: {
        component:
          'REQ-TKT-004 관리자 고객 접근 모드 route입니다. body의 expectedVersion과 CSRF/expected-actor guard를 사용하고, 409에서 사용자의 선택을 지우지 않습니다.',
      },
    },
    msw: { handlers: readyHandlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminCustomerAccessModePage>

export default meta
type Story = StoryObj<typeof meta>

export const SavePolicy: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '고객 접근 모드' }),
    ).toBeVisible()
    await userEvent.selectOptions(
      canvas.getByLabelText('고객 접근 모드'),
      'REGISTRATION_OPTIONAL',
    )
    await userEvent.click(canvas.getByRole('button', { name: '정책 저장' }))
    await expect(
      await canvas.findByText('고객 접근 정책을 저장했습니다.'),
    ).toBeVisible()
  },
}

export const SaveLocksSelection: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/settings/customer-access-mode', () =>
          HttpResponse.json(setting),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'storybook-csrf',
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.put('/api/v1/admin/settings/customer-access-mode', async () => {
          await delay(250)
          return HttpResponse.json({
            ...setting,
            mode: 'REGISTRATION_OPTIONAL',
            version: 4,
          })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '고객 접근 모드' }),
    ).toBeVisible()
    await userEvent.selectOptions(
      canvas.getByLabelText('고객 접근 모드'),
      'REGISTRATION_OPTIONAL',
    )
    await userEvent.click(canvas.getByRole('button', { name: '정책 저장' }))
    await expect(canvas.getByLabelText('고객 접근 모드')).toBeDisabled()
    await expect(
      canvas.getByRole('button', { name: '서버 값 새로고침' }),
    ).toBeDisabled()
    await expect(
      await canvas.findByText('고객 접근 정책을 저장했습니다.'),
    ).toBeVisible()
  },
}

export const ConflictPreservesSelection: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/settings/customer-access-mode', () =>
          HttpResponse.json(setting),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'storybook-csrf',
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.put('/api/v1/admin/settings/customer-access-mode', () =>
          HttpResponse.json({ status: 409 }, { status: 409 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '고객 접근 모드' }),
    ).toBeVisible()
    await userEvent.selectOptions(
      canvas.getByLabelText('고객 접근 모드'),
      'REGISTRATION_REQUIRED',
    )
    await userEvent.click(canvas.getByRole('button', { name: '정책 저장' }))
    await expect(
      await canvas.findByText('다른 관리자가 고객 접근 정책을 변경했습니다.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText('고객 접근 모드')).toHaveValue(
      'REGISTRATION_REQUIRED',
    )
  },
}
