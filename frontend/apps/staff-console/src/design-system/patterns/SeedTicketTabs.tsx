import { useRef } from 'react'
import { Link } from 'react-router'
import { SeedIconButton } from '../primitives/SeedCore'

export function SeedTicketTabs({
  items,
  onClose,
}: {
  items: Array<{ id: string; label: string; href: string; active: boolean }>
  onClose: (id: string) => void
}) {
  const links = useRef<Array<HTMLAnchorElement | null>>([])
  if (!items.length) return null
  return (
    <nav aria-label="열린 티켓" className="seed-ticket-tabs">
      {items.map((item, index) => (
        <span
          className="seed-ticket-tabs__item"
          data-active={item.active}
          key={item.id}
        >
          <Link
            aria-current={item.active ? 'page' : undefined}
            to={item.href}
            ref={(element) => {
              links.current[index] = element
            }}
            onKeyDown={(event) => {
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % items.length
                  : event.key === 'ArrowLeft'
                    ? (index - 1 + items.length) % items.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? items.length - 1
                        : null
              if (next === null) return
              event.preventDefault()
              links.current[next]?.focus()
            }}
          >
            {item.label}
          </Link>
          <SeedIconButton
            icon="x"
            label={`${item.label} 닫기`}
            variant="quiet"
            onClick={() => {
              links.current[index > 0 ? index - 1 : index + 1]?.focus()
              onClose(item.id)
            }}
          />
        </span>
      ))}
    </nav>
  )
}
