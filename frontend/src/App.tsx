import { lazy, Suspense } from 'react'
import { Navigate, Outlet, useRoutes, type RouteObject } from 'react-router'
import { AppShell } from './components/AppShell'
import { AgentShell } from './features/agent-shell/AgentShell'
import { AgentTicketWorkspacePage } from './features/ticket-workspace/AgentTicketWorkspacePage'
import { AgentSearchPage } from './features/ticket-search/AgentSearchPage'
import { AgentViewsPage } from './features/ticket-views/AgentViewsPage'
import { AdminShell } from './features/admin/AdminShell'
import { AuditShell } from './features/audit/AuditShell'
import { AuditExplorerPage } from './features/audit/AuditExplorerPage'
import {
  AgentRoute,
  AdminRoute,
  AuditRoute,
  ExternalSystemAdminRoute,
  IntegrationAdminRoute,
  StaffRoute,
  StaffSessionLayout,
} from './features/staff-auth/StaffRoute'
import { AdminGroupsPage } from './pages/AdminGroupsPage'
import { AdminStaffPage } from './pages/AdminStaffPage'
import { HomePage } from './pages/HomePage'
import { LookupPage } from './pages/LookupPage'
import { NewRequestPage } from './pages/NewRequestPage'
import { RequestDetailPage } from './pages/RequestDetailPage'
import { StaffLoginPage } from './pages/StaffLoginPage'
import { IntegrationClientsPage } from './pages/IntegrationClientsPage'
import { ExternalSystemsPage } from './pages/ExternalSystemsPage'

const FrontendSystemFixturePage = import.meta.env.DEV
  ? lazy(() =>
      import('./features/frontend-system-fixtures/FrontendSystemFixturePage').then(
        (module) => ({ default: module.FrontendSystemFixturePage }),
      ),
    )
  : null

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
            path: '/integrations',
            element: <AdminShell />,
            children: [
              {
                index: true,
                element: <Navigate to="/integrations/clients" replace />,
              },
              {
                element: <IntegrationAdminRoute />,
                children: [
                  { path: 'clients', element: <IntegrationClientsPage /> },
                ],
              },
              {
                element: <ExternalSystemAdminRoute />,
                children: [
                  { path: 'systems', element: <ExternalSystemsPage /> },
                ],
              },
              {
                path: '*',
                element: <Navigate to="/integrations/clients" replace />,
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
      {
        path: '/requests/:ticketNumber',
        element: <RequestDetailPage />,
      },
      { path: '/lookup', element: <Navigate to="/requests/lookup" replace /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]

export default function App() {
  return useRoutes(appRoutes)
}
