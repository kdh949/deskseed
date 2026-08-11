import type { AgentComment } from '../../api/types'
import type { ReactNode } from 'react'
import { ConversationTimeline } from '../../shared/ui/system'

export function TicketConversation({
  comments,
  footer,
}: {
  comments: AgentComment[]
  footer?: ReactNode
}) {
  return (
    <ConversationTimeline
      items={comments.map((comment) => ({
        id: comment.id,
        visibility: comment.visibility,
        author: comment.actor.displayName,
        body: comment.body,
        createdAt: comment.createdAt,
      }))}
      footer={
        footer ?? (
          <footer className="read-only-footer">
            <span aria-hidden="true">◇</span>
            <div>
              <strong>읽기 전용</strong>
              <p>이 슬라이스에서는 답변과 티켓 변경을 제공하지 않습니다.</p>
            </div>
          </footer>
        )
      }
    />
  )
}
