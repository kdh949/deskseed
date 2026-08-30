import type {
  ButtonHTMLAttributes,
  CSSProperties,
  InputHTMLAttributes,
  KeyboardEvent,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react'
import { forwardRef, useId, useRef } from 'react'
import {
  Combobox as GardenCombobox,
  Field as GardenField,
  Label as GardenLabel,
  Message as GardenMessage,
  Option as GardenOption,
} from '@zendeskgarden/react-dropdowns'
import alertWarning from '@zendeskgarden/svg-icons/src/16/alert-warning-stroke.svg'
import arrowLeft from '@zendeskgarden/svg-icons/src/16/arrow-left-stroke.svg'
import bookmark from '@zendeskgarden/svg-icons/src/16/bookmark-stroke.svg'
import alignCenter from '@zendeskgarden/svg-icons/src/16/align-center-stroke.svg'
import alignLeft from '@zendeskgarden/svg-icons/src/16/align-left-stroke.svg'
import alignRight from '@zendeskgarden/svg-icons/src/16/align-right-stroke.svg'
import at from '@zendeskgarden/svg-icons/src/16/at-stroke.svg'
import bold from '@zendeskgarden/svg-icons/src/16/bold-stroke.svg'
import calendar from '@zendeskgarden/svg-icons/src/16/calendar-stroke.svg'
import checkCircle from '@zendeskgarden/svg-icons/src/16/check-circle-stroke.svg'
import chevronDown from '@zendeskgarden/svg-icons/src/16/chevron-down-stroke.svg'
import chevronUp from '@zendeskgarden/svg-icons/src/16/chevron-up-stroke.svg'
import clock from '@zendeskgarden/svg-icons/src/16/clock-stroke.svg'
import copy from '@zendeskgarden/svg-icons/src/16/copy-stroke.svg'
import eye from '@zendeskgarden/svg-icons/src/16/eye-stroke.svg'
import external from '@zendeskgarden/svg-icons/src/16/link-stroke.svg'
import filter from '@zendeskgarden/svg-icons/src/16/filter-stroke.svg'
import gear from '@zendeskgarden/svg-icons/src/16/gear-stroke.svg'
import grid from '@zendeskgarden/svg-icons/src/16/grid-2x2-stroke.svg'
import home from '@zendeskgarden/svg-icons/src/16/home-stroke.svg'
import leaf from '@zendeskgarden/svg-icons/src/16/leaf-fill.svg'
import image from '@zendeskgarden/svg-icons/src/16/image-stroke.svg'
import italic from '@zendeskgarden/svg-icons/src/16/italic-stroke.svg'
import lightning from '@zendeskgarden/svg-icons/src/16/lightning-bolt-stroke.svg'
import listBullet from '@zendeskgarden/svg-icons/src/16/list-bullet-stroke.svg'
import listNumber from '@zendeskgarden/svg-icons/src/16/list-number-stroke.svg'
import lock from '@zendeskgarden/svg-icons/src/16/lock-locked-stroke.svg'
import mail from '@zendeskgarden/svg-icons/src/16/email-stroke.svg'
import notification from '@zendeskgarden/svg-icons/src/16/notification-stroke.svg'
import overflow from '@zendeskgarden/svg-icons/src/16/overflow-vertical-stroke.svg'
import paperclip from '@zendeskgarden/svg-icons/src/16/paperclip.svg'
import plus from '@zendeskgarden/svg-icons/src/16/plus-stroke.svg'
import reload from '@zendeskgarden/svg-icons/src/16/reload-stroke.svg'
import quote from '@zendeskgarden/svg-icons/src/16/quote-stroke.svg'
import search from '@zendeskgarden/svg-icons/src/16/search-stroke.svg'
import sort from '@zendeskgarden/svg-icons/src/16/sort-stroke.svg'
import speech from '@zendeskgarden/svg-icons/src/16/speech-bubble-plain-stroke.svg'
import smile from '@zendeskgarden/svg-icons/src/16/smile-slight-stroke.svg'
import text from '@zendeskgarden/svg-icons/src/16/text-stroke.svg'
import ticket from '@zendeskgarden/svg-icons/src/16/inbox-stroke.svg'
import user from '@zendeskgarden/svg-icons/src/16/user-solo-stroke.svg'
import users from '@zendeskgarden/svg-icons/src/16/user-group-stroke.svg'
import underline from '@zendeskgarden/svg-icons/src/16/underline-stroke.svg'
import x from '@zendeskgarden/svg-icons/src/16/x-stroke.svg'

export type SeedIconName =
  | 'alert'
  | 'align-center'
  | 'align-left'
  | 'align-right'
  | 'at'
  | 'back'
  | 'bold'
  | 'bookmark'
  | 'calendar'
  | 'check'
  | 'chevron'
  | 'clock'
  | 'columns'
  | 'copy'
  | 'eye'
  | 'external'
  | 'filter'
  | 'home'
  | 'image'
  | 'italic'
  | 'leaf'
  | 'lightning'
  | 'list-bullet'
  | 'list-number'
  | 'lock'
  | 'mail'
  | 'more'
  | 'notification'
  | 'paperclip'
  | 'plus'
  | 'priority'
  | 'quote'
  | 'refresh'
  | 'search'
  | 'settings'
  | 'sort'
  | 'speech'
  | 'smile'
  | 'ticket'
  | 'text'
  | 'underline'
  | 'user'
  | 'users'
  | 'x'

const seedIconSources: Record<SeedIconName, string> = {
  alert: alertWarning,
  'align-center': alignCenter,
  'align-left': alignLeft,
  'align-right': alignRight,
  at,
  back: arrowLeft,
  bold,
  bookmark,
  calendar,
  check: checkCircle,
  chevron: chevronDown,
  clock,
  columns: grid,
  copy,
  eye,
  external,
  filter,
  home,
  image,
  italic,
  leaf,
  lightning,
  'list-bullet': listBullet,
  'list-number': listNumber,
  lock,
  mail,
  more: overflow,
  notification,
  paperclip,
  plus,
  priority: chevronUp,
  quote,
  refresh: reload,
  search,
  settings: gear,
  sort,
  speech,
  smile,
  ticket,
  text,
  underline,
  user,
  users,
  x,
}

export function SeedIcon({
  name,
  size = 'default',
}: {
  name: SeedIconName
  size?: 'small' | 'default' | 'large'
}) {
  const style = {
    '--seed-icon-source': `url("${seedIconSources[name]}")`,
  } as CSSProperties
  return (
    <span
      aria-hidden="true"
      className={`seed-icon seed-icon--${size}`}
      style={style}
    />
  )
}

export function SeedBrandLockup({ compact = false }: { compact?: boolean }) {
  return (
    <span className={`seed-brand${compact ? ' seed-brand--compact' : ''}`}>
      <span className="seed-brand__mark" aria-hidden="true">
        <SeedIcon name="leaf" size="large" />
      </span>
      {!compact && <span className="seed-brand__name">Deskseed</span>}
    </span>
  )
}

export function SeedAvatar({
  label,
  initials,
  size = 'default',
}: {
  label: string
  initials: string
  size?: 'small' | 'default' | 'large'
}) {
  return (
    <span
      aria-label={`${label} 이니셜 프로필`}
      className={`seed-avatar seed-avatar--${size}`}
      role="img"
    >
      {initials}
    </span>
  )
}

export type SeedButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'quiet' | 'danger'
  size?: 'default' | 'compact'
}

