import { NavLink } from 'react-router'
import { DeskseedBrandMark } from '../../primitives/DeskseedPrimitives'
import { DeskseedIcon, type IconName } from '../../primitives/DeskseedIcon'

export type WorkspaceNavigationRailItem = {
  icon?: IconName
  id: string
  label: string
  to: string
}

type WorkspaceNavigationRailProps = {
  activeItemId?: string
  ariaLabel: string
  brandLabel: string
  brandTo: string
  items: readonly WorkspaceNavigationRailItem[]
  tone?: 'default' | 'inverse'
}

export function WorkspaceNavigationRail({
  activeItemId,
  ariaLabel,
  brandLabel,
  brandTo,
  items,
  tone = 'default',
}: WorkspaceNavigationRailProps) {
  return (
    <nav
      aria-label={ariaLabel}
      className={`ds-workspace-navigation-rail ds-workspace-navigation-rail--${tone}`}
    >
      <NavLink
        aria-label={brandLabel}
        className="ds-workspace-navigation-rail-brand"
        to={brandTo}
      >
        <DeskseedBrandMark transparent />
      </NavLink>
      <div className="ds-workspace-navigation-rail-items">
        {items.map((item) => (
          <NavLink
            aria-label={item.label}
            className={({ isActive }) =>
              isActive || activeItemId === item.id
                ? 'ds-workspace-navigation-rail-link is-active'
                : 'ds-workspace-navigation-rail-link'
            }
            key={item.id}
            to={item.to}
          >
            {item.icon ? (
              <DeskseedIcon name={item.icon} />
            ) : (
              <span aria-hidden="true">{Array.from(item.label)[0]}</span>
            )}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}
