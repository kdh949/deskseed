import type { AgentComment } from '../../api/types'

export function TicketConversation({ comments }: { comments: AgentComment[] }) {
  return (
    <section className="ticket-conversation" aria-label="대화">
      <header className="workspace-panel-header">
        <div>
          <p className="agent-page-eyebrow">CONVERSATION</p>
          <h2>대화</h2>
        </div>
        <span className="conversation-count">{comments.length}</span>
      </header>
      <ol className="conversation-list">
        {comments.map((comment) => (
          <li
            className={`conversation-entry conversation-${comment.visibility.toLowerCase()}`}
            key={comment.id}
          >
            <header>
              <span className="comment-avatar" aria-hidden="true">
                {comment.actor.displayName.slice(0, 1)}
              </span>
              <div>
                <strong>{comment.actor.displayName}</strong>
                <time dateTime={comment.createdAt}>
                  {formatTimestamp(comment.createdAt)}
                </time>
              </div>
              <span
                className={`visibility-badge visibility-${comment.visibility.toLowerCase()}`}
              >
                {comment.visibility === 'PUBLIC' ? '공개 답변' : '내부 메모'}
              </span>
            </header>
            <p>{comment.body}</p>
          </li>
        ))}
      </ol>
      <footer className="read-only-footer">
        <span aria-hidden="true">◇</span>
        <div>
          <strong>읽기 전용</strong>
          <p>이 슬라이스에서는 답변과 티켓 변경을 제공하지 않습니다.</p>
        </div>
      </footer>
    </section>
  )
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
