import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { Route, Routes, useLocation } from 'react-router'
import { expect } from 'storybook/test'
import { AgentShellLayout } from '../../features/agent-shell/AgentShellLayout'
import { StaffSessionProvider } from '../../features/staff-auth/StaffSessionContext'
import { mswHandlers } from '../../../.storybook/msw-handlers'
const notificationId = '11111111-1111-4111-8111-111111111111'
function Destination() {
  const location = useLocation()
  return (
    <section>
      <h1>티켓 #1042</h1>
      <p>
        {location.pathname}
        {location.hash}
      </p>
    </section>
  )
}
const meta = {
  title: '07 Screens/Rule Notifications',
  component: AgentShellLayout,
  parameters: {
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            id: 'agent-1',
            email: 'agent@example.test',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
        ),
        http.get('/api/v1/agent/notifications', () =>
          HttpResponse.json({
            items: [
              {
                id: notificationId,
                type: 'UNASSIGNED_TICKET_ALERT',
                ticketNumber: 1042,
                noteId: null,
                actor: {
                  id: '22222222-2222-4222-8222-222222222222',
                  type: 'TRIGGER',
                  displayName: '미배정 재답변 알림',
                },
                createdAt: '2026-09-08T00:00:00Z',
                readAt: null,
              },
            ],
            unreadCount: 1,
            nextCursor: null,
          }),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'storybook-csrf',
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.put(
          '/api/v1/agent/notifications/:id/read',
          ({ params, request }) => {
            expect(params.id).toBe(notificationId)
            expect(request.headers.get('X-CSRF-TOKEN')).toBe('storybook-csrf')
            return new HttpResponse(null, { status: 204 })
          },
        ),
        ...mswHandlers,
      ],
    },
  },
  render: () => (
    <StaffSessionProvider>
      <Routes>
        <Route element={<AgentShellLayout />} path="/agent">
          <Route path="views/my-open" element={<h1>상담 대기</h1>} />
          <Route path="tickets/:number" element={<Destination />} />
        </Route>
      </Routes>
    </StaffSessionProvider>
  ),
} satisfies Meta<typeof AgentShellLayout>
export default meta
type Story = StoryObj<typeof meta>
export const OpenUnassignedTicket: Story = {
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('heading', { name: '상담 대기' })
    await userEvent.click(await canvas.findByRole('button', { name: /알림/ }))
    await expect(
      await canvas.findByText('그룹에 담당자가 없는 티켓이 있습니다'),
    ).toBeVisible()
    await expect(
      canvas.queryByText(/회원님을 멘션했습니다/),
    ).not.toBeInTheDocument()
    await userEvent.click(
      canvas.getByText('그룹에 담당자가 없는 티켓이 있습니다'),
    )
    await expect(
      await canvas.findByRole('heading', { name: '티켓 #1042' }),
    ).toBeVisible()
    await expect(
      canvas.getByText('/agent/tickets/1042', { exact: true }),
    ).toBeVisible()
  },
}
