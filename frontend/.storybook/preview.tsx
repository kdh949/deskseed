import type { Preview } from '@storybook/react-vite'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { mswLoader } from 'msw-storybook-addon/csf3'
import { DeskseedThemeProvider } from '../src/design-system'
import { mswHandlers } from './msw-handlers'
import '../src/design-system/index.css'

const preview: Preview = {
  decorators: [
    (Story) => {
      const queryClient = new QueryClient({
        defaultOptions: {
          queries: {
            refetchOnWindowFocus: false,
            staleTime: 15_000,
          },
        },
      })
      const router = createMemoryRouter([{ path: '*', element: <Story /> }], {
        initialEntries: ['/agent/views/my-open'],
      })

      return (
        <DeskseedThemeProvider>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </DeskseedThemeProvider>
      )
    },
  ],
  loaders: [mswLoader()],
  async beforeEach({ msw }) {
    msw.use(...mswHandlers)
  },
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },

    a11y: {
      // 'todo' - show a11y violations in the test UI only
      // 'error' - fail CI on a11y violations
      // 'off' - skip a11y checks entirely
      test: 'todo',
    },
  },
}

export default preview
