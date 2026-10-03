import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import {
  article,
  category,
  revision,
  section,
} from '../../extensions/knowledge-workflow/fixtures'
import { AdminCustomerReadinessPanel } from './AdminCustomerReadinessPanel'

const announcement = { ...section, slug: 'announcements', title: '공지사항' }
const published = {
  ...article,
  lifecycle: 'PUBLISHED',
  currentPublishedRevision: revision,
}
const policy = {
  id: category.id,
  policyKey: 'registration',
  context: 'REGISTRATION',
  lifecycle: 'PUBLISHED',
  aggregateVersion: 1,
  publishedVersion: 1,
  required: true,
  displayOrder: 0,
  createdAt: '2026-10-03T00:00:00Z',
  updatedAt: '2026-10-03T00:00:00Z',
}
const policyPage = {
  items: [policy],
  page: 0,
  size: 20,
  totalCount: 1,
  totalPages: 1,
}
const handlers = {
  policies: http.get(
    '/api/v1/admin/customer-consent-policies',
    ({ request }) => {
      const url = new URL(request.url)
      expect(url.searchParams.get('context')).toBe('REGISTRATION')
      expect(url.searchParams.get('lifecycle')).toBe('PUBLISHED')
      return HttpResponse.json(policyPage)
    },
  ),
  categories: http.get('/api/v1/admin/knowledge/categories', () =>
    HttpResponse.json([category]),
  ),
  sections: http.get('/api/v1/admin/knowledge/sections', () =>
    HttpResponse.json([announcement]),
  ),
  articles: http.get('/api/v1/admin/knowledge/articles', ({ request }) => {
    const url = new URL(request.url)
    expect(url.searchParams.get('sectionId')).toBe(announcement.id)
    expect(url.searchParams.get('audience')).toBe('PUBLIC')
    expect(url.searchParams.get('lifecycle')).toBe('PUBLISHED')
    return HttpResponse.json({ items: [published], nextCursor: null })
  }),
}
const meta = {
  title: '06 Admin/Admin Customer Readiness Panel',
  component: AdminCustomerReadinessPanel,
  args: { mode: 'REGISTRATION_REQUIRED' },
  parameters: {
    msw: { handlers },
    docs: {
      description: {
        component:
          '가입 정책 metadata와 활성 KB 계층/공개 발행 문서를 명시적 읽기 작업으로 확인합니다. 가입·메일 E2E 성공이나 설정 변경을 의미하지 않습니다.',
      },
    },
  },
  decorators: [
    (Story) => (
      <main className="admin-page">
        <h1>고객 접근 모드</h1>
        <Story />
      </main>
    ),
  ],
  tags: ['autodocs'],
} satisfies Meta<typeof AdminCustomerReadinessPanel>
export default meta
type Story = StoryObj<typeof meta>

export const Initial: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    ).toBeEnabled()
    await expect(
      canvas.queryByText('가입 정책 1개 발행됨'),
    ).not.toBeInTheDocument()
  },
}
export const Ready: Story = {
  play: async ({ canvas }) => {
    const button = canvas.getByRole('button', {
      name: '준비 상태 확인',
    })
    button.focus()
    await userEvent.keyboard('{Enter}')
    await expect(await canvas.findByText('가입 정책 1개 발행됨')).toBeVisible()
    await expect(
      await canvas.findByText('공개 공지가 발행되어 있습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('link', { name: '고객 동의 정책 관리' }),
    ).toHaveAttribute('href', '/admin/customer-consent-policies')
    await expect(
      canvas.getByRole('link', { name: '지식 문서 관리' }),
    ).toHaveAttribute('href', '/admin/knowledge')
  },
}
export const MissingConfiguration: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        policies: http.get('/api/v1/admin/customer-consent-policies', () =>
          HttpResponse.json({
            ...policyPage,
            items: [],
            totalCount: 0,
            totalPages: 0,
          }),
        ),
        sections: http.get('/api/v1/admin/knowledge/sections', () =>
          HttpResponse.json([]),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('가입 정책 발행이 필요합니다.'),
    ).toBeVisible()
    await expect(await canvas.findByText('공지 섹션이 없습니다.')).toBeVisible()
  },
}
export const InactiveParent: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        categories: http.get('/api/v1/admin/knowledge/categories', () =>
          HttpResponse.json([{ ...category, active: false }]),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('공지의 상위 주제가 비활성 상태입니다.'),
    ).toBeVisible()
  },
}
export const InactiveSection: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        sections: http.get('/api/v1/admin/knowledge/sections', () =>
          HttpResponse.json([{ ...announcement, active: false }]),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('공지 섹션이 비활성 상태입니다.'),
    ).toBeVisible()
  },
}
export const NoPublicPublication: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        articles: http.get('/api/v1/admin/knowledge/articles', () =>
          HttpResponse.json({ items: [], nextCursor: null }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('누구나 읽을 수 있는 발행 공지가 없습니다.'),
    ).toBeVisible()
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        policies: http.get('/api/v1/admin/customer-consent-policies', () =>
          HttpResponse.json({}, { status: 403 }),
        ),
        categories: http.get('/api/v1/admin/knowledge/categories', () =>
          HttpResponse.json({}, { status: 403 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('가입 약관 준비 상태를 확인할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      await canvas.findByText('공지 준비 상태를 확인할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText('공지 섹션이 없습니다.'),
    ).not.toBeInTheDocument()
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        policies: http.get(
          '/api/v1/admin/customer-consent-policies',
          async () => {
            await delay('infinite')
            return HttpResponse.json(policyPage)
          },
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('가입 약관을 확인하고 있습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '준비 상태 확인 중…' }),
    ).toBeDisabled()
  },
}
let attempts = 0
export const FailureRecovery: Story = {
  beforeEach: () => {
    attempts = 0
  },
  parameters: {
    msw: {
      handlers: {
        ...handlers,
        articles: http.get('/api/v1/admin/knowledge/articles', () =>
          ++attempts === 1
            ? HttpResponse.json({}, { status: 503 })
            : HttpResponse.json({ items: [published], nextCursor: null }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 확인' }),
    )
    await expect(
      await canvas.findByText('공지 준비 상태를 확인할 수 없습니다.'),
    ).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: '준비 상태 다시 확인' }),
    )
    await expect(
      await canvas.findByText('공개 공지가 발행되어 있습니다.'),
    ).toBeVisible()
  },
}
