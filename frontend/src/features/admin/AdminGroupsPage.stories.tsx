import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { AdminGroupsPage } from './AdminGroupsPage'

const group = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '결제 지원',
  status: 'ACTIVE',
  memberCount: 1,
}

const member = {
  groupId: group.id,
  staffId: '22222222-2222-4222-8222-222222222222',
  staffDisplayName: '상담사 A',
  role: 'AGENT',
}

const staff = {
  id: member.staffId,
  email: 'agent@example.test',
  displayName: member.staffDisplayName,
  role: member.role,
  status: 'ACTIVE',
  memberships: [{ id: group.id, name: group.name }],
  auditAuthorities: [],
  lastLoginAt: null,
}

const secondGroup = {
  id: '33333333-3333-4333-8333-333333333333',
  name: '배송 지원',
  status: 'ACTIVE',
  memberCount: 1,
}

const secondMember = {
  groupId: secondGroup.id,
  staffId: '44444444-4444-4444-8444-444444444444',
  staffDisplayName: '상담사 B',
  role: 'AGENT',
}

const secondStaff = {
  id: secondMember.staffId,
  email: 'agent-b@example.test',
  displayName: secondMember.staffDisplayName,
  role: secondMember.role,
  status: 'ACTIVE',
  memberships: [{ id: secondGroup.id, name: secondGroup.name }],
  auditAuthorities: [],
  lastLoginAt: null,
}

const handlers = [
  http.get('/api/v1/admin/groups', () => HttpResponse.json([group])),
  http.get(`/api/v1/admin/groups/${group.id}/members`, () =>
    HttpResponse.json([member]),
  ),
  http.get('/api/v1/admin/staff', () => HttpResponse.json([staff])),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post('/api/v1/admin/groups', () =>
    HttpResponse.json(group, { status: 201 }),
  ),
  http.patch(`/api/v1/admin/groups/${group.id}`, () =>
    HttpResponse.json(group),
  ),
  http.post(`/api/v1/admin/groups/${group.id}/members`, () =>
    HttpResponse.json(member, { status: 201 }),
  ),
  http.delete(
    `/api/v1/admin/groups/${group.id}/members/${member.staffId}`,
    () => new HttpResponse(null, { status: 204 }),
  ),
  http.delete(
    `/api/v1/admin/groups/${group.id}`,
    () => new HttpResponse(null, { status: 204 }),
  ),
]

const meta = {
  title: '06 Admin/Admin Groups Page',
  component: AdminGroupsPage,
  parameters: {
    docs: {
      description: {
        component:
          'REQ-PERM-002 그룹/구성원 관리 route입니다. 실제 그룹, 멤버십, 활성 직원 projection을 소비하고 모든 mutation은 server-side organization consistency guard로 확정합니다.',
      },
    },
    msw: { handlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminGroupsPage>

export default meta
type Story = StoryObj<typeof meta>

export const ManageMembers: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '그룹' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '그룹 관리' }))
    await expect(
      await canvas.findByRole('heading', { name: '결제 지원' }),
    ).toBeVisible()
    await expect(await canvas.findByText('상담사 A')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '구성원 제거' }))
    await expect(
      canvas.getByRole('group', { name: '구성원 제거 최종 확인' }),
    ).toBeVisible()
  },
}

export const SwitchingGroupsClearsMemberRemoval: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/groups', () =>
          HttpResponse.json([group, secondGroup]),
        ),
        http.get(`/api/v1/admin/groups/${group.id}/members`, () =>
          HttpResponse.json([member]),
        ),
        http.get(`/api/v1/admin/groups/${secondGroup.id}/members`, () =>
          HttpResponse.json([secondMember]),
        ),
        http.get('/api/v1/admin/staff', () =>
          HttpResponse.json([staff, secondStaff]),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    const manageButtons = await canvas.findAllByRole('button', {
      name: '그룹 관리',
    })
    await userEvent.click(manageButtons[0]!)
    await userEvent.click(
      await canvas.findByRole('button', { name: '구성원 제거' }),
    )
    await expect(
      canvas.getByRole('group', { name: '구성원 제거 최종 확인' }),
    ).toBeVisible()
    await userEvent.click(
      canvas.getAllByRole('button', { name: '그룹 관리' })[1]!,
    )
    await expect(
      await canvas.findByRole('heading', { name: secondGroup.name }),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('group', { name: '구성원 제거 최종 확인' }),
    ).not.toBeInTheDocument()
  },
}

export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [http.get('/api/v1/admin/groups', () => HttpResponse.json([]))],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('등록된 지원 그룹이 없습니다.'),
    ).toBeVisible()
  },
}
