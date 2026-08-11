import { useRef, type KeyboardEvent, type ReactNode } from 'react'

export interface ContextPanelTab {
  id: string
  label: string
}

interface ContextPanelProps {
  label: string
  tabs: ContextPanelTab[]
  activeTab: string
  onTabChange: (id: string) => void
  children: ReactNode
}

export function ContextPanel({
  label,
  tabs,
  activeTab,
  onTabChange,
  children,
}: ContextPanelProps) {
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  const onKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const last = tabs.length - 1
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? last
          : event.key === 'ArrowRight'
            ? (index + 1) % tabs.length
            : (index - 1 + tabs.length) % tabs.length
    const nextTab = tabs[next]
    if (!nextTab) return
    onTabChange(nextTab.id)
    tabRefs.current[next]?.focus()
  }

  return (
    <section className="ticket-context-panel" aria-label={label}>
      <div className="context-tabs" role="tablist" aria-label={`${label} 탭`}>
        {tabs.map((tab, index) => (
          <button
            key={tab.id}
            ref={(element) => {
              tabRefs.current[index] = element
            }}
            role="tab"
            type="button"
            aria-selected={activeTab === tab.id}
            aria-controls={`context-${tab.id}`}
            id={`context-tab-${tab.id}`}
            tabIndex={activeTab === tab.id ? 0 : -1}
            onClick={() => onTabChange(tab.id)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div
        className="context-tab-panel"
        role="tabpanel"
        id={`context-${activeTab}`}
        aria-labelledby={`context-tab-${activeTab}`}
      >
        {children}
      </div>
    </section>
  )
}
