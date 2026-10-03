import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
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
  parameters: {
    msw: {
      handlers: [
        ...handlers.filter(
          (handler) =>
            !handler.info.header.startsWith('GET /api/v1/admin/staff'),
        ),
        http.get('/api/v1/admin/staff', () =>
          HttpResponse.json([{ ...staff, role: 'SECURITY_AUDITOR' }]),
        ),
      ],
    },
  },
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
    await expect(
      canvas.getByRole('checkbox', { name: '검색어 공개' }),
    ).toBeChecked()
    await expect(
      canvas.getByRole('dialog', { name: '운영 관리자 감사 권한' }),
    ).toBeVisible()
  },
}

export const RoleRestrictedActions: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('운영 관리자')).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '감사 권한' }),
    ).not.toBeInTheDocument()
    await expect(
      canvas.getByRole('button', { name: '직원 비활성화' }),
    ).toBeVisible()
  },
}

export const CreateDraftProtection: Story = {
  play: async ({ canvas }) => {
    const trigger = await canvas.findByRole('button', { name: '직원 추가' })
    await userEvent.click(trigger)
    await userEvent.type(canvas.getByLabelText('표시 이름'), '새 직원 초안')
    await userEvent.click(
      canvas.getByRole('button', { name: '직원 계정 생성 닫기' }),
    )
    await expect(
      canvas.getByRole('dialog', { name: '저장하지 않은 직원 정보' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '계속 편집' }))
    await expect(canvas.getByLabelText('표시 이름')).toHaveValue('새 직원 초안')
    await userEvent.keyboard('{Escape}')
    await userEvent.click(
      canvas.getByRole('button', { name: '변경 사항 버리기' }),
    )
    await expect(canvas.queryByRole('dialog')).not.toBeInTheDocument()
    await expect(trigger).toHaveFocus()
    await userEvent.click(trigger)
    await expect(canvas.getByLabelText('표시 이름')).toHaveValue('')
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

export const DirectorySearch: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/staff/search', async ({ request }) => {
          const body = (await request.json()) as {
            query: string
            page: number
            interactionId: string
          }
          await expect(request.url).not.toContain(body.query)
          await expect(body.interactionId).toMatch(/^[0-9a-f-]{36}$/)
          return HttpResponse.json(
            [
              {
                ...staff,
                displayName:
                  body.page === 0 ? '검색 첫 직원' : '마지막 페이지 직원',
              },
            ],
            {
              headers: {
                'X-Page-Number': String(body.page),
                'X-Page-Size': '50',
                'X-Total-Count': '51',
                'X-Total-Pages': '2',
              },
            },
          )
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('직원 이름 또는 이메일 검색'),
      'private-name@example.test{Enter}',
    )
    await expect(await canvas.findByText('검색 결과 51명')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '다음 페이지' }))
    await expect(await canvas.findByText('마지막 페이지 직원')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '검색 초기화' }))
    await expect(await canvas.findByText('운영 관리자')).toBeVisible()
    await expect(
      canvas.getByLabelText('직원 이름 또는 이메일 검색'),
    ).toHaveValue('')
  },
}

export const SearchEmpty: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/staff/search', () => HttpResponse.json([])),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('직원 이름 또는 이메일 검색'),
      '없는 직원{Enter}',
    )
    await expect(await canvas.findByText('검색 결과가 없습니다.')).toBeVisible()
    await expect(
      canvas.getByLabelText('직원 이름 또는 이메일 검색'),
    ).toHaveValue('없는 직원')
  },
}

export const SearchError: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/staff/search', () =>
          HttpResponse.json(
            { title: 'Unavailable', status: 503 },
            { status: 503 },
          ),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('직원 이름 또는 이메일 검색'),
      '직원{Enter}',
    )
    await expect(
      await canvas.findByText('직원 검색 결과를 불러오지 못했습니다.'),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '검색 초기화' }))
    await expect(await canvas.findByText('운영 관리자')).toBeVisible()
  },
}

export const SearchDenied: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/staff/search', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('직원 이름 또는 이메일 검색'),
      '직원{Enter}',
    )
    await expect(
      await canvas.findByText('직원 검색 결과를 불러오지 못했습니다.'),
    ).toBeVisible()
  },
}

export const SearchLoading: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/staff/search', async () => {
          await delay('infinite')
          return HttpResponse.json([])
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('직원 이름 또는 이메일 검색'),
      '직원{Enter}',
    )
    await expect(await canvas.findByText('직원을 검색하는 중')).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '검색 초기화' }),
    ).toBeEnabled()
  },
}
