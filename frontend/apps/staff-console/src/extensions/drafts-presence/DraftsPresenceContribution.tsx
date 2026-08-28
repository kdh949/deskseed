import { useTicketCollaboration } from './useTicketCollaboration'
import type { CollaborationMember } from './collaborationRealtime'

export function TicketPresenceContext({
  ticketNumber,
}: {
  ticketNumber: number
}) {
  const collaboration = useTicketCollaboration({ ticketNumber })

  return (
    <aside
      aria-label="함께 작업 중인 상담사"
      className="ticket-collaboration-presence"
    >
      <div className="ticket-collaboration-presence__heading">
        <h2>함께 작업 중</h2>
        <ConnectionState state={collaboration.connection} />
      </div>
      {collaboration.connection === 'connecting' ? (
        <p aria-live="polite">상담사 presence를 확인하는 중입니다.</p>
      ) : null}
      {collaboration.connection === 'unavailable' ? (
        <p aria-live="polite">
          실시간 presence를 연결하지 못했습니다. 티켓 저장과 초안 복구는 계속
          사용할 수 있습니다.
        </p>
      ) : null}
      {collaboration.connection === 'denied' ? (
        <p aria-live="polite">
          이 티켓의 실시간 presence를 볼 권한이 없습니다. 티켓 저장과 초안
          복구는 계속 사용할 수 있습니다.
        </p>
      ) : null}
      {collaboration.connection === 'connected' &&
      collaboration.members.length === 0 ? (
        <p>현재 이 티켓을 보는 다른 상담사가 없습니다.</p>
      ) : null}
      {collaboration.members.length > 0 ? (
        <ul
          aria-label="현재 presence"
          className="ticket-collaboration-presence__list"
        >
          {collaboration.members.map((member) => (
            <PresenceMember key={member.staffId} member={member} />
          ))}
        </ul>
      ) : null}
      {collaboration.ticketUpdate ? (
        <section
          aria-live="polite"
          className="ticket-collaboration-presence__update"
        >
          <strong>새 티켓 버전이 저장되었습니다.</strong>
          <p>
            현재 작성 중인 내용은 유지됩니다. 최신 내용을 확인하려면 화면을
            새로고침하세요.
          </p>
          <button onClick={() => window.location.reload()} type="button">
            최신 버전 확인
          </button>
        </section>
      ) : null}
    </aside>
  )
}

export function ComposerPresenceStatus({
  composerMode,
  ticketNumber,
}: {
  composerMode: 'public' | 'internal'
  ticketNumber: number
}) {
  const collaboration = useTicketCollaboration({ composerMode, ticketNumber })
  const mode = composerMode === 'public' ? 'PUBLIC 답변' : 'INTERNAL 메모'
  const message =
    collaboration.connection === 'connected'
      ? `${mode} 작성 presence가 공유됩니다.`
      : collaboration.connection === 'connecting'
        ? '실시간 작성 presence를 연결하는 중입니다.'
        : collaboration.connection === 'denied'
          ? '이 티켓의 실시간 작성 presence를 공유할 권한이 없습니다. 초안은 계속 저장됩니다.'
          : '실시간 작성 presence를 사용할 수 없습니다. 초안은 계속 저장됩니다.'

  return (
    <p aria-live="polite" className="ticket-collaboration-composer-status">
      {message}
    </p>
  )
}

function ConnectionState({
  state,
}: {
  state: 'connecting' | 'connected' | 'denied' | 'unavailable'
}) {
  const label =
    state === 'connected'
      ? '연결됨'
      : state === 'connecting'
        ? '연결 중'
        : state === 'denied'
          ? '권한 없음'
          : '연결 안 됨'
  return <span data-state={state}>{label}</span>
}

function PresenceMember({ member }: { member: CollaborationMember }) {
  return (
    <li>
      <span
        aria-hidden="true"
        className="ticket-collaboration-presence__marker"
      >
        {member.state === 'EDITING_PUBLIC' ||
        member.state === 'EDITING_INTERNAL'
          ? '작성'
          : '열람'}
      </span>
      <span>
        <strong>{member.displayName}</strong>
        <small>{presenceLabel(member.state)}</small>
      </span>
    </li>
  )
}

function presenceLabel(state: CollaborationMember['state']) {
  switch (state) {
    case 'EDITING_PUBLIC':
      return 'PUBLIC 답변 작성 중'
    case 'EDITING_INTERNAL':
      return 'INTERNAL 메모 작성 중'
    case 'AWAY':
      return '잠시 자리를 비움'
    case 'VIEWING':
      return '티켓 열람 중'
  }
}
