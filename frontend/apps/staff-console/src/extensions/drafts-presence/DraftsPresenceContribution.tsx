import {
  SeedAvatar,
  SeedButton,
  SeedContextCard,
  SeedStatusBadge,
} from '../../design-system/canonical'
import { useTicketCollaboration } from './useTicketCollaboration'
import type {
  CollaborationMember,
  CollaborationView,
} from './collaborationRealtime'

export function TicketPresenceContext({
  ticketNumber,
}: {
  ticketNumber: number
}) {
  const collaboration = useTicketCollaboration({ ticketNumber })

  return (
    <aside aria-label="함께 작업 중인 상담사" className="seed-presence">
      <SeedContextCard
        badge={<ConnectionState state={collaboration.connection} />}
        title="함께 작업 중"
      >
        <p aria-live="polite">{connectionMessage(collaboration)}</p>
        {collaboration.connection === 'unavailable' &&
        collaboration.reconnectAt ? (
          <p>
            연결을 자동으로 다시 시도합니다. 다음 시도{' '}
            <time dateTime={new Date(collaboration.reconnectAt).toISOString()}>
              {formatTime(collaboration.reconnectAt)}
            </time>
          </p>
        ) : null}
        {collaboration.lastConfirmedAt ? (
          <p>
            마지막 상태 확인{' '}
            <time dateTime={collaboration.lastConfirmedAt}>
              {formatTime(collaboration.lastConfirmedAt)}
            </time>
          </p>
        ) : null}
        {collaboration.connection === 'unavailable' ||
        collaboration.connection === 'denied' ? (
          <SeedButton onClick={collaboration.retry}>연결 다시 확인</SeedButton>
        ) : null}
        {collaboration.connection === 'connected' &&
        collaboration.snapshotReceived &&
        collaboration.members.length > 0 ? (
          <ul
            aria-label="현재 작업 중인 상담사"
            className="seed-presence__list"
          >
            {collaboration.members.map((member) => (
              <PresenceMember key={member.staffId} member={member} />
            ))}
          </ul>
        ) : null}
        <p>
          작업 상태 공유는 동시 편집을 막지 않습니다. 티켓 저장 결과와 초안 보관
          상태는 별도로 확인해 주세요.
        </p>
        {collaboration.ticketUpdate ? (
          <section aria-live="polite" className="seed-presence__update">
            <strong>새 티켓 버전이 저장되었습니다.</strong>
            <p>
              새로고침 전에 작성 중인 내용의 초안 보관 상태를 확인해 주세요.
            </p>
            <SeedButton onClick={() => window.location.reload()}>
              최신 버전 확인
            </SeedButton>
          </section>
        ) : null}
      </SeedContextCard>
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
  const mode = composerMode === 'public' ? '공개 답변' : '내부 메모'
  const message =
    collaboration.connection === 'connected' && collaboration.snapshotReceived
      ? `${mode} 작성 상태를 공유 중입니다.`
      : collaboration.connection === 'connecting' ||
          (collaboration.connection === 'connected' &&
            !collaboration.snapshotReceived)
        ? '실시간 작업 상태를 확인하는 중입니다.'
        : '실시간 작업 상태가 공유되지 않습니다. 초안 보관 상태를 별도로 확인해 주세요.'

  return (
    <p aria-live="polite" className="seed-presence__composer-status">
      {message}
    </p>
  )
}

function ConnectionState({
  state,
}: {
  state: CollaborationView['connection']
}) {
  const label =
    state === 'connected'
      ? '연결됨'
      : state === 'connecting'
        ? '연결 중'
        : state === 'denied'
          ? '권한 없음'
          : state === 'unsupported'
            ? '지원 안 됨'
            : '연결 안 됨'
  const tone =
    state === 'connected'
      ? 'positive'
      : state === 'denied'
        ? 'danger'
        : state === 'unavailable'
          ? 'warning'
          : 'neutral'
  return <SeedStatusBadge tone={tone}>{label}</SeedStatusBadge>
}

function PresenceMember({ member }: { member: CollaborationMember }) {
  return (
    <li>
      <SeedAvatar
        initials={member.displayName.slice(0, 2)}
        label={member.displayName}
        size="small"
      />
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
      return '공개 답변 작성 중'
    case 'EDITING_INTERNAL':
      return '내부 메모 작성 중'
    case 'AWAY':
      return '잠시 자리를 비움'
    case 'VIEWING':
      return '티켓 열람 중'
  }
}

function connectionMessage(view: CollaborationView) {
  switch (view.connection) {
    case 'connecting':
      return '실시간 작업 상태에 연결하는 중입니다.'
    case 'connected':
      return !view.snapshotReceived
        ? '연결되었습니다. 상담사 상태를 확인하는 중입니다.'
        : view.members.length === 0
          ? '현재 이 티켓을 보는 다른 상담사가 없습니다.'
          : '이 티켓을 함께 확인하는 상담사입니다.'
    case 'unavailable':
      return '실시간 작업 상태를 연결하지 못했습니다. 이전 상담사 목록은 표시하지 않습니다.'
    case 'denied':
      return '세션 또는 티켓 권한을 확인하지 못했습니다. 로그인 상태와 접근 권한을 확인한 뒤 다시 연결해 주세요.'
    case 'unsupported':
      return '이 브라우저에서는 실시간 작업 상태 연결을 지원하지 않습니다.'
  }
}

function formatTime(value: string | number) {
  return new Date(value).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}
