import { DEFAULT_THEME, ThemeProvider } from '@zendeskgarden/react-theming'
import type { ReactNode } from 'react'

export function DeskseedThemeProvider({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={DEFAULT_THEME}>{children}</ThemeProvider>
}
