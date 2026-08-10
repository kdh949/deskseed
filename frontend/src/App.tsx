import { Navigate, Outlet, useRoutes, type RouteObject } from 'react-router'
import { AppShell } from './components/AppShell'
import { AgentShell } from './features/agent-shell/AgentShell'
import { AdminShell } from './features/admin/AdminShell'
import {
  AdminRoute,
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

export const appRoutes: RouteObject[] = [
  {
    element: <StaffSessionLayout />,
    children: [
      { path: '/agent/login', element: <StaffLoginPage /> },
      {
        element: <StaffRoute />,
        children: [
          { path: '/agent/*', element: <AgentShell /> },
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
