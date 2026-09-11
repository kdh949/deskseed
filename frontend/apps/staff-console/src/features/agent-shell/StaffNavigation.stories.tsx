import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { useEffect } from 'react'
import App from '../../App'
import { Navigate, Route, Routes, useNavigate } from 'react-router'
import { expect, userEvent } from 'storybook/test'
import { AgentShellLayout } from './AgentShellLayout'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { StaffLoginPage } from '../../pages/StaffLoginPage'
import { mswHandlers } from '../../../.storybook/msw-handlers'
const staff = {
  id: 'staff-nav',
  email: 'staff@example.test',
  displayName: '감사 담당자',
  role: 'SECURITY_AUDITOR',
  capabilities: ['AUDIT_VIEW'],
}
const meta = {
  title: '07 Screens/Staff Navigation',
  component: AgentShellLayout,
  tags: ['autodocs'],
  parameters: {
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () => HttpResponse.json(staff)),
        ...mswHandlers,
      ],
    },
  },
  render: () => (
    <StaffSessionProvider>
      <Routes>
        <Route path="/agent/login" element={<StaffLoginPage />} />
        <Route path="/agent" element={<AgentShellLayout />}>
          <Route path="audit" element={<h1>감사 기록</h1>} />
          <Route path="views/my-open" element={<h1>내 티켓</h1>} />
        </Route>
        <Route
          path="/admin/operations/mail"
          element={<h1>관리자 메일 운영</h1>}
        />
        <Route path="*" element={<Navigate to="/agent/login" replace />} />
      </Routes>
    </StaffSessionProvider>
  ),
} satisfies Meta<typeof AgentShellLayout>
export default meta
type Story = StoryObj<typeof meta>
export const Auditor: Story = {
  render: () => (
    <StaffSessionProvider>
      <Routes>
        <Route element={<AgentShellLayout />}>
          <Route path="*" element={<h1>감사 기록</h1>} />
        </Route>
      </Routes>
    </StaffSessionProvider>
  ),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('감사 담당자')).toBeVisible()
    const header = canvas.getByRole('banner')
    await expect(getComputedStyle(header).position).toBe('sticky')
    await expect(getComputedStyle(header).gridArea).toBe('topbar')
    await expect(header.getBoundingClientRect().height).toBeGreaterThan(0)
    await expect(
      canvas.queryByRole('button', { name: '티켓' }),
    ).not.toBeInTheDocument()
    await expect(
      canvas.queryByRole('button', { name: /검색/ }),
    ).not.toBeInTheDocument()
    await expect(
      canvas.queryByRole('button', { name: /알림/ }),
    ).not.toBeInTheDocument()
    await userEvent.keyboard('{Control>}k{/Control}')
    await expect(
      canvas.getByRole('heading', { name: '감사 기록' }),
    ).toBeVisible()
  },
}
export const AdminEntry: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () =>
          HttpResponse.json({
            ...staff,
            displayName: '운영 관리자',
            role: 'ADMIN',
            capabilities: ['AGENT_WORKSPACE', 'ADMIN_MANAGE'],
          }),
        ),
        ...mswHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    const entry = await canvas.findByRole('button', { name: '관리자 운영' })
    await expect(
      canvas.queryByRole('button', { name: '감사' }),
    ).not.toBeInTheDocument()
    await userEvent.click(entry)
    await expect(
      await canvas.findByRole('heading', { name: '관리자 메일 운영' }),
    ).toBeVisible()
  },
}

function StaffRouteExample({ path }: { path: string }) {
  const navigate = useNavigate()
  useEffect(() => {
    void navigate(path, { replace: true })
  }, [navigate, path])
  return <App />
}
export const AuditorNotFound: Story = {
  render: () => <StaffRouteExample path="/agent/audit/typo" />,
  play: async ({ canvas, canvasElement }) => {
    await expect(
      await canvas.findByRole('heading', {
        name: '페이지를 찾을 수 없습니다.',
      }),
    ).toBeVisible()
    await expect(canvas.getAllByRole('main')).toHaveLength(1)
    await expect(canvasElement.querySelector('main main')).toBeNull()
    await expect(
      canvas.queryByText('상담사 작업 공간 권한이 필요합니다.'),
    ).not.toBeInTheDocument()
  },
}

export const FailedAccountSwitch: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/me', () => HttpResponse.json(staff)),
        http.delete(
          '/api/v1/agent/session',
          () => new HttpResponse(null, { status: 503 }),
        ),
        ...mswHandlers,
      ],
    },
  },
  render: () => <StaffRouteExample path="/agent/views/my-open" />,
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '다른 계정으로 로그인' }),
    )
    await expect(
      await canvas.findByText('로그아웃하지 못했습니다. 다시 시도해 주세요.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '다른 계정으로 로그인' }),
    ).toBeEnabled()
    await expect(
      canvas.queryByRole('heading', { name: 'Deskseed 로그인' }),
    ).not.toBeInTheDocument()
  },
}