export const SeedButton = forwardRef<HTMLButtonElement, SeedButtonProps>(
  function SeedButton(
    {
      variant = 'secondary',
      size = 'default',
      className = '',
      type = 'button',
      ...props
    },
    ref,
  ) {
    return (
      <button
        {...props}
        className={`seed-button seed-button--${variant} seed-button--${size} ${className}`.trim()}
        ref={ref}
        type={type}
      />
    )
  },
)

export const SeedIconButton = forwardRef<
  HTMLButtonElement,
  Omit<SeedButtonProps, 'children'> & {
    icon: SeedIconName
    label: string
  }
>(function SeedIconButton({ icon, label, className = '', ...props }, ref) {
  return (
    <SeedButton
      {...props}
      aria-label={label}
      className={`seed-icon-button ${className}`.trim()}
      ref={ref}
      title={label}
    >
      <SeedIcon name={icon} />
    </SeedButton>
  )
})

type FieldFrameProps = {
  children: ReactNode
  error?: string
  hint?: string
  label: string
  required?: boolean
}

function SeedFieldFrame({
  children,
  error,
  hint,
  label,
  required,
}: FieldFrameProps) {
  return (
    <label className={`seed-field${error ? ' seed-field--invalid' : ''}`}>
      <span className="seed-field__label">
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </span>
      {children}
      {error ? (
        <span className="seed-field__message" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="seed-field__hint">{hint}</span>
      ) : null}
    </label>
  )
}

export function SeedTextField({
  label,
  error,
  hint,
  leadingIcon,
  required,
  ...props
}: InputHTMLAttributes<HTMLInputElement> &
  Omit<FieldFrameProps, 'children'> & { leadingIcon?: SeedIconName }) {
  const generatedId = useId()
  const id = props.id ?? generatedId
  return (
    <SeedFieldFrame error={error} hint={hint} label={label} required={required}>
      <span className="seed-control-frame">
        {leadingIcon && <SeedIcon name={leadingIcon} />}
        <input
          {...props}
          aria-invalid={Boolean(error)}
          id={id}
          required={required}
        />
      </span>
    </SeedFieldFrame>
  )
}

