import type { Preview } from '@storybook/react-vite'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { mswLoader } from 'msw-storybook-addon/csf3'
import { CustomerSessionProvider } from '../src/features/customer-auth/CustomerSessionContext'
import { mswHandlers } from './msw-handlers'
import '../src/design-system/index.css'
import '../src/customer-pages.css'

const preview: Preview = {
  decorators: [
    (Story) => {
      const queryClient = new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: false },
        },
      })
      const router = createMemoryRouter([{ path: '*', element: <Story /> }])
      return (
        <QueryClientProvider client={queryClient}>
          <CustomerSessionProvider>
            <RouterProvider router={router} />
          </CustomerSessionProvider>
        </QueryClientProvider>
      )
    },
  ],
  loaders: [mswLoader()],
  parameters: {
    msw: { handlers: mswHandlers },
    a11y: { test: 'error' },
    options: { storySort: { method: 'alphabetical', locales: 'ko-KR' } },
    controls: { matchers: { color: /(background|color)$/i, date: /Date$/i } },
  },
}

export default preview
