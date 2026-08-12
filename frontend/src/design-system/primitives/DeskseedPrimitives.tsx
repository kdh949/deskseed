import type { ButtonHTMLAttributes, ImgHTMLAttributes, ReactNode } from 'react'
import brandMark from '../../assets/deskseed/brand-mark-v2.png'
import transparentBrandMark from '../../assets/deskseed/brand-mark-transparent-v2.png'
import { DeskseedIcon, type IconName } from './DeskseedIcon'

type DeskseedBrandMarkProps = {
  size?: 'sm' | 'md' | 'lg'
  transparent?: boolean
}

export function DeskseedBrandMark({
  size = 'md',
  transparent = false,
}: DeskseedBrandMarkProps) {
  return (
    <img
      alt=""
      className={`ds-brand-mark ds-brand-mark--${size}`}
      src={transparent ? transparentBrandMark : brandMark}
    />
  )
}

export type DsIconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: IconName
  label: string
}

export function DsIconButton({
  icon,
  label,
  className = '',
  ...props
}: DsIconButtonProps) {
  return (
    <button
      {...props}
      aria-label={label}
      className={`ds-icon-button ${className}`.trim()}
      title={label}
      type={props.type ?? 'button'}
    >
      <DeskseedIcon name={icon} />
    </button>
  )
}

export type DsAvatarProps = Omit<
  ImgHTMLAttributes<HTMLImageElement>,
  'alt' | 'src'
> & {
  name: string
  src: string
  size?: 'sm' | 'md' | 'lg' | 'xl'
}

export function DsAvatar({
  name,
  src,
  size = 'md',
  className = '',
  ...props
}: DsAvatarProps) {
  return (
    <img
      {...props}
      alt={`${name} 프로필 사진`}
      className={`ds-avatar ds-avatar--${size} ${className}`.trim()}
      src={src}
    />
  )
}

type DsInitialAvatarProps = {
  initials: string
  label: string
  size?: 'sm' | 'md' | 'lg' | 'xl'
}

export function DsInitialAvatar({
  initials,
  label,
  size = 'md',
}: DsInitialAvatarProps) {
  return (
    <span
      aria-label={`${label} 이니셜 아바타`}
      className={`ds-initial-avatar ds-initial-avatar--${size}`}
      role="img"
    >
      {initials}
    </span>
  )
}

type StatusIndicatorProps = {
  children: ReactNode
  tone: 'new' | 'open' | 'pending' | 'onHold' | 'solved' | 'high'
}

export function DsStatusIndicator({ children, tone }: StatusIndicatorProps) {
  const iconByTone: Record<StatusIndicatorProps['tone'], IconName> = {
    new: 'circle',
    open: 'checkCircle',
    pending: 'clock',
    onHold: 'pause',
    solved: 'checkCircle',
    high: 'alertWarning',
  }

  return (
    <span className={`ds-status-indicator ds-status-indicator--${tone}`}>
      <DeskseedIcon name={iconByTone[tone]} size="sm" />
      {children}
    </span>
  )
}
