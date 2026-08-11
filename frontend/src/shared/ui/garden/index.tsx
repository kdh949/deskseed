import { Button } from '@zendeskgarden/react-buttons'
import type { IButtonProps } from '@zendeskgarden/react-buttons'
import { DEFAULT_THEME, ThemeProvider } from '@zendeskgarden/react-theming'
import type { PropsWithChildren } from 'react'

/**
 * This directory is the only Garden import boundary in the application.
 * Product code consumes Deskseed-owned names so Garden can be upgraded and
 * audited without leaking vendor-specific APIs across feature modules.
 */
export function GardenThemeBoundary({ children }: PropsWithChildren) {
  return <ThemeProvider theme={DEFAULT_THEME}>{children}</ThemeProvider>
}

export function GardenPrimaryButton({ children, ...props }: IButtonProps) {
  return (
    <Button isPrimary {...props}>
      {children}
    </Button>
  )
}

export type { IButtonProps as GardenButtonProps }
