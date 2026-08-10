import { Navigate, Outlet, useRoutes, type RouteObject } from 'react-router'
import { AppShell } from './components/AppShell'
import { AgentShell } from './features/agent-shell/AgentShell'
import { HomePage } from './pages/HomePage'
import { LookupPage } from './pages/LookupPage'
import { NewRequestPage } from './pages/NewRequestPage'
import { RequestDetailPage } from './pages/RequestDetailPage'

export const appRoutes: RouteObject[] = [
  { path: '/agent/*', element: <AgentShell /> },
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
