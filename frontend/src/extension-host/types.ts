import type { ReactElement } from 'react'

export type ExtensionSurface = 'customer' | 'agent' | 'admin'

export type ExtensionSlot =
  | 'ticket-workspace.context'
  | 'ticket-composer.toolbar'
  | 'ticket-composer.status'

export type ExtensionAccess = {
  role: string
  capabilities: readonly string[]
}

type ContributionIdentity = {
  /** Globally stable dotted identifier owned by exactly one feature. */
  id: string
  /** Deterministic ordering within the contribution's surface or slot. */
  order: number
  requiredCapabilities?: readonly string[]
  requiredRoles?: readonly string[]
}

export type RouteContribution = ContributionIdentity & {
  kind: 'route'
  surface: ExtensionSurface
  /** Relative to the existing customer, agent, or admin route parent. */
  path: string
  element: ReactElement
  title: string
}

export type ShellNavigationContribution = ContributionIdentity & {
  kind: 'shell-navigation'
  surface: 'agent'
  label: string
  to: string
}

export type WorkspaceSlotContribution = ContributionIdentity & {
  kind: 'workspace-slot'
  slot: ExtensionSlot
  /** Only non-sensitive route and composer context is supplied by the host. */
  render: (context: WorkspaceExtensionContext) => ReactElement
}

export type WorkspaceExtensionContext = {
  ticketNumber: string
  composerMode?: 'public' | 'internal'
}

export type FrontendContribution =
  RouteContribution | ShellNavigationContribution | WorkspaceSlotContribution

export type FeatureContributionModule = {
  contribution: FrontendContribution | readonly FrontendContribution[]
}
