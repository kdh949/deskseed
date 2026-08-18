import type {
  ExtensionAccess,
  ExtensionSlot,
  FeatureContributionModule,
  FrontendContribution,
  RouteContribution,
  ShellNavigationContribution,
  WorkspaceSlotContribution,
} from './types'

const CONTRIBUTION_ID = /^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$/
const ROUTE_PATH =
  /^(?!\/)(?!.*(?:^|\/)\.\.?\/)[a-z0-9][a-z0-9-]*(?:\/[a-z0-9:_-]+)*$/
const AGENT_NAVIGATION_PATH = /^\/agent\/[a-z0-9][a-z0-9/_-]*$/

export class ExtensionContractError extends Error {}

export class FrontendExtensionCatalog {
  private constructor(
    private readonly contributions: readonly FrontendContribution[],
  ) {}

  static fromModules(
    modules: Record<string, FeatureContributionModule>,
  ): FrontendExtensionCatalog {
    const contributions = Object.entries(modules)
      .sort(([left], [right]) => left.localeCompare(right))
      .flatMap(([, module]) =>
        Array.isArray(module.contribution)
          ? module.contribution
          : [module.contribution],
      )
    validateContributionSet(contributions)
    return new FrontendExtensionCatalog(contributions)
  }

  routesFor(
    surface: RouteContribution['surface'],
  ): readonly RouteContribution[] {
    return this.contributions
      .filter(
        (contribution): contribution is RouteContribution =>
          contribution.kind === 'route' && contribution.surface === surface,
      )
      .sort(byOrderThenId)
  }

  agentNavigationFor(
    access: ExtensionAccess,
  ): readonly ShellNavigationContribution[] {
    return this.contributions
      .filter(
        (contribution): contribution is ShellNavigationContribution =>
          contribution.kind === 'shell-navigation' &&
          allowsContribution(contribution, access),
      )
      .sort(byOrderThenId)
  }

  slotsFor(
    slot: ExtensionSlot,
    access: ExtensionAccess,
  ): readonly WorkspaceSlotContribution[] {
    return this.contributions
      .filter(
        (contribution): contribution is WorkspaceSlotContribution =>
          contribution.kind === 'workspace-slot' &&
          contribution.slot === slot &&
          allowsContribution(contribution, access),
      )
      .sort(byOrderThenId)
  }
}

export function allowsContribution(
  contribution: Pick<
    FrontendContribution,
    'requiredCapabilities' | 'requiredRoles'
  >,
  access: ExtensionAccess,
): boolean {
  const allowedRole =
    contribution.requiredRoles === undefined ||
    contribution.requiredRoles.includes(access.role)
  const allowedCapabilities = (contribution.requiredCapabilities ?? []).every(
    (capability) => access.capabilities.includes(capability),
  )
  return allowedRole && allowedCapabilities
}

const extensionModules = import.meta.glob<FeatureContributionModule>(
  '../extensions/**/feature-contribution.tsx',
  { eager: true },
)

export const frontendExtensions =
  FrontendExtensionCatalog.fromModules(extensionModules)

function validateContributionSet(
  contributions: readonly FrontendContribution[],
) {
  const ids = new Set<string>()
  const routePaths = new Set<string>()
  const orders = new Set<string>()

  contributions.forEach((contribution) => {
    if (!CONTRIBUTION_ID.test(contribution.id)) {
      throw new ExtensionContractError(
        `Invalid frontend extension id: ${contribution.id}`,
      )
    }
    if (!Number.isInteger(contribution.order) || contribution.order < 0) {
      throw new ExtensionContractError(
        `Invalid frontend extension order: ${contribution.id}`,
      )
    }
    if (ids.has(contribution.id)) {
      throw new ExtensionContractError(
        `Duplicate frontend extension id: ${contribution.id}`,
      )
    }
    ids.add(contribution.id)

    const orderScope = contributionOrderScope(contribution)
    const orderKey = `${orderScope}:${contribution.order}`
    if (orders.has(orderKey)) {
      throw new ExtensionContractError(
        `Ambiguous frontend extension order: ${orderKey}`,
      )
    }
    orders.add(orderKey)

    if (contribution.kind === 'route') {
      if (!ROUTE_PATH.test(contribution.path)) {
        throw new ExtensionContractError(
          `Invalid extension route path: ${contribution.path}`,
        )
      }
      const routeKey = `${contribution.surface}:${contribution.path}`
      if (routePaths.has(routeKey)) {
        throw new ExtensionContractError(
          `Duplicate extension route: ${routeKey}`,
        )
      }
      routePaths.add(routeKey)
      if (
        contribution.surface === 'customer' &&
        (contribution.requiredRoles !== undefined ||
          contribution.requiredCapabilities !== undefined)
      ) {
        throw new ExtensionContractError(
          `Customer extension routes cannot declare staff access: ${contribution.id}`,
        )
      }
    }

    if (
      contribution.kind === 'shell-navigation' &&
      !AGENT_NAVIGATION_PATH.test(contribution.to)
    ) {
      throw new ExtensionContractError(
        `Invalid agent extension navigation path: ${contribution.to}`,
      )
    }
  })
}

function contributionOrderScope(contribution: FrontendContribution): string {
  if (contribution.kind === 'route') return `route:${contribution.surface}`
  if (contribution.kind === 'shell-navigation') return 'shell-navigation:agent'
  return `workspace-slot:${contribution.slot}`
}

function byOrderThenId(
  left: Pick<FrontendContribution, 'id' | 'order'>,
  right: Pick<FrontendContribution, 'id' | 'order'>,
): number {
  return left.order - right.order || left.id.localeCompare(right.id)
}
