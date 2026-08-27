import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { AdminStaffPage } from './AdminStaffPage'

const staff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'admin@example.test',
  displayName: '운영 관리자',
  role: 'ADMIN',
  status: 'ACTIVE',
  memberships: [
    { id: '22222222-2222-4222-8222-222222222222', name: '결제 지원' },
  ],
  auditAuthorities: ['AUDIT_EXPORT'],
  lastLoginAt: '2026-08-15T09:00:00Z',
}

const handlers = [
  http.get('/api/v1/admin/staff', () => HttpResponse.json([staff])),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post('/api/v1/admin/staff', () =>
    HttpResponse.json(staff, { status: 201 }),
  ),
  http.put(
    '/api/v1/admin/staff/:staffId/audit-authorities/:authority',
    () => new HttpResponse(null, { status: 204 }),
  ),
  http.delete(
    '/api/v1/admin/staff/:staffId/audit-authorities/:authority',
    () => new HttpResponse(null, { status: 204 }),
  ),
  http.delete(
    '/api/v1/admin/staff/:staffId',
    () => new HttpResponse(null, { status: 204 }),
  ),
]

const meta = {
  title: '06 Admin/Admin Staff Page',
  component: AdminStaffPage,
  parameters: {
    docs: {
      description: {
        component:
          'REQ-PERM-002 직원 관리 route입니다. 실제 ADMIN staff operation과 CSRF/expected actor client를 사용하며 초기 password는 성공 후 화면 state에서 제거합니다.',
      },
    },
    msw: { handlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminStaffPage>

export default meta
type Story = StoryObj<typeof meta>

export const ManageAccount: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '직원' }),
    ).toBeVisible()
    await expect(canvas.getByText('운영 관리자')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '감사 권한' }))
    await expect(
      canvas.getByRole('heading', { name: '운영 관리자 감사 권한' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('checkbox', { name: '검색어 공개' }))
  },
}

export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [http.get('/api/v1/admin/staff', () => HttpResponse.json([]))],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('등록된 직원 계정이 없습니다.'),
    ).toBeVisible()
  },
}
