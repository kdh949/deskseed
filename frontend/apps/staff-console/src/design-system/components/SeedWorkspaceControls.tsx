import {
  useEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react'
import {
  SeedAvatar,
  SeedButton,
  SeedChoiceField,
  SeedIcon,
  SeedIconButton,
} from '../primitives/SeedCore'
import {
  SeedContextCard,
  SeedDrawer,
  SeedFeedbackState,
  SeedStatusBadge,
} from './SeedSurfaces'
import './seed-workspace-controls.css'

export interface SeedSplitAction {
  id: string
  label: string
  description?: string
}

export function SeedSplitButton({
  label,
  disabled = false,
  busy = false,
  actions,
  onPrimary,
  onAction,
}: {
  label: string
  disabled?: boolean
  busy?: boolean
  actions: SeedSplitAction[]
  onPrimary: () => void
  onAction: (id: string) => void
}) {
  const [open, setOpen] = useState(false)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    menuRef.current?.querySelector<HTMLButtonElement>('button')?.focus()
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        toggleRef.current?.focus()
      }
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [open])
  return (
    <div className="seed-split-button">
      <SeedButton
        disabled={disabled || busy}
        onClick={onPrimary}
        size="compact"
        variant="primary"
      >
        {busy ? '저장 중…' : label}
      </SeedButton>
      <SeedButton
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`${label} 추가 작업`}
        className="seed-split-button__toggle"
        disabled={disabled || busy || actions.length === 0}
        onClick={() => setOpen((current) => !current)}
        ref={toggleRef}
        size="compact"
        variant="primary"
      >
        <SeedIcon name="chevron" size="small" />
      </SeedButton>
      {open && (
        <div className="seed-split-button__menu" ref={menuRef} role="menu">
          {actions.map((action) => (
            <button
              key={action.id}
              onClick={() => {
                setOpen(false)
                onAction(action.id)
              }}
              role="menuitem"
              type="button"
            >
              <strong>{action.label}</strong>
              {action.description && <small>{action.description}</small>}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export type SeedAsyncState = 'idle' | 'loading' | 'empty' | 'error' | 'denied'

export function SeedMacroMenu({
  items,
  state,
  onSelect,
  onRetry,
}: {
  items: Array<{ id: string; label: string; description?: string }>
  state: SeedAsyncState
  onSelect: (id: string) => void
  onRetry?: () => void
}) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (open)
      panelRef.current?.querySelector<HTMLButtonElement>('button')?.focus()
  }, [open])
  return (
    <div className="seed-macro-menu">
      <SeedButton
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen((current) => !current)}
        ref={buttonRef}
        size="compact"
      >
        <SeedIcon name="lightning" size="small" />
        매크로 라이브러리
        <SeedIcon name="chevron" size="small" />
      </SeedButton>
      {open && (
        <div
          aria-label="매크로 라이브러리"
          className="seed-macro-menu__panel"
          ref={panelRef}
          role="menu"
        >
          <header>
            <strong>매크로 라이브러리</strong>
            <SeedIconButton
              icon="x"
              label="매크로 닫기"
              onClick={() => {
                setOpen(false)
                buttonRef.current?.focus()
              }}
              size="compact"
              variant="quiet"
            />
          </header>
          {state === 'loading' && (
            <SeedFeedbackState
              compact
              kind="loading"
              title="매크로를 불러오는 중"
            />
          )}
          {state === 'empty' && (
            <SeedFeedbackState
              compact
              kind="empty"
              title="사용 가능한 매크로가 없습니다"
            />
          )}
          {state === 'denied' && (
            <SeedFeedbackState
              compact
              kind="denied"
              title="매크로를 사용할 권한이 없습니다"
            />
          )}
          {state === 'error' && (
            <SeedFeedbackState
              action={
                onRetry && (
                  <SeedButton onClick={onRetry} size="compact">
                    다시 시도
                  </SeedButton>
                )
              }
              compact
              kind="error"
              title="매크로를 불러오지 못했습니다"
            />
          )}
          {(state === 'idle' || state === 'empty') &&
            items.map((item) => (
              <button
                key={item.id}
                onClick={() => {
                  setOpen(false)
                  onSelect(item.id)
                }}
                role="menuitem"
                type="button"
              >
                <SeedIcon name="lightning" size="small" />
                <span>
                  <strong>{item.label}</strong>
                  {item.description && <small>{item.description}</small>}
                </span>
              </button>
            ))}
        </div>
      )}
    </div>
  )
}

export interface SeedCollaborationNoteItem {
  id: string
  author: string
  initials: string
  body: string
  timestamp: string
  mentionLabels?: string[]
}

export function SeedCollaborationThread({
  notes,
  people,
  state,
  canWrite,
  submitting = false,
  onSubmit,
  onRetry,
}: {
  notes: SeedCollaborationNoteItem[]
  people: Array<{ id: string; label: string; initials: string }>
  state: SeedAsyncState
  canWrite: boolean
  submitting?: boolean
  onSubmit: (body: string, mentionedIds: string[]) => Promise<boolean> | boolean
  onRetry?: () => void
}) {
  const [composerOpen, setComposerOpen] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [body, setBody] = useState('')
  const [mentionedIds, setMentionedIds] = useState<string[]>([])
  const openButtonRef = useRef<HTMLButtonElement>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  useEffect(() => {
    if (composerOpen) bodyRef.current?.focus()
  }, [composerOpen])
  const noteList = (items: SeedCollaborationNoteItem[]) => (
    <ol className="seed-collaboration__notes">
      {items.map((note) => (
        <li key={note.id}>
          <SeedAvatar
            initials={note.initials}
            label={note.author}
            size="small"
          />
          <div>
            <div className="seed-collaboration__note-meta">
              <strong>{note.author}</strong>
              <time>{note.timestamp}</time>
            </div>
            <p>{note.body}</p>
            {note.mentionLabels?.length ? (
              <small>멘션: {note.mentionLabels.join(', ')}</small>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  )
  return (
    <>
      <SeedContextCard
        action={
          canWrite ? (
            <SeedIconButton
              icon="plus"
              label="협업 메모 작성"
              onClick={() => setComposerOpen(true)}
              ref={openButtonRef}
              size="compact"
              variant="quiet"
            />
          ) : undefined
        }
        badge={<SeedStatusBadge>{notes.length}</SeedStatusBadge>}
        title="내부 협업"
      >
        {state === 'loading' && (
          <SeedFeedbackState
            compact
            kind="loading"
            title="메모를 불러오는 중"
          />
        )}
        {state === 'error' && (
          <SeedFeedbackState
            action={
              onRetry && (
                <SeedButton onClick={onRetry} size="compact">
                  다시 시도
                </SeedButton>
              )
            }
            compact
            kind="error"
            title="협업 메모를 불러오지 못했습니다"
          />
        )}
        {state === 'denied' && (
          <SeedFeedbackState
            compact
            kind="denied"
            title="협업 메모를 볼 권한이 없습니다"
          />
        )}
        {state === 'empty' && !composerOpen && (
          <p className="seed-collaboration__empty">
            아직 협업 메모가 없습니다.
          </p>
        )}
        {(state === 'idle' || state === 'empty') && noteList(notes.slice(0, 2))}
        {composerOpen && (
          <form
            className="seed-collaboration__composer"
            onSubmit={async (event) => {
              event.preventDefault()
              if (!body.trim()) return
              if (await onSubmit(body.trim(), mentionedIds)) {
                setBody('')
                setMentionedIds([])
                setComposerOpen(false)
                openButtonRef.current?.focus()
              }
            }}
          >
            <label>
              <span className="seed-visually-hidden">협업 메모</span>
              <textarea
                maxLength={4000}
                onChange={(event) => setBody(event.target.value)}
                placeholder="팀에 남길 메모를 입력하세요"
                ref={bodyRef}
                value={body}
              />
            </label>
            <SeedChoiceField
              label="@ 멘션 추가"
              onChange={(id) =>
                setMentionedIds((current) =>
                  current.includes(id) ? current : [...current, id],
                )
              }
              options={people
                .filter((person) => !mentionedIds.includes(person.id))
                .map((person) => ({
                  value: person.id,
                  label: person.label,
                  startAdornment: (
                    <SeedAvatar
                      initials={person.initials}
                      label={person.label}
                      size="small"
                    />
                  ),
                }))}
              placeholder="직원 선택"
              value={null}
            />
            {mentionedIds.length > 0 && (
              <div className="seed-collaboration__mentions">
                {mentionedIds.map((id) => {
                  const person = people.find((item) => item.id === id)
                  return person ? (
                    <button
                      aria-label={`${person.label} 멘션 제거`}
                      key={id}
                      onClick={() =>
                        setMentionedIds((current) =>
                          current.filter((item) => item !== id),
                        )
                      }
                      type="button"
                    >
                      @{person.label} <SeedIcon name="x" size="small" />
                    </button>
                  ) : null
                })}
              </div>
            )}
            <footer>
              <SeedButton
                onClick={() => {
                  setComposerOpen(false)
                  openButtonRef.current?.focus()
                }}
                size="compact"
              >
                취소
              </SeedButton>
              <SeedButton
                disabled={submitting || !body.trim()}
                size="compact"
                type="submit"
                variant="primary"
              >
                {submitting ? '작성 중…' : '메모 추가'}
              </SeedButton>
            </footer>
          </form>
        )}
        {notes.length > 2 && (
          <button
            className="seed-collaboration__view-all"
            onClick={() => setDrawerOpen(true)}
            type="button"
          >
            모든 메모 보기
          </button>
        )}
      </SeedContextCard>
      <SeedDrawer
        onClose={() => setDrawerOpen(false)}
        open={drawerOpen}
        returnFocusRef={openButtonRef as RefObject<HTMLElement>}
        title="모든 협업 메모"
      >
        {noteList(notes)}
      </SeedDrawer>
    </>
  )
}

export interface SeedNotificationItem {
  id: string
  title: string
  description: string
  timestamp: string
  unread: boolean
}

export function SeedNotificationMenu({
  items,
  unreadCount,
  state,
  onSelect,
  onRetry,
}: {
  items: SeedNotificationItem[]
  unreadCount: number
  state: SeedAsyncState
  onSelect: (id: string) => void
  onRetry?: () => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <div className="seed-notification-menu">
      <SeedIconButton
        aria-expanded={open}
        aria-haspopup="menu"
        icon="notification"
        label={`알림${unreadCount ? `, 읽지 않음 ${unreadCount}개` : ''}`}
        onClick={() => setOpen((current) => !current)}
        variant="quiet"
      />
      {unreadCount > 0 && (
        <span aria-hidden="true" className="seed-notification-menu__badge">
          {unreadCount > 99 ? '99+' : unreadCount}
        </span>
      )}
      {open && (
        <div
          aria-label="알림"
          className="seed-notification-menu__panel"
          role="menu"
        >
          <header>
            <strong>알림</strong>
            <span>{unreadCount}개 읽지 않음</span>
          </header>
          {state === 'loading' && (
            <SeedFeedbackState
              compact
              kind="loading"
              title="알림을 불러오는 중"
            />
          )}
          {state === 'empty' && (
            <SeedFeedbackState
              compact
              kind="empty"
              title="새 알림이 없습니다"
            />
          )}
          {state === 'denied' && (
            <SeedFeedbackState
              compact
              kind="denied"
              title="알림을 볼 권한이 없습니다"
            />
          )}
          {state === 'error' && (
            <SeedFeedbackState
              action={
                onRetry && (
                  <SeedButton onClick={onRetry} size="compact">
                    다시 시도
                  </SeedButton>
                )
              }
              compact
              kind="error"
              title="알림을 불러오지 못했습니다"
            />
          )}
          {items.map((item) => (
            <button
              className={item.unread ? 'is-unread' : ''}
              key={item.id}
              onClick={() => {
                setOpen(false)
                onSelect(item.id)
              }}
              role="menuitem"
              type="button"
            >
              <SeedIcon name="at" size="small" />
              <span>
                <strong>{item.title}</strong>
                <small>{item.description}</small>
                <time>{item.timestamp}</time>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export function SeedComposerFooter({
  left,
  status,
  actions,
}: {
  left: ReactNode
  status?: ReactNode
  actions: ReactNode
}) {
  return (
    <footer className="seed-composer-footer">
      <div>{left}</div>
      <div>
        {status}
        {actions}
      </div>
    </footer>
  )
}
