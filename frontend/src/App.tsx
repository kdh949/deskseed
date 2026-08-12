import { lazy, Suspense } from 'react'
import { Navigate, Outlet, useRoutes, type RouteObject } from 'react-router'
import { AppShell } from './components/AppShell'
import { AdminShell } from './features/admin/AdminShell'
import { AgentShell } from './features/agent-shell/AgentShell'
import { AuditExplorerPage } from './features/audit/AuditExplorerPage'
import { AuditShell } from './features/audit/AuditShell'
import {
  CustomerRoute,
  CustomerSessionLayout,
} from './features/customer-auth/CustomerRoute'
import { AgentSearchPage } from './features/ticket-search/AgentSearchPage'
import { AgentViewsPage } from './features/ticket-views/AgentViewsPage'
import { AgentTicketWorkspacePage } from './features/ticket-workspace/AgentTicketWorkspacePage'
import {
  AdminRoute,
  AgentRoute,
  AuditRoute,
  StaffRoute,
  StaffSessionLayout,
} from './features/staff-auth/StaffRoute'
import { AdminCustomerAccessPage } from './pages/AdminCustomerAccessPage'
import { AdminGroupsPage } from './pages/AdminGroupsPage'
import { AdminStaffPage } from './pages/AdminStaffPage'
import {
  CustomerMagicLinkConsumePage,
  CustomerSignInPage,
} from './pages/CustomerSignInPage'
import {
  CustomerRequestDetailPage,
  CustomerRequestsPage,
} from './pages/CustomerRequestsPage'
import { HomePage } from './pages/HomePage'
import { LookupPage } from './pages/LookupPage'
import { NewRequestPage } from './pages/NewRequestPage'
import { RequestDetailPage } from './pages/RequestDetailPage'
import { StaffLoginPage } from './pages/StaffLoginPage'

const FrontendSystemFixturePage = import.meta.env.DEV
  ? lazy(() =>
      import('./features/frontend-system-fixtures/FrontendSystemFixturePage').then(
        (module) => ({ default: module.FrontendSystemFixturePage }),
      ),
    )
  : null

export function createAppRoutes(
  customerMagicLinkToken: string | null,
): RouteObject[] {
  return [
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
                  element: <AgentShell />,
                  children: [
                    {
                      index: true,
                      element: <Navigate to="/agent/views/my-open" replace />,
                    },
                    {
                      path: 'home',
                      element: <Navigate to="/agent/views/my-open" replace />,
                    },
                    {
                      path: 'views',
                      element: <Navigate to="/agent/views/my-open" replace />,
                    },
                    { path: 'views/:viewKey', element: <AgentViewsPage /> },
                    { path: 'search', element: <AgentSearchPage /> },
                    {
                      path: 'tickets/:ticketNumber',
                      element: <AgentTicketWorkspacePage />,
                    },
                    {
                      path: '*',
                      element: <Navigate to="/agent/views/my-open" replace />,
                    },
                  ],
                },
              ],
            },
            {
              element: <AdminRoute />,
              children: [
                {
                  path: '/admin',
                  element: <AdminShell />,
                  children: [
                    {
                      index: true,
                      element: <Navigate to="/admin/staff" replace />,
                    },
                    { path: 'staff', element: <AdminStaffPage /> },
                    { path: 'groups', element: <AdminGroupsPage /> },
                    {
                      path: 'access/customer-mode',
                      element: <AdminCustomerAccessPage />,
                    },
                    {
                      path: '*',
                      element: <Navigate to="/admin/staff" replace />,
                    },
                  ],
                },
              ],
            },
            {
              element: <AuditRoute />,
              children: [
                {
                  path: '/audit',
                  element: <AuditShell />,
                  children: [
                    {
                      index: true,
                      element: <Navigate to="/audit/activity" replace />,
                    },
                    { path: 'activity', element: <AuditExplorerPage /> },
                    {
                      path: '*',
                      element: <Navigate to="/audit/activity" replace />,
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
    },
    {
      element: (
        <AppShell>
          <Outlet />
        </AppShell>
      ),
      children: [
        { index: true, element: <HomePage /> },
        { path: '/requests/new', element: <NewRequestPage /> },
        { path: '/requests/lookup', element: <LookupPage /> },
        { path: '/customer/sign-in', element: <CustomerSignInPage /> },
        {
          path: '/customer/sign-in/consume',
          element: (
            <CustomerMagicLinkConsumePage token={customerMagicLinkToken} />
          ),
        },
        { path: '/requests/:ticketNumber', element: <RequestDetailPage /> },
        {
          element: <CustomerSessionLayout />,
          children: [
            {
              element: <CustomerRoute />,
              children: [
                {
                  path: '/account/requests',
                  element: <CustomerRequestsPage />,
                },
                {
                  path: '/account/requests/:ticketNumber',
                  element: <CustomerRequestDetailPage />,
                },
              ],
            },
          ],
        },
        {
          path: '/lookup',
          element: <Navigate to="/requests/lookup" replace />,
        },
        { path: '*', element: <Navigate to="/" replace /> },
      ],
    },
  ]
}

export const appRoutes = createAppRoutes(null)

export default function App() {
  return useRoutes(appRoutes)
}
