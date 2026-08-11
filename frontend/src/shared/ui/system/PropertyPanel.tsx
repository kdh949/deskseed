import type { ReactNode } from 'react'

export interface PropertyPanelItem {
  label: string
  value: ReactNode
}

interface PropertyPanelProps {
  title: string
  meta?: string
  items: PropertyPanelItem[]
  footer?: ReactNode
}

export function PropertyPanel({
  title,
  meta,
  items,
  footer,
}: PropertyPanelProps) {
  return (
    <section
      className="ticket-properties-panel"
      aria-label="티켓 속성"
      tabIndex={0}
    >
      <header className="workspace-panel-header">
        <h2>{title}</h2>
        {meta ? <span>{meta}</span> : null}
      </header>
      <dl className="ticket-properties">
        {items.map((item) => (
          <div key={item.label}>
            <dt>{item.label}</dt>
            <dd>{item.value}</dd>
          </div>
        ))}
      </dl>
      {footer}
    </section>
  )
}
