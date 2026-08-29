import type { Preview } from '@storybook/react-vite'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { mswLoader } from 'msw-storybook-addon/csf3'
import { SeedThemeProvider } from '../src/design-system/canonical'
import { mswHandlers } from './msw-handlers'
import '../src/design-system/canonical-index.css'

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
        <QueryClientProvider client={queryClient}>
          <SeedThemeProvider>
            <RouterProvider router={router} />
          </SeedThemeProvider>
        </QueryClientProvider>
      )
    },
  ],
  loaders: [mswLoader()],
  parameters: {
    msw: {
      handlers: mswHandlers,
    },
    options: {
      storySort: {
        method: 'alphabetical',
        locales: 'en-US',
      },
    },

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
      test: 'error',
    },
  },
}

export default preview
