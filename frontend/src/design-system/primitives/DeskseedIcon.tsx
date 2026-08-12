import type { CSSProperties } from 'react'
import adjust from '@zendeskgarden/svg-icons/src/16/adjust-stroke.svg'
import alertWarning from '@zendeskgarden/svg-icons/src/16/alert-warning-stroke.svg'
import arrowLeft from '@zendeskgarden/svg-icons/src/16/arrow-left-stroke.svg'
import bookClosed from '@zendeskgarden/svg-icons/src/16/book-closed-stroke.svg'
import bookmark from '@zendeskgarden/svg-icons/src/16/bookmark-stroke.svg'
import calendar from '@zendeskgarden/svg-icons/src/16/calendar-stroke.svg'
import checkCircle from '@zendeskgarden/svg-icons/src/16/check-circle-stroke.svg'
import chevronDown from '@zendeskgarden/svg-icons/src/16/chevron-down-stroke.svg'
import chevronDoubleLeft from '@zendeskgarden/svg-icons/src/16/chevron-double-left-stroke.svg'
import circle from '@zendeskgarden/svg-icons/src/16/circle-stroke.svg'
import clock from '@zendeskgarden/svg-icons/src/16/clock-stroke.svg'
import download from '@zendeskgarden/svg-icons/src/16/download-stroke.svg'
import eye from '@zendeskgarden/svg-icons/src/16/eye-stroke.svg'
import gear from '@zendeskgarden/svg-icons/src/16/gear-stroke.svg'
import grid from '@zendeskgarden/svg-icons/src/16/grid-2x2-stroke.svg'
import history from '@zendeskgarden/svg-icons/src/16/history-stroke.svg'
import home from '@zendeskgarden/svg-icons/src/16/home-stroke.svg'
import inbox from '@zendeskgarden/svg-icons/src/16/inbox-stroke.svg'
import info from '@zendeskgarden/svg-icons/src/16/info-stroke.svg'
import link from '@zendeskgarden/svg-icons/src/16/link-stroke.svg'
import lock from '@zendeskgarden/svg-icons/src/16/lock-locked-stroke.svg'
import notification from '@zendeskgarden/svg-icons/src/16/notification-stroke.svg'
import overflow from '@zendeskgarden/svg-icons/src/16/overflow-vertical-stroke.svg'
import paperclip from '@zendeskgarden/svg-icons/src/16/paperclip.svg'
import pause from '@zendeskgarden/svg-icons/src/16/pause-stroke.svg'
import pencil from '@zendeskgarden/svg-icons/src/16/pencil-stroke.svg'
import plus from '@zendeskgarden/svg-icons/src/16/plus-stroke.svg'
import reload from '@zendeskgarden/svg-icons/src/16/reload-stroke.svg'
import search from '@zendeskgarden/svg-icons/src/16/search-stroke.svg'
import smiley from '@zendeskgarden/svg-icons/src/16/smiley-stroke.svg'
import sort from '@zendeskgarden/svg-icons/src/16/sort-stroke.svg'
import speechBubble from '@zendeskgarden/svg-icons/src/16/speech-bubble-plain-stroke.svg'
import star from '@zendeskgarden/svg-icons/src/16/star-stroke.svg'
import userGroup from '@zendeskgarden/svg-icons/src/16/user-group-stroke.svg'
import x from '@zendeskgarden/svg-icons/src/16/x-stroke.svg'

export type IconName =
  | 'adjust'
  | 'alertWarning'
  | 'arrowLeft'
  | 'bookClosed'
  | 'bookmark'
  | 'calendar'
  | 'checkCircle'
  | 'chevronDown'
  | 'chevronDoubleLeft'
  | 'circle'
  | 'clock'
  | 'download'
  | 'eye'
  | 'gear'
  | 'grid'
  | 'history'
  | 'home'
  | 'inbox'
  | 'info'
  | 'link'
  | 'lock'
  | 'notification'
  | 'overflow'
  | 'paperclip'
  | 'pause'
  | 'pencil'
  | 'plus'
  | 'reload'
  | 'search'
  | 'smiley'
  | 'sort'
  | 'speechBubble'
  | 'star'
  | 'userGroup'
  | 'x'

const iconSource: Record<IconName, string> = {
  adjust,
  alertWarning,
  arrowLeft,
  bookClosed,
  bookmark,
  calendar,
  checkCircle,
  chevronDown,
  chevronDoubleLeft,
  circle,
  clock,
  download,
  eye,
  gear,
  grid,
  history,
  home,
  inbox,
  info,
  link,
  lock,
  notification,
  overflow,
  paperclip,
  pause,
  pencil,
  plus,
  reload,
  search,
  smiley,
  sort,
  speechBubble,
  star,
  userGroup,
  x,
}

type DeskseedIconProps = {
  name: IconName
  size?: 'sm' | 'md' | 'lg'
}

export function DeskseedIcon({ name, size = 'md' }: DeskseedIconProps) {
  const style = {
    '--ds-icon-source': `url("${iconSource[name]}")`,
  } as CSSProperties

  return (
    <span
      aria-hidden="true"
      className={`ds-icon ds-icon--${size}`}
      style={style}
    />
  )
}
