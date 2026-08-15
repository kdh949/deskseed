import { Link, Navigate, useRoutes, type RouteObject } from 'react-router'
import { ScreenState } from './design-system'
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
import { CustomerAccountRoute } from './features/customer-auth/CustomerAccountRoute'
import { CustomerMagicLinkConsumePage } from './features/customer-auth/CustomerMagicLinkConsumePage'
import { CustomerRouteLayout } from './features/customer-auth/CustomerRouteLayout'
import { CustomerSignInPage } from './features/customer-auth/CustomerSignInPage'
import { CustomerHomePage } from './features/customer-portal/CustomerHomePage'
import { CustomerRequestDetailPage } from './features/customer-portal/CustomerRequestDetailPage'
import { CustomerRequestListPage } from './features/customer-portal/CustomerRequestListPage'
import { AnonymousRequestDetailPage } from './features/customer-requests/AnonymousRequestDetailPage'
import { CustomerRequestCreatePage } from './features/customer-requests/CustomerRequestCreatePage'
import { CustomerRequestLookupPage } from './features/customer-requests/CustomerRequestLookupPage'
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
import { StaffLoginPage } from './pages/StaffLoginPage'

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
  { path: '*', element: <Navigate to="operations/mail" replace /> },
]

const customerChildren: RouteObject[] = [
  { index: true, element: <CustomerHomePage /> },
  { path: 'requests/new', element: <CustomerRequestCreatePage /> },
  { path: 'requests/lookup', element: <CustomerRequestLookupPage /> },
  {
    path: 'requests/:ticketNumber',
    element: <AnonymousRequestDetailPage />,
  },
  { path: 'customer/sign-in', element: <CustomerSignInPage /> },
  {
    path: 'customer/sign-in/consume',
    element: <CustomerMagicLinkConsumePage />,
  },
  {
    path: 'account',
    element: <CustomerAccountRoute />,
    children: [
      { path: 'requests', element: <CustomerRequestListPage /> },
      {
        path: 'requests/:ticketNumber',
        element: <CustomerRequestDetailPage />,
      },
    ],
  },
]

export const appRoutes: RouteObject[] = [
  {
    element: <CustomerRouteLayout />,
    children: customerChildren,
  },
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
      <main className="workspace-error-state">
        <ScreenState
          action={<Link to="/">고객 지원 홈으로 이동</Link>}
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
