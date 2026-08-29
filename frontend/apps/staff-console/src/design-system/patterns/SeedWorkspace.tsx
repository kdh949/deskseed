import type {
  KeyboardEvent,
  MouseEvent,
  ReactNode,
  RefObject,
  TableHTMLAttributes,
} from 'react'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import {
  SeedButton,
  SeedCheckbox,
  SeedIcon,
  SeedIconButton,
  SeedTabs,
  type SeedIconName,
} from '../primitives/SeedCore'
import { SeedAvatar } from '../primitives/SeedCore'
import { SeedBrandLockup } from '../primitives/SeedCore'
import {
  SeedDrawer,
  SeedStatusBadge,
  type SeedTone,
} from '../components/SeedSurfaces'

export function SeedLoginShell({
  title,
  description,
  preview,
  children,
  footer,
}: {
  title: string
  description: string
  preview: ReactNode
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <main className="seed-login-shell">
      <header className="seed-login-shell__brand">
        <SeedBrandLockup />
      </header>
      <section className="seed-login-shell__intro">
        <h1>{title}</h1>
        <p>{description}</p>
        <div className="seed-login-shell__preview" aria-hidden="true">
          {preview}
        </div>
        <p className="seed-login-shell__trust">
          <SeedIcon name="lock" /> 안전한 Deskseed 직원 전용 작업 공간
        </p>
      </section>
      <section
        className="seed-login-shell__card"
        aria-label="Deskseed 직원 로그인"
      >
        {children}
      </section>
      {footer && <footer className="seed-login-shell__footer">{footer}</footer>}
    </main>
  )
}

export type SeedNavigationItem = {
  id: string
  label: string
  icon: SeedIconName
  active?: boolean
  badge?: ReactNode
}

export function SeedNavigationRail({
  items,
  footerItems = [],
  onNavigate,
}: {
  items: SeedNavigationItem[]
  footerItems?: SeedNavigationItem[]
  onNavigate: (id: string) => void
}) {
  const renderItem = (item: SeedNavigationItem) => (
    <button
      aria-current={item.active ? 'page' : undefined}
      className="seed-rail__item"
      key={item.id}
      onClick={() => onNavigate(item.id)}
      type="button"
    >
      <SeedIcon name={item.icon} />
      <span>{item.label}</span>
      {item.badge && <span className="seed-rail__badge">{item.badge}</span>}
    </button>
  )
  return (
    <aside aria-label="Deskseed 상담사 전역 탐색" className="seed-rail">
      <div className="seed-rail__brand">
        <SeedBrandLockup />
      </div>
      <nav aria-label="주요 상담사 메뉴" className="seed-rail__navigation">
        {items.map(renderItem)}
      </nav>
      <nav aria-label="보조 메뉴" className="seed-rail__footer">
        {footerItems.map(renderItem)}
      </nav>
    </aside>
  )
}

export function SeedTopBar({
  breadcrumb,
  notifications,
  onCreate,
  onSearch,
  profileName,
  profileInitials,
}: {
  breadcrumb: ReactNode
  notifications?: ReactNode
  onCreate?: () => void
  onSearch?: () => void
  profileName: string
  profileInitials: string
}) {
  return (
    <header className="seed-topbar">
      <div className="seed-topbar__breadcrumb">{breadcrumb}</div>
      <div className="seed-topbar__actions">
        {onSearch && (
          <button
            className="seed-topbar__search"
            onClick={onSearch}
            type="button"
          >
            <SeedIcon name="search" />
            <span>Deskseed 검색</span>
            <kbd>⌘K</kbd>
          </button>
        )}
        {onCreate && (
          <SeedButton onClick={onCreate}>
            <SeedIcon name="plus" /> 새 티켓
          </SeedButton>
        )}
        {notifications}
        <SeedAvatar initials={profileInitials} label={profileName} />
      </div>
    </header>
  )
}

