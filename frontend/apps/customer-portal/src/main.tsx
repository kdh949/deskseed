import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { customerRoutes } from './App'
import './design-system/index.css'
import './customer-pages.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 15_000, refetchOnWindowFocus: false },
  },
})
const customerBasename = window.location.pathname.startsWith('/_customer')
  ? '/_customer'
  : '/'
const router = createBrowserRouter(customerRoutes, {
  basename: customerBasename,
})
const root = document.getElementById('root')
if (!root) throw new Error('Customer portal root element was not found')

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
