import { DEFAULT_THEME, ThemeProvider } from '@zendeskgarden/react-theming'
import type { ReactNode } from 'react'

export function SeedThemeProvider({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={DEFAULT_THEME}>{children}</ThemeProvider>
}