export function SeedPageShell({
  rail,
  topbar,
  sidebar,
  children,
  className = '',
}: {
  rail: ReactNode
  topbar: ReactNode
  sidebar?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <div className={`seed-page-shell ${className}`.trim()}>
      {rail}
      {topbar}
      {sidebar && <aside className="seed-page-shell__sidebar">{sidebar}</aside>}
      <main className="seed-page-shell__main">{children}</main>
    </div>
  )
}

export function SeedFilterBar({ children }: { children: ReactNode }) {
  return <div className="seed-filter-bar">{children}</div>
}

export type SeedTableColumn<T> = {
  id: string
  label: ReactNode
  width?: string
  render: (row: T) => ReactNode
}

export function SeedDataTable<T>({
  ariaLabel,
  columns,
  rows,
  rowKey,
  onActivate,
  className = '',
  ...tableProps
}: {
  ariaLabel: string
  columns: SeedTableColumn<T>[]
  rows: T[]
  rowKey: (row: T) => string | number
  onActivate?: (row: T) => void
  className?: string
} & Omit<TableHTMLAttributes<HTMLTableElement>, 'children'>) {
  const activateFromKeyboard = (
    event: KeyboardEvent<HTMLTableRowElement>,
    row: T,
  ) => {
    if (!onActivate || (event.key !== 'Enter' && event.key !== ' ')) return
    event.preventDefault()
    onActivate(row)
  }
  return (
    <div className="seed-table-scroll">
      <table
        {...tableProps}
        aria-label={ariaLabel}
        className={`seed-table ${className}`.trim()}
      >
        <colgroup>
          {columns.map((column) => (
            <col key={column.id} style={{ width: column.width }} />
          ))}
        </colgroup>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.id} scope="col">
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onActivate ? () => onActivate(row) : undefined}
              onKeyDown={(event) => activateFromKeyboard(event, row)}
              tabIndex={onActivate ? 0 : undefined}
            >
              {columns.map((column) => (
                <td key={column.id}>{column.render(row)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function SeedSavedViews({
  title = '저장 보기',
  sections,
  activeId,
  onSelect,
  onCreate,
}: {
  title?: string
  sections: Array<{
    id: string
    label: string
    items: Array<{
      id: string
      label: string
      count?: number
      denied?: boolean
    }>
  }>
  activeId?: string
  onSelect: (id: string) => void
  onCreate?: () => void
}) {
  return (
    <div className="seed-saved-views">
      <header>
        <h2>{title}</h2>
        {onCreate && (
          <SeedIconButton
            icon="plus"
            label="개인 보기 만들기"
            onClick={onCreate}
            variant="quiet"
          />
        )}
      </header>
      <label className="seed-saved-views__search">
        <span className="seed-visually-hidden">저장 보기 검색</span>
        <SeedIcon name="search" />
        <input placeholder="보기 검색" type="search" />
      </label>
      {sections.map((section) => (
        <section key={section.id}>
          <h3>{section.label}</h3>
          <div>
            {section.items.map((item) => (
              <button
                aria-current={item.id === activeId ? 'page' : undefined}
                disabled={item.denied}
                key={item.id}
                onClick={() => onSelect(item.id)}
                type="button"
              >
                <SeedIcon name={item.denied ? 'lock' : 'users'} size="small" />
                <span>{item.label}</span>
                {item.count !== undefined && <b>{item.count}</b>}
              </button>
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

export type SeedSavedViewItem = {
  key: string
  label: string
  to: string
  count?: number | null
  countAsOf?: string | null
  editable?: boolean
}

export function SeedSavedViewNavigation({
  sections,
  activeKey,
  onCreate,
  onEdit,
}: {
  sections: Array<{ id: string; label: string; items: SeedSavedViewItem[] }>
  activeKey: string
  onCreate: (event: MouseEvent<HTMLButtonElement>) => void
  onEdit: (
    item: SeedSavedViewItem,
    event: MouseEvent<HTMLButtonElement>,
  ) => void
}) {
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim().toLocaleLowerCase('ko-KR')
  const visibleSections = sections
    .map((section) => ({
      ...section,
      items: normalizedQuery
        ? section.items.filter((item) =>
            item.label.toLocaleLowerCase('ko-KR').includes(normalizedQuery),
          )
        : section.items,
    }))
    .filter((section) => section.items.length > 0)

  return (
    <aside aria-label="티켓 보기" className="seed-view-navigation">
      <header>
        <div>
          <span>WORKSPACE</span>
          <h2>저장 보기</h2>
        </div>
        <SeedIconButton
          icon="plus"
          label="새 보기 만들기"
          onClick={onCreate}
          variant="quiet"
        />
      </header>
      <label>
        <span className="seed-visually-hidden">보기 검색</span>
        <SeedIcon name="search" />
        <input
          onChange={(event) => setQuery(event.target.value)}
          placeholder="보기 검색"
          type="search"
          value={query}
        />
      </label>
      {visibleSections.length ? (
        visibleSections.map((section) => (
          <section key={section.id}>
            <h3>{section.label}</h3>
            <ul>
              {section.items.map((item) => (
                <li key={item.key}>
                  <Link
                    aria-current={item.key === activeKey ? 'page' : undefined}
                    to={item.to}
                  >
                    <SeedIcon
                      name={section.id === 'personal' ? 'bookmark' : 'ticket'}
                      size="small"
                    />
                    <span>{item.label}</span>
                    {item.count !== null && item.count !== undefined && (
                      <b>{item.count}</b>
                    )}
                    {item.count !== null &&
                      item.count !== undefined &&
                      item.countAsOf && (
                        <span className="seed-visually-hidden">
                          티켓 {item.count}개, 기준 {item.countAsOf}
                        </span>
                      )}
                  </Link>
                  {item.editable && (
                    <SeedIconButton
                      icon="more"
                      label={`${item.label} 편집`}
                      onClick={(event) => onEdit(item, event)}
                      variant="quiet"
                    />
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))
      ) : (
        <p className="seed-view-navigation__empty">일치하는 보기가 없습니다.</p>
      )}
    </aside>
  )
}

export type SeedQueueColumn =
  | 'ticketNumber'
  | 'subject'
  | 'status'
  | 'priority'
  | 'group'
  | 'assignee'
  | 'updatedAt'
  | 'sla'

export type SeedQueueTicket = {
  ticketNumber: number
  subject: string
  status: ReactNode
  priority: ReactNode
  requester: string
  group: string
  assignee: string
  updatedLabel: string
  sla?: ReactNode
}

export function SeedQueueTicketTable({
  items,
  label,
  selectedTicketNumbers,
  visibleColumns,
  onOpenTicket,
  onSelectAll,
  onSelectionChange,
}: {
  items: SeedQueueTicket[]
  label: string
  selectedTicketNumbers: Set<number>
  visibleColumns: SeedQueueColumn[]
  onOpenTicket: (ticketNumber: number) => void
  onSelectAll: () => void
  onSelectionChange: (
    ticketNumber: number,
    options: { orderedTicketNumbers: number[]; range: boolean },
  ) => void
}) {
  const links = useRef<Array<HTMLAnchorElement | null>>([])
  const orderedTicketNumbers = items.map((item) => item.ticketNumber)
  const moveFocus = (
    event: KeyboardEvent<HTMLAnchorElement>,
    index: number,
  ) => {
    if (event.key === ' ') {
      event.preventDefault()
      onSelectionChange(items[index]!.ticketNumber, {
        orderedTicketNumbers,
        range: event.shiftKey,
      })
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    event.preventDefault()
    const next =
      event.key === 'ArrowDown'
        ? Math.min(items.length - 1, index + 1)
        : Math.max(0, index - 1)
    links.current[next]?.focus()
  }
  return (
    <div className="seed-table-scroll">
      <table
        aria-label={`${label} 티켓`}
        className="seed-table seed-queue-table"
      >
        <thead>
          <tr>
            <th scope="col">
              <SeedCheckbox
                aria-label="티켓 전체 선택"
                label="티켓 선택"
                checked={
                  items.length > 0 &&
                  items.every((item) =>
                    selectedTicketNumbers.has(item.ticketNumber),
                  )
                }
                onChange={onSelectAll}
              />
            </th>
            {visibleColumns.map((column) => (
              <th key={column} scope="col">
                {queueColumnLabel[column]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => (
            <tr key={item.ticketNumber}>
              <td>
                <SeedCheckbox
                  aria-label={`티켓 #${item.ticketNumber} 선택`}
                  checked={selectedTicketNumbers.has(item.ticketNumber)}
                  label=""
                  onChange={() =>
                    onSelectionChange(item.ticketNumber, {
                      orderedTicketNumbers,
                      range: false,
                    })
                  }
                />
              </td>
              {visibleColumns.map((column) => (
                <td key={column}>
                  {column === 'ticketNumber' ? (
                    <Link
                      aria-label={`티켓 #${item.ticketNumber} ${item.subject}`}
                      onClick={(event) => {
                        event.preventDefault()
                        onOpenTicket(item.ticketNumber)
                      }}
                      onKeyDown={(event) => moveFocus(event, index)}
                      ref={(element) => {
                        links.current[index] = element
                      }}
                      to={`/agent/tickets/${item.ticketNumber}`}
                    >
                      #{item.ticketNumber}
                    </Link>
                  ) : column === 'subject' ? (
                    <span className="seed-table__subject">
                      {item.subject}
                      <small>{item.requester}</small>
                    </span>
                  ) : column === 'status' ? (
                    item.status
                  ) : column === 'priority' ? (
                    item.priority
                  ) : column === 'group' ? (
                    item.group
                  ) : column === 'assignee' ? (
                    item.assignee
                  ) : column === 'updatedAt' ? (
                    item.updatedLabel
                  ) : (
                    (item.sla ?? '정책 없음')
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const queueColumnLabel: Record<SeedQueueColumn, string> = {
  ticketNumber: '티켓 ID',
  subject: '제목',
  status: '상태',
  priority: '우선순위',
  group: '그룹',
  assignee: '담당자',
  updatedAt: '업데이트',
  sla: 'SLA',
}

export type SeedComposerMode = 'PUBLIC' | 'INTERNAL'

export function SeedComposer({
  attachment,
  availableModes = ['PUBLIC', 'INTERNAL'],
  canSubmit,
  disabled,
  draft,
  extension,
  editor,
  footer,
  messageLabel,
  mode,
  onDraftChange,
  onModeChange,
  onSubmit,
  placeholder,
  status,
  submitLabel,
}: {
  attachment?: ReactNode
  availableModes?: SeedComposerMode[]
  canSubmit: boolean
  disabled?: boolean
  draft?: string
  editor?: ReactNode
  extension?: ReactNode
  footer?: ReactNode
  messageLabel: string
  mode: SeedComposerMode
  onDraftChange?: (value: string) => void
  onModeChange: (mode: SeedComposerMode) => void
  onSubmit: () => void
  placeholder: string
  status: ReactNode
  submitLabel: string
}) {
  return (
    <section
      aria-label="답변 작성"
      className={`seed-composer seed-composer--${mode.toLowerCase()}`}
    >
      {availableModes.length > 1 ? (
        <SeedTabs
          active={mode}
          ariaLabel="답변 공개 범위"
          items={availableModes.map((item) => ({
            id: item,
            label: (
              <span
                aria-label={
                  item === 'PUBLIC'
                    ? '공개 답변 작성 모드로 전환'
                    : '내부 메모 작성 모드로 전환'
                }
              >
                <SeedIcon
                  name={item === 'PUBLIC' ? 'speech' : 'lock'}
                  size="small"
                />
                {item === 'PUBLIC' ? 'PUBLIC 답변' : 'INTERNAL 메모'}
              </span>
            ),
          }))}
          onChange={onModeChange}
        />
      ) : (
        <p className="seed-composer__single-mode">
          <SeedIcon name="lock" size="small" /> INTERNAL 메모 · 직원 전용
        </p>
      )}
      {extension && <div className="seed-composer__extension">{extension}</div>}
      {editor ?? (
        <label className="seed-composer__editor">
          <span className="seed-visually-hidden">{messageLabel}</span>
          <textarea
            aria-label={messageLabel}
            disabled={disabled}
            maxLength={20_000}
            onChange={(event) => onDraftChange?.(event.target.value)}
            placeholder={placeholder}
            value={draft ?? ''}
          />
        </label>
      )}
      {attachment && (
        <div className="seed-composer__attachment">{attachment}</div>
      )}
      {footer ?? (
        <footer>
          <span>{status}</span>
          <SeedButton
            disabled={disabled || !canSubmit}
            onClick={onSubmit}
            variant="primary"
          >
            {submitLabel}
            <SeedIcon name="chevron" size="small" />
          </SeedButton>
        </footer>
      )}
    </section>
  )
}

export function SeedWorkspaceHeader({
  assignee,
  contextButtonRef,
  copiedMessage,
  onCopyTicketLabel,
  onOpenContext,
  onRefresh,
  priority,
  sla,
  status,
  ticketLabel,
  title,
}: {
  assignee?: { initials: string; label: string }
  contextButtonRef?: RefObject<HTMLButtonElement>
  copiedMessage?: string
  onCopyTicketLabel?: () => void
  onOpenContext?: () => void
  onRefresh?: () => void
  priority?: { label: string; tone?: SeedTone }
  sla?: ReactNode
  status: ReactNode
  ticketLabel: string
  title: string
}) {
  return (
    <>
      <div className="seed-workspace-heading">
        <span className="seed-workspace-heading__ticket">
          <strong aria-label={ticketLabel}>{ticketLabel}</strong>
          {onCopyTicketLabel && (
            <SeedIconButton
              icon="copy"
              label={`${ticketLabel} 복사`}
              onClick={onCopyTicketLabel}
              variant="quiet"
            />
          )}
        </span>
        <span aria-hidden="true" className="seed-workspace-heading__divider" />
        <h1>{title}</h1>
        {copiedMessage && (
          <span className="seed-visually-hidden" role="status">
            {copiedMessage}
          </span>
        )}
      </div>
      <div className="seed-workspace-header__actions">
        {status}
        {priority && (
          <span
            className={`seed-workspace-header__priority seed-workspace-header__priority--${priority.tone ?? 'neutral'}`}
          >
            <SeedIcon name="priority" size="small" />
            {priority.label}
            <SeedIcon name="chevron" size="small" />
          </span>
        )}
        {assignee && (
          <span className="seed-workspace-header__assignee">
            <SeedAvatar initials={assignee.initials} label={assignee.label} />
            <strong>{assignee.label}</strong>
            <SeedIcon name="chevron" size="small" />
          </span>
        )}
        {sla && <span className="seed-workspace-header__sla">{sla}</span>}
        {onOpenContext && (
          <SeedIconButton
            className="seed-workspace-header__context-toggle"
            icon="columns"
            label="티켓 컨텍스트 열기"
            onClick={onOpenContext}
            ref={contextButtonRef}
            variant="quiet"
          />
        )}
        {onRefresh && (
          <details className="seed-workspace-header__menu">
            <summary aria-label="티켓 추가 작업" title="티켓 추가 작업">
              <SeedIcon name="more" />
            </summary>
            <div>
              <button onClick={onRefresh} type="button">
                <SeedIcon name="refresh" /> 최신 정보 새로고침
              </button>
            </div>
          </details>
        )}
      </div>
    </>
  )
}

export function SeedPropertyStack({
  action,
  children,
  title,
}: {
  action?: ReactNode
  children: ReactNode
  title: string
}) {
  return (
    <section className="seed-workspace-properties">
      <header>
        <h2>{title}</h2>
        {action}
      </header>
      {children}
    </section>
  )
}

export function SeedPropertyRow({
  children,
  label,
}: {
  children: ReactNode
  label: string
}) {
  return (
    <div className="seed-property">
      <span>{label}</span>
      <strong>{children}</strong>
    </div>
  )
}

export function SeedConversationTimeline<T extends string>({
  activeFilter,
  children,
  filters,
  onFilterChange,
  sortLabel,
}: {
  activeFilter: T
  children: ReactNode
  filters: Array<{ count: number; id: T; label: string }>
  onFilterChange: (filter: T) => void
  sortLabel: string
}) {
  return (
    <section aria-label="티켓 대화 기록" className="seed-conversation">
      <header className="seed-conversation__header">
        <SeedTabs
          active={activeFilter}
          ariaLabel="대화 공개 범위 필터"
          items={filters.map((filter) => ({
            id: filter.id,
            label: (
              <>
                {filter.label} <b>{filter.count}</b>
              </>
            ),
          }))}
          onChange={onFilterChange}
        />
        <span>
          {sortLabel} <SeedIcon name="sort" size="small" />
        </span>
      </header>
      <ol className="seed-conversation__list">{children}</ol>
    </section>
  )
}

export function SeedConversationItem({
  actorLabel,
  actorRole,
  attachments,
  children,
  dateTime,
  initials,
  sourceLabel,
  timestamp,
  visibility,
}: {
  actorLabel: string
  actorRole: string
  attachments?: ReactNode
  children: ReactNode
  dateTime: string
  initials: string
  sourceLabel: string
  timestamp: string
  visibility: SeedComposerMode
}) {
  return (
    <li>
      <article
        className={`seed-message seed-message--${visibility.toLowerCase()}`}
      >
        <SeedAvatar initials={initials} label={actorLabel} />
        <div>
          <header>
            <strong>{actorLabel}</strong>
            <span>
              {actorRole} · {sourceLabel}
            </span>
            <SeedStatusBadge
              tone={visibility === 'PUBLIC' ? 'info' : 'warning'}
            >
              {visibility}
            </SeedStatusBadge>
            <time dateTime={dateTime}>{timestamp}</time>
          </header>
          <div className="seed-message__body">{children}</div>
          {attachments}
        </div>
      </article>
    </li>
  )
}

export function SeedConflictBar({
  actions,
  containerRef,
  description,
  title,
}: {
  actions: ReactNode
  containerRef?: RefObject<HTMLElement>
  description: string
  title: string
}) {
  return (
    <section
      aria-label={title}
      className="seed-conflict-bar"
      ref={containerRef}
      role="alert"
      tabIndex={-1}
    >
      <SeedIcon name="alert" />
      <strong>{title}</strong>
      <span>{description}</span>
      <div>{actions}</div>
    </section>
  )
}

export function SeedTicketWorkspaceShell({
  contextOpen = false,
  contextReturnFocusRef,
  header,
  properties,
  conversation,
  context,
  onContextClose = () => undefined,
}: {
  contextOpen?: boolean
  contextReturnFocusRef?: RefObject<HTMLElement>
  header: ReactNode
  properties: ReactNode
  conversation: ReactNode
  context: ReactNode
  onContextClose?: () => void
}) {
  return (
    <>
      <section className="seed-ticket-workspace">
        <header className="seed-ticket-workspace__header">{header}</header>
        <aside className="seed-ticket-workspace__properties">
          {properties}
        </aside>
        <div className="seed-ticket-workspace__conversation">
          {conversation}
        </div>
        <aside className="seed-ticket-workspace__context">{context}</aside>
      </section>
      <SeedDrawer
        description="고객, 관련 티켓, 외부 참조와 최근 활동을 확인합니다."
        onClose={onContextClose}
        open={contextOpen}
        returnFocusRef={contextReturnFocusRef}
        title="티켓 컨텍스트"
      >
        <div className="seed-context-stack seed-context-stack--drawer">
          {context}
        </div>
      </SeedDrawer>
    </>
  )
}
