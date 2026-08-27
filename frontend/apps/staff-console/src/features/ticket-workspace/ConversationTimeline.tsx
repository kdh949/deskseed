import agentAvatar from '../../assets/deskseed/agent-mina-park-v1.png'
import customerAvatar from '../../assets/deskseed/customer-kim-jiyeon-v1.png'
import attachmentPreview from '../../assets/deskseed/payment-error-attachment-v1.png'
import { DeskseedIcon } from '../../design-system/primitives/DeskseedIcon'
import { DsAvatar } from '../../design-system/primitives/DeskseedPrimitives'
import type { ConversationEntry } from './ticketWorkspaceFixture'

type ConversationTimelineProps = { entries: ConversationEntry[] }

export function ConversationTimeline({ entries }: ConversationTimelineProps) {
  return (
    <section aria-label="티켓 대화 기록" className="conversation-timeline">
      {entries.map((entry, index) => {
        if (entry.kind === 'system') {
          return (
            <article
              className="conversation-entry conversation-entry--system"
              key={`${entry.timestamp}-${index}`}
            >
              <span className="conversation-system-icon">
                <DeskseedIcon name="gear" />
              </span>
              <div>
                <div className="conversation-meta">
                  <strong>System</strong>
                  <span className="conversation-visibility">
                    <DeskseedIcon name="lock" size="sm" /> INTERNAL
                  </span>
                  <time>{entry.timestamp}</time>
                </div>
                <p>{entry.body}</p>
              </div>
            </article>
          )
        }

        const isAgent = entry.author === 'agent'
        const isInternal = entry.visibility === 'internal'
        return (
          <article
            className={`conversation-entry conversation-entry--${entry.author} ${isInternal ? 'conversation-entry--internal' : ''}`.trim()}
            key={`${entry.timestamp}-${index}`}
          >
            <DsAvatar
              name={entry.name}
              size="lg"
              src={isAgent ? agentAvatar : customerAvatar}
            />
            <div className="conversation-entry-body">
              <div className="conversation-meta">
                <strong>{entry.name}</strong>
                <span
                  className={`conversation-role conversation-role--${entry.author}`}
                >
                  {isAgent ? 'Agent' : 'Customer'}
                </span>
                <span className="conversation-visibility">
                  <DeskseedIcon name={isInternal ? 'lock' : 'eye'} size="sm" />{' '}
                  {isInternal ? 'INTERNAL' : 'PUBLIC'}
                </span>
                <time>{entry.timestamp}</time>
              </div>
              {entry.body.map((paragraph) => (
                <p key={paragraph}>{paragraph}</p>
              ))}
              {entry.attachment ? (
                <div className="conversation-attachment">
                  <img
                    alt="결제 오류 화면의 첨부 미리보기"
                    src={attachmentPreview}
                  />
                  <span>
                    <strong>{entry.attachment.name}</strong>
                    <small>{entry.attachment.size}</small>
                  </span>
                  <button
                    aria-label={`${entry.attachment.name} 다운로드`}
                    className="conversation-download"
                    type="button"
                  >
                    <DeskseedIcon name="download" />
                  </button>
                </div>
              ) : null}
            </div>
          </article>
        )
      })}
    </section>
  )
}
