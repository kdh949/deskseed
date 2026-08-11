import type { PropsWithChildren } from 'react'
import { GardenThemeBoundary } from './garden'

/** Keeps Garden implementation details at the Deskseed design-system boundary. */
export function DeskseedThemeProvider({ children }: PropsWithChildren) {
  return <GardenThemeBoundary>{children}</GardenThemeBoundary>
}
