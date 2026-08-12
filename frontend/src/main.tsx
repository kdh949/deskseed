import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { createAppRoutes } from './App'
import { CustomerSessionProvider } from './features/customer-auth/CustomerSessionContext'
import { RequestAccessProvider } from './features/customer-requests/RequestAccessContext'
import { RequestSubmissionProvider } from './features/customer-requests/RequestSubmissionContext'
import { takeAndClearMagicLinkToken } from './pages/CustomerSignInPage'
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
const customerMagicLinkToken =
  window.location.pathname === '/customer/sign-in/consume'
    ? takeAndClearMagicLinkToken()
    : null
const router = createBrowserRouter(createAppRoutes(customerMagicLinkToken))

const root = document.getElementById('root')
if (!root) throw new Error('Root element was not found')

createRoot(root).render(
  <StrictMode>
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <CustomerSessionProvider>
          <RequestAccessProvider>
            <RequestSubmissionProvider>
              <RouterProvider router={router} />
            </RequestSubmissionProvider>
          </RequestAccessProvider>
        </CustomerSessionProvider>
      </QueryClientProvider>
    </DeskseedThemeProvider>
  </StrictMode>,
)
