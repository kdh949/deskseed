import type { PropsWithChildren } from 'react'

interface AppShellProps extends PropsWithChildren {
  className: string
  contentId: string
  skipLabel?: string
}

/** Shared document-level frame for customer, agent and admin surfaces. */
export function AppShell({
  children,
  className,
  contentId,
  skipLabel = '본문으로 건너뛰기',
}: AppShellProps) {
  return (
    <div className={className}>
      <a className="skip-link" href={`#${contentId}`}>
        {skipLabel}
      </a>
      {children}
    </div>
  )
}
