import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { SeedThemeProvider } from './design-system/canonical'
import { appRoutes } from './App'
import './design-system/canonical-index.css'

function startApplication() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
      },
    },
  })
  const staffBasename = window.location.pathname.startsWith('/_staff')
    ? '/_staff'
    : '/'
  const router = createBrowserRouter(appRoutes, { basename: staffBasename })

  const root = document.getElementById('root')
  if (!root) throw new Error('Root element was not found')

  createRoot(root).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <SeedThemeProvider>
          <RouterProvider router={router} />
        </SeedThemeProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
}

startApplication()
