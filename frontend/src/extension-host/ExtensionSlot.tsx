import { Component, type ReactNode } from 'react'
import { frontendExtensions, type FrontendExtensionCatalog } from './catalog'
import type {
  ExtensionAccess,
  ExtensionSlot as ExtensionSlotName,
  WorkspaceExtensionContext,
} from './types'

type ExtensionSlotProps = {
  access: ExtensionAccess
  catalog?: FrontendExtensionCatalog
  context: WorkspaceExtensionContext
  slot: ExtensionSlotName
}

/** Isolates one optional feature contribution so it cannot blank the workspace. */
export function ExtensionSlot({
  access,
  catalog = frontendExtensions,
  context,
  slot,
}: ExtensionSlotProps) {
  return (
    <>
      {catalog.slotsFor(slot, access).map((contribution) => (
        <ExtensionErrorBoundary key={contribution.id}>
          <RenderedSlotContribution
            contribution={contribution}
            context={context}
          />
        </ExtensionErrorBoundary>
      ))}
    </>
  )
}

function RenderedSlotContribution({
  contribution,
  context,
}: {
  contribution: ReturnType<FrontendExtensionCatalog['slotsFor']>[number]
  context: WorkspaceExtensionContext
}) {
  return contribution.render(context)
}

type ExtensionErrorBoundaryProps = { children: ReactNode }
type ExtensionErrorBoundaryState = { failed: boolean }

export class ExtensionErrorBoundary extends Component<
  ExtensionErrorBoundaryProps,
  ExtensionErrorBoundaryState
> {
  state: ExtensionErrorBoundaryState = { failed: false }

  static getDerivedStateFromError(): ExtensionErrorBoundaryState {
    return { failed: true }
  }

  componentDidCatch() {
    // The host deliberately exposes no ticket/customer data and leaves incident reporting to the owning feature.
  }

  render() {
    return this.state.failed ? null : this.props.children
  }
}
