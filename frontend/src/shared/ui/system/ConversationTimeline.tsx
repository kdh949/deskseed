export interface ConversationTimelineItem {
  id: string
  visibility: 'PUBLIC' | 'INTERNAL'
  author: string
  body: string
  createdAt: string
}

interface ConversationTimelineProps {
  items: ConversationTimelineItem[]
  footer?: React.ReactNode
}

export function ConversationTimeline({
  items,
  footer,
}: ConversationTimelineProps) {
  return (
    <section className="ticket-conversation" aria-label="대화">
      <header className="workspace-panel-header">
        <div>
          <p className="agent-page-eyebrow">CONVERSATION</p>
          <h2>대화</h2>
        </div>
        <span className="conversation-count">{items.length}</span>
      </header>
      <ol
        className="conversation-list"
        aria-label="티켓 대화 목록"
        tabIndex={0}
      >
        {items.map((item) => (
          <li
            className={`conversation-entry conversation-${item.visibility.toLowerCase()}`}
            key={item.id}
          >
            <article
              aria-label={`${item.author}의 ${item.visibility === 'PUBLIC' ? '공개 답변' : '내부 메모'}`}
            >
              <header>
                <span className="comment-avatar" aria-hidden="true">
                  {item.author.slice(0, 1)}
                </span>
                <div>
                  <strong>{item.author}</strong>
                  <time dateTime={item.createdAt}>
                    {formatTimestamp(item.createdAt)}
                  </time>
                </div>
                <span
                  className={`visibility-badge visibility-${item.visibility.toLowerCase()}`}
                >
                  <span aria-hidden="true">
                    {item.visibility === 'PUBLIC' ? '↗' : '◆'}
                  </span>{' '}
                  {item.visibility === 'PUBLIC' ? '공개 답변' : '내부 메모'}
                </span>
              </header>
              <p>{item.body}</p>
            </article>
          </li>
        ))}
      </ol>
      {footer}
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
