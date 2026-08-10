import { DEFAULT_THEME, ThemeProvider } from '@zendeskgarden/react-theming'
import type { PropsWithChildren } from 'react'

/** Keeps Garden implementation details at the Deskseed design-system boundary. */
export function DeskseedThemeProvider({ children }: PropsWithChildren) {
  return <ThemeProvider theme={DEFAULT_THEME}>{children}</ThemeProvider>
}
