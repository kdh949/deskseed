import type { MouseEvent, ReactNode } from 'react'
import { NavLink } from 'react-router'
import type { IconName } from '../primitives/DeskseedIcon'
import { DeskseedIcon } from '../primitives/DeskseedIcon'
import { DsIconButton } from '../primitives/DeskseedPrimitives'

export type ViewNavigationItem = {
  count?: number | null
  editable?: boolean
  icon: IconName
  iconTone?: 'danger' | 'success' | 'warning'
  key: string
  label: string
  to: string
}

export type ViewNavigationSection = {
  action?: ReactNode
  footerAction?: ReactNode
  id: string
  items: ViewNavigationItem[]
  label: string
}

type ViewNavigationProps = {
  footer?: ReactNode
  label: string
  onEditItem?: (
    item: ViewNavigationItem,
    event: MouseEvent<HTMLButtonElement>,
  ) => void
  sections: ViewNavigationSection[]
  title: string
}

export function ViewNavigation({
  footer,
  label,
  onEditItem,
  sections,
  title,
}: ViewNavigationProps) {
  return (
    <aside aria-label={label} className="ds-view-navigation">
      <header>
        <strong>{title}</strong>
      </header>
      <nav aria-label={`${label} 목록`}>
        {sections.map((section) => (
          <section
            aria-labelledby={`view-section-${section.id}`}
            key={section.id}
          >
            <div className="ds-view-navigation-section-heading">
              <h2 id={`view-section-${section.id}`}>{section.label}</h2>
              {section.action}
            </div>
            {section.items.length ? (
              <ul>
                {section.items.map((item) => (
                  <li key={item.key}>
                    <NavLink to={item.to}>
                      <span
                        className="ds-view-navigation-item-icon"
                        data-tone={item.iconTone}
                      >
                        <DeskseedIcon name={item.icon} size="sm" />
                      </span>
                      <span>{item.label}</span>
                      {item.count !== null && item.count !== undefined ? (
                        <small>{item.count}</small>
                      ) : null}
                    </NavLink>
                    {item.editable && onEditItem ? (
                      <DsIconButton
                        className="ds-view-navigation-edit"
                        icon="pencil"
                        label={`${item.label} 편집`}
                        onClick={(event) => onEditItem(item, event)}
                      />
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="ds-view-navigation-empty">
                표시할 보기가 없습니다.
              </p>
            )}
            {section.footerAction ? (
              <div className="ds-view-navigation-section-footer">
                {section.footerAction}
              </div>
            ) : null}
          </section>
        ))}
      </nav>
      {footer ? <footer>{footer}</footer> : null}
    </aside>
  )
}
