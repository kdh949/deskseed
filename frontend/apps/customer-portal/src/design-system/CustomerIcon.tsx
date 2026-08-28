import type { CSSProperties } from 'react'
import alertWarning from '@zendeskgarden/svg-icons/src/16/alert-warning-stroke.svg'
import arrowLeft from '@zendeskgarden/svg-icons/src/16/arrow-left-stroke.svg'
import bookClosed from '@zendeskgarden/svg-icons/src/16/book-closed-stroke.svg'
import calendar from '@zendeskgarden/svg-icons/src/16/calendar-stroke.svg'
import checkCircle from '@zendeskgarden/svg-icons/src/16/check-circle-stroke.svg'
import chevronDown from '@zendeskgarden/svg-icons/src/16/chevron-down-stroke.svg'
import clock from '@zendeskgarden/svg-icons/src/16/clock-stroke.svg'
import home from '@zendeskgarden/svg-icons/src/16/home-stroke.svg'
import inbox from '@zendeskgarden/svg-icons/src/16/inbox-stroke.svg'
import info from '@zendeskgarden/svg-icons/src/16/info-stroke.svg'
import lock from '@zendeskgarden/svg-icons/src/16/lock-locked-stroke.svg'
import mail from '@zendeskgarden/svg-icons/src/16/email-stroke.svg'
import paperclip from '@zendeskgarden/svg-icons/src/16/paperclip.svg'
import pencil from '@zendeskgarden/svg-icons/src/16/pencil-stroke.svg'
import plus from '@zendeskgarden/svg-icons/src/16/plus-stroke.svg'
import reload from '@zendeskgarden/svg-icons/src/16/reload-stroke.svg'
import search from '@zendeskgarden/svg-icons/src/16/search-stroke.svg'
import speechBubble from '@zendeskgarden/svg-icons/src/16/speech-bubble-plain-stroke.svg'
import user from '@zendeskgarden/svg-icons/src/16/user-solo-stroke.svg'

export type CustomerIconName =
  | 'alert'
  | 'arrowLeft'
  | 'book'
  | 'calendar'
  | 'check'
  | 'chevronDown'
  | 'clock'
  | 'home'
  | 'inbox'
  | 'info'
  | 'lock'
  | 'mail'
  | 'paperclip'
  | 'pencil'
  | 'plus'
  | 'reload'
  | 'search'
  | 'speechBubble'
  | 'user'

const sources: Record<CustomerIconName, string> = {
  alert: alertWarning,
  arrowLeft,
  book: bookClosed,
  calendar,
  check: checkCircle,
  chevronDown,
  clock,
  home,
  inbox,
  info,
  lock,
  mail,
  paperclip,
  pencil,
  plus,
  reload,
  search,
  speechBubble,
  user,
}

export function CustomerIcon({
  name,
  size = 'md',
}: {
  name: CustomerIconName
  size?: 'sm' | 'md' | 'lg' | 'xl'
}) {
  return (
    <span
      aria-hidden="true"
      className={`customer-icon customer-icon--${size}`}
      style={
        { '--customer-icon-source': `url("${sources[name]}")` } as CSSProperties
      }
    />
  )
}
