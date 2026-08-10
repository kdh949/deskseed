import { Navigate, Outlet, Route, Routes } from 'react-router'
import { AppShell } from './components/AppShell'
import { AgentShell } from './features/agent-shell/AgentShell'
import { HomePage } from './pages/HomePage'
import { LookupPage } from './pages/LookupPage'
import { NewRequestPage } from './pages/NewRequestPage'
import { RequestDetailPage } from './pages/RequestDetailPage'

export default function App() {
  return (
    <Routes>
      <Route path="/agent/*" element={<AgentShell />} />
      <Route
        element={
          <AppShell>
            <Outlet />
          </AppShell>
        }
      >
        <Route path="/" element={<HomePage />} />
        <Route path="/requests/new" element={<NewRequestPage />} />
        <Route path="/requests/:ticketNumber" element={<RequestDetailPage />} />
        <Route path="/lookup" element={<LookupPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
