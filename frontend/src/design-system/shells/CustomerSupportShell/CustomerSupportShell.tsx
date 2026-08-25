import type { ReactNode } from 'react'

type CustomerSupportShellProps = {
  children: ReactNode
  complementary?: ReactNode
  complementaryLabel?: string
  globalNavigation: ReactNode
  mainLabel: string
  topBar: ReactNode
  workNavigation: ReactNode
}

export function CustomerSupportShell({
  children,
  complementary,
  complementaryLabel,
  globalNavigation,
  mainLabel,
  topBar,
  workNavigation,
}: CustomerSupportShellProps) {
  return (
    <div className="ds-customer-support-shell">
      {globalNavigation}
      <div className="ds-customer-support-shell-column">
        <header className="ds-customer-support-shell-topbar">{topBar}</header>
        <div className="ds-customer-support-shell-workspace">
          {workNavigation}
          <main
            aria-label={mainLabel}
            className="ds-customer-support-shell-main"
            id="customer-main-content"
          >
            {children}
          </main>
          {complementary ? (
            <aside
              aria-label={complementaryLabel}
              className="ds-customer-support-shell-complementary"
            >
              {complementary}
            </aside>
          ) : null}
        </div>
      </div>
    </div>
  )
}
