import { Link, Navigate, useRoutes, type RouteObject } from 'react-router'
import { SeedFeedbackState } from './design-system/canonical'
import { AgentShellLayout } from './features/agent-shell/AgentShellLayout'
import { AdminBusinessSchedulesPage } from './features/admin/AdminBusinessSchedulesPage'
import { AdminCustomerAccessModePage } from './features/admin/AdminCustomerAccessModePage'
import { AdminFirstReplySlaPage } from './features/admin/AdminFirstReplySlaPage'
import { AdminGroupsPage } from './features/admin/AdminGroupsPage'
import { AdminMailPage } from './features/admin/AdminMailPage'
import { AdminShellLayout } from './features/admin/AdminShellLayout'
import { AdminStaffPage } from './features/admin/AdminStaffPage'
import { AuditExplorerPage } from './features/audit/AuditExplorerPage'
import { AuditExportStatusPage } from './features/audit/AuditExportStatusPage'
import {
  AgentRoute,
  AdminRoute,
  AuditRoute,
  StaffRoute,
  StaffSessionLayout,
} from './features/staff-auth/StaffRoute'
import { CreateAgentTicketPage } from './features/ticket-create/CreateAgentTicketPage'
import { AgentTicketWorkspacePage } from './features/ticket-workspace/AgentTicketWorkspacePage'
import { AgentViewsPage } from './features/ticket-views/AgentViewsPage'
import { AgentSearchPage } from './features/ticket-search/AgentSearchPage'
import { StaffLoginPage } from './pages/StaffLoginPage'
import { ExtensionRouteGate } from './extension-host/ExtensionRouteGate'
import { frontendExtensions } from './extension-host/catalog'

function extensionRoutes(surface: 'agent' | 'admin'): RouteObject[] {
  return frontendExtensions.routesFor(surface).map((contribution) => ({
    path: contribution.path,
    element: <ExtensionRouteGate contribution={contribution} />,
  }))
}

const agentChildren: RouteObject[] = [
  { index: true, element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'home', element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'views', element: <Navigate to="/agent/views/my-open" replace /> },
  { path: 'views/:viewKey', element: <AgentViewsPage /> },
  { path: 'search', element: <AgentSearchPage /> },
  { path: 'tickets/new', element: <CreateAgentTicketPage /> },
  { path: 'tickets/:ticketNumber', element: <AgentTicketWorkspacePage /> },
  ...extensionRoutes('agent'),
  { path: '*', element: <Navigate to="/agent/views/my-open" replace /> },
]

const auditChildren: RouteObject[] = [
  { path: 'audit', element: <AuditExplorerPage /> },
  { path: 'audit/exports/:jobId', element: <AuditExportStatusPage /> },
]

const adminChildren: RouteObject[] = [
  { index: true, element: <Navigate to="operations/mail" replace /> },
  { path: 'operations/mail', element: <AdminMailPage /> },
  { path: 'staff', element: <AdminStaffPage /> },
  { path: 'groups', element: <AdminGroupsPage /> },
  {
    path: 'settings/customer-access-mode',
    element: <AdminCustomerAccessModePage />,
  },
  { path: 'business-rules/schedules', element: <AdminBusinessSchedulesPage /> },
  { path: 'business-rules/sla', element: <AdminFirstReplySlaPage /> },
  ...extensionRoutes('admin'),
  { path: '*', element: <Navigate to="operations/mail" replace /> },
]

export const appRoutes: RouteObject[] = [
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
          {
            element: <AdminRoute />,
            children: [
              {
                path: '/admin',
                element: <AdminShellLayout />,
                children: adminChildren,
              },
            ],
          },
        ],
      },
    ],
  },
  {
    path: '*',
    element: (
      <main className="seed-route-feedback">
        <SeedFeedbackState
          action={<Link to="/agent/login">상담사 로그인으로 이동</Link>}
          description="요청한 상담사 화면은 현재 제공되지 않습니다."
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