export function SeedSelectField({
  label,
  error,
  hint,
  required,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & FieldFrameProps) {
  const generatedId = useId()
  return (
    <SeedFieldFrame error={error} hint={hint} label={label} required={required}>
      <span className="seed-control-frame seed-control-frame--select">
        <select
          {...props}
          aria-invalid={Boolean(error)}
          id={props.id ?? generatedId}
          required={required}
        >
          {children}
        </select>
        <SeedIcon name="chevron" size="small" />
      </span>
    </SeedFieldFrame>
  )
}

export function SeedSelect({
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <span className="seed-control-frame seed-control-frame--select">
      <select {...props}>{children}</select>
      <SeedIcon name="chevron" size="small" />
    </span>
  )
}

export interface SeedChoiceOption<T extends string> {
  value: T
  label: string
  description?: string
  startAdornment?: ReactNode
  disabled?: boolean
}

export function SeedChoiceField<T extends string>({
  label,
  value,
  options,
  onChange,
  onClear,
  clearLabel = `${label} 선택 해제`,
  placeholder = '선택',
  disabled = false,
  error,
}: {
  label: string
  value: T | null
  options: SeedChoiceOption<T>[]
  onChange: (value: T) => void
  onClear?: () => void
  clearLabel?: string
  placeholder?: string
  disabled?: boolean
  error?: string
}) {
  const selected = options.find((option) => option.value === value)
  return (
    <GardenField
      className={`seed-choice-field${error ? ' seed-choice-field--invalid' : ''}`}
    >
      <GardenLabel className="seed-field__label">{label}</GardenLabel>
      <div className="seed-choice-field__control">
        <GardenCombobox
          inputProps={{ 'aria-invalid': Boolean(error) }}
          isCompact
          isDisabled={disabled}
          isEditable={false}
          listboxAriaLabel={`${label} 선택지`}
          onChange={(change) => {
            if (typeof change.selectionValue === 'string') {
              onChange(change.selectionValue as T)
            }
          }}
          placeholder={placeholder}
          renderValue={() =>
            selected ? (
              <span className="seed-choice-field__value">
                {selected.startAdornment}
                <span>{selected.label}</span>
              </span>
            ) : (
              <span className="seed-choice-field__placeholder">
                {placeholder}
              </span>
            )
          }
          selectionValue={value}
          validation={error ? 'error' : undefined}
        >
          {options.map((option) => (
            <GardenOption
              isDisabled={option.disabled}
              key={option.value}
              label={option.label}
              value={option.value}
            >
              <span className="seed-choice-field__option">
                {option.startAdornment}
                <span>
                  <strong>{option.label}</strong>
                  {option.description && <small>{option.description}</small>}
                </span>
              </span>
            </GardenOption>
          ))}
        </GardenCombobox>
        {onClear && value !== null && !disabled && (
          <button
            aria-label={clearLabel}
            className="seed-choice-field__clear"
            onClick={onClear}
            type="button"
          >
            <SeedIcon name="x" size="small" />
          </button>
        )}
      </div>
      {error && <GardenMessage validation="error">{error}</GardenMessage>}
    </GardenField>
  )
}

export function SeedReadOnlyField({
  label,
  value,
  leadingIcon,
}: {
  label: string
  value: string
  leadingIcon?: SeedIconName
}) {
  return (
    <div className="seed-field">
      <span className="seed-field__label">{label}</span>
      <span
        aria-label={`${label}: ${value}`}
        className="seed-control-frame seed-read-only-field"
        role="textbox"
        aria-readonly="true"
      >
        {leadingIcon && <SeedIcon name={leadingIcon} size="small" />}
        <span>{value}</span>
      </span>
    </div>
  )
}

export function SeedTextAreaField({
  label,
  error,
  hint,
  required,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement> &
  Omit<FieldFrameProps, 'children'>) {
  return (
    <SeedFieldFrame error={error} hint={hint} label={label} required={required}>
      <textarea {...props} aria-invalid={Boolean(error)} required={required} />
    </SeedFieldFrame>
  )
}

export function SeedCheckbox({
  label,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="seed-checkbox">
      <input {...props} type="checkbox" />
      <span>{label}</span>
    </label>
  )
}

export function SeedTabs<T extends string>({
  active,
  ariaLabel,
  items,
  onChange,
}: {
  active: T
  ariaLabel: string
  items: Array<{ id: T; label: ReactNode }>
  onChange: (id: T) => void
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])
  const onKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    const last = items.length - 1
    const next =
      event.key === 'ArrowRight'
        ? index === last
          ? 0
          : index + 1
        : event.key === 'ArrowLeft'
          ? index === 0
            ? last
            : index - 1
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? last
              : null
    if (next === null) return
    event.preventDefault()
    const item = items[next]
    if (!item) return
    onChange(item.id)
    refs.current[next]?.focus()
  }
  return (
    <div aria-label={ariaLabel} className="seed-tabs" role="tablist">
      {items.map((item, index) => (
        <button
          aria-selected={active === item.id}
          key={item.id}
          onClick={() => onChange(item.id)}
          onKeyDown={(event) => onKeyDown(event, index)}
          ref={(element) => {
            refs.current[index] = element
          }}
          role="tab"
          tabIndex={active === item.id ? 0 : -1}
          type="button"
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}
