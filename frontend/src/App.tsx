import { lazy, Suspense } from 'react'
import { Link, Navigate, useRoutes, type RouteObject } from 'react-router'
import { ScreenState } from './design-system'
import { AgentShellLayout } from './features/agent-shell/AgentShellLayout'
import { AuditExplorerPage } from './features/audit/AuditExplorerPage'
import {
  AgentRoute,
  AuditRoute,
  StaffRoute,
  StaffSessionLayout,
} from './features/staff-auth/StaffRoute'
import { CreateAgentTicketPage } from './features/ticket-create/CreateAgentTicketPage'
import { AgentTicketWorkspacePage } from './features/ticket-workspace/AgentTicketWorkspacePage'
import { AgentViewsPage } from './features/ticket-views/AgentViewsPage'
import { StaffLoginPage } from './pages/StaffLoginPage'

const FrontendSystemFixturePage = import.meta.env.DEV
  ? lazy(() =>
      import('./features/frontend-system-fixtures/FrontendSystemFixturePage').then(
        (module) => ({ default: module.FrontendSystemFixturePage }),
      ),
    )
  : null

const agentChildren: RouteObject[] = [
  { index: true, element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'home', element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'views', element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'views/:viewKey', element: <AgentViewsPage /> },
  { path: 'tickets/new', element: <CreateAgentTicketPage /> },
  { path: 'tickets/:ticketNumber', element: <AgentTicketWorkspacePage /> },
  { path: '*', element: <Navigate to="/agent/views/my-open" replace /> },
]

const auditChildren: RouteObject[] = [
  { path: 'audit', element: <AuditExplorerPage /> },
]

export const appRoutes: RouteObject[] = [
  ...(import.meta.env.DEV
    ? [
        {
          path: '/__fixtures__/frontend-system/:fixtureName',
          element: FrontendSystemFixturePage ? (
            <Suspense fallback={null}>
              <FrontendSystemFixturePage />
            </Suspense>
          ) : null,
        },
      ]
    : []),
  {
    element: <StaffSessionLayout />,
    children: [
      { path: '/agent/login', element: <StaffLoginPage /> },
      {
        element: <StaffRoute />,
        children: [
          {
            element: <AgentRoute />,
            children: [
              {
                path: '/agent',
                element: <AgentShellLayout />,
                children: agentChildren,
              },
            ],
          },
          {
            element: <AuditRoute />,
            children: [
              {
                path: '/agent',
                element: <AgentShellLayout />,
                children: auditChildren,
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '/', element: <Navigate to="/agent/views/my-open" replace /> },
  {
    path: '*',
    element: (
      <main className="workspace-error-state">
        <ScreenState
          action={<Link to="/agent/views/my-open">티켓 큐로 이동</Link>}
          description="요청한 프론트엔드 화면은 현재 제공되지 않습니다."
          kind="not-found"
          title="페이지를 찾을 수 없습니다."
        />
      </main>
    ),
  },
]

export default function App() {
  return useRoutes(appRoutes)
}
