import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { BrandMark } from './BrandMark'

export interface NavRailItem {
  to: string
  label: string
  icon: ReactNode
}

interface NavRailProps {
  brandDestination: string
  brandLabel: string
  items: NavRailItem[]
  accountName?: string
}

export function NavRail({
  brandDestination,
  brandLabel,
  items,
  accountName,
}: NavRailProps) {
  return (
    <nav className="agent-global-nav" aria-label="상담사 전역 탐색">
      <NavLink
        className="agent-brand"
        to={brandDestination}
        aria-label={brandLabel}
      >
        <BrandMark compact />
      </NavLink>
      <div className="agent-nav-items">
        {items.map((item) => (
          <NavLink
            className="agent-nav-link"
            to={item.to}
            aria-label={item.label}
            key={item.to}
          >
            <span aria-hidden="true">{item.icon}</span>
            <span className="agent-nav-tooltip">{item.label}</span>
          </NavLink>
        ))}
      </div>
      {accountName ? (
        <div className="agent-nav-account" aria-hidden="true">
          {accountName.slice(0, 1)}
        </div>
      ) : null}
    </nav>
  )
}
