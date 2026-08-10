import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import { RequestAccessProvider } from './features/customer-requests/RequestAccessContext'
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

const root = document.getElementById('root')
if (!root) throw new Error('Root element was not found')

createRoot(root).render(
  <StrictMode>
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RequestAccessProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </RequestAccessProvider>
      </QueryClientProvider>
    </DeskseedThemeProvider>
  </StrictMode>,
)
