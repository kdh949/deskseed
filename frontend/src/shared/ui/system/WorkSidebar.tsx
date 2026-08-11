import type { PropsWithChildren, ReactNode } from 'react'

interface WorkSidebarProps extends PropsWithChildren {
  eyebrow: string
  title: string
  collapsed: boolean
  onToggle: () => void
  footer?: ReactNode
}

export function WorkSidebar({
  eyebrow,
  title,
  collapsed,
  onToggle,
  footer,
  children,
}: WorkSidebarProps) {
  if (collapsed) {
    return (
      <button
        className="work-nav-expand"
        type="button"
        onClick={onToggle}
        aria-label="작업 탐색 펼치기"
      >
        <span aria-hidden="true">›</span>
      </button>
    )
  }

  return (
    <aside className="agent-work-nav" aria-label="상담사 작업 탐색">
      <header className="work-nav-header">
        <div>
          <p className="agent-work-nav-eyebrow">{eyebrow}</p>
          <h1>{title}</h1>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={onToggle}
          aria-label="작업 탐색 접기"
        >
          <span aria-hidden="true">‹</span>
        </button>
      </header>
      {children}
      {footer}
    </aside>
  )
}
