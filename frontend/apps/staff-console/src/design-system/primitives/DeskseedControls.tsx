import type {
  ButtonHTMLAttributes,
  KeyboardEvent,
  ReactNode,
  SelectHTMLAttributes,
} from 'react'
import { useRef } from 'react'
import { DeskseedIcon } from './DeskseedIcon'

export type DsSelectProps = SelectHTMLAttributes<HTMLSelectElement>

export function DsSelect({ className = '', ...props }: DsSelectProps) {
  return <select {...props} className={`ds-select ${className}`.trim()} />
}

type DsPropertyFieldProps = {
  children: ReactNode
  label: string
}

export function DsPropertyField({ children, label }: DsPropertyFieldProps) {
  return (
    <label className="ds-property-field">
      <span>{label}</span>
      {children}
    </label>
  )
}

type DsTagInputProps = {
  label: string
  tags: string[]
}

export function DsTagInput({ label, tags }: DsTagInputProps) {
  return (
    <div aria-label={label} className="ds-tag-input" role="group">
      <div className="ds-tag-input-list">
        {tags.map((tag) => (
          <span className="ds-tag" key={tag}>
            {tag}
            <DeskseedIcon name="x" size="sm" />
          </span>
        ))}
      </div>
      <DeskseedIcon name="chevronDown" size="sm" />
    </div>
  )
}

type DsButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  tone?: 'primary' | 'secondary' | 'ghost'
}

export function DsButton({
  className = '',
  tone = 'secondary',
  ...props
}: DsButtonProps) {
  return (
    <button
      {...props}
      className={`ds-button ds-button--${tone} ${className}`.trim()}
      type={props.type ?? 'button'}
    />
  )
}

type DsSplitButtonProps = {
  actionLabel: string
  children: ReactNode
  disabled?: boolean
  onAction: () => void
  onMore?: () => void
}

export function DsSplitButton({
  actionLabel,
  children,
  disabled = false,
  onAction,
  onMore,
}: DsSplitButtonProps) {
  return (
    <span className="ds-split-button">
      <DsButton disabled={disabled} onClick={onAction} tone="primary">
        {children}
      </DsButton>
      <button
        aria-label={`${actionLabel} 추가 옵션`}
        className="ds-split-button-more"
        disabled={disabled}
        onClick={onMore}
        type="button"
      >
        <DeskseedIcon name="chevronDown" size="sm" />
      </button>
    </span>
  )
}

type DsTabsProps<T extends string> = {
  activeId: T
  ariaLabel: string
  items: { ariaLabel?: string; id: T; label: ReactNode; panelId?: string }[]
  onChange: (id: T) => void
  className?: string
}

export function DsTabs<T extends string>({
  activeId,
  ariaLabel,
  items,
  onChange,
  className = '',
}: DsTabsProps<T>) {
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  const handleKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % items.length
    if (event.key === 'ArrowLeft')
      nextIndex = (index - 1 + items.length) % items.length
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = items.length - 1
    if (nextIndex === null) return

    event.preventDefault()
    const nextItem = items[nextIndex]
    if (!nextItem) return
    onChange(nextItem.id)
    tabRefs.current[nextIndex]?.focus()
  }

  return (
    <div
      aria-label={ariaLabel}
      className={`ds-tabs ${className}`.trim()}
      role="tablist"
    >
      {items.map((item) => (
        <button
          aria-controls={item.panelId}
          aria-label={item.ariaLabel}
          aria-selected={activeId === item.id}
          id={item.panelId ? `${item.panelId}-tab` : undefined}
          key={item.id}
          onClick={() => onChange(item.id)}
          onKeyDown={(event) => handleKeyDown(event, items.indexOf(item))}
          ref={(element) => {
            tabRefs.current[items.indexOf(item)] = element
          }}
          role="tab"
          tabIndex={activeId === item.id ? 0 : -1}
          type="button"
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}
