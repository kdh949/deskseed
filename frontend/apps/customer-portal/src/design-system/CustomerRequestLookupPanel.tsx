import { useId, type FormEvent } from 'react'
import { DsButton, Notification, ScreenState } from './CustomerPrimitives'

export type CustomerRequestLookupResult = 'invalid' | 'missing' | null

export function CustomerRequestLookupPanel({
  onSubmit,
  onTicketNumberChange,
  result,
  ticketNumber,
}: {
  onSubmit: () => void
  onTicketNumberChange: (value: string) => void
  result: CustomerRequestLookupResult
  ticketNumber: string
}) {
  const inputId = useId()
  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit()
  }
  return (
    <section className="customer-lookup-card">
      <header>
        <h1>문의 번호로 빠르게 확인하세요</h1>
        <p>이메일에 표시된 문의 번호를 입력해 주세요.</p>
      </header>
      <form onSubmit={submit}>
        <label htmlFor={inputId}>문의 번호</label>
        <div>
          <input
            aria-invalid={result === 'invalid' || undefined}
            id={inputId}
            inputMode="numeric"
            onChange={(event) => onTicketNumberChange(event.target.value)}
            placeholder="예: 1288"
            value={ticketNumber}
          />
          <DsButton tone="primary" type="submit">
            문의 열기
          </DsButton>
        </div>
      </form>
      {result === 'invalid' ? (
        <Notification title="문의 번호를 확인해 주세요." tone="danger" />
      ) : result === 'missing' ? (
        <Notification title="이메일 문의 링크가 필요합니다." tone="warning">
          <p>이메일로 받은 문의 링크를 다시 열어 주세요.</p>
        </Notification>
      ) : (
        <ScreenState
          compact
          description="이메일에 표시된 문의 번호를 입력해 주세요."
          kind="empty"
          title="조회할 문의를 선택하세요"
        />
      )}
    </section>
  )
}
