import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { appRoutes } from './App'
import { RequestAccessProvider } from './features/customer-requests/RequestAccessContext'
import { RequestSubmissionProvider } from './features/customer-requests/RequestSubmissionContext'
import { DeskseedThemeProvider } from './shared/ui/DeskseedThemeProvider'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
    },
  },
})
const router = createBrowserRouter(appRoutes)

const root = document.getElementById('root')
if (!root) throw new Error('Root element was not found')

createRoot(root).render(
  <StrictMode>
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RequestAccessProvider>
          <RequestSubmissionProvider>
            <RouterProvider router={router} />
          </RequestSubmissionProvider>
        </RequestAccessProvider>
      </QueryClientProvider>
    </DeskseedThemeProvider>
  </StrictMode>,
)
