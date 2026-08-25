import { useId, type FormEvent } from 'react'
import { Notification, ScreenState } from '../components/Feedback'
import { DsButton } from '../primitives/DeskseedControls'

export type CustomerRequestLookupResult = 'invalid' | 'missing' | null

type CustomerRequestLookupPanelProps = {
  onSubmit: () => void
  onTicketNumberChange: (value: string) => void
  result: CustomerRequestLookupResult
  ticketNumber: string
}

export function CustomerRequestLookupPanel({
  onSubmit,
  onTicketNumberChange,
  result,
  ticketNumber,
}: CustomerRequestLookupPanelProps) {
  const headingId = useId()
  const ticketNumberId = useId()

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onSubmit()
  }

  return (
    <section aria-labelledby={headingId} className="ds-customer-request-lookup">
      <header>
        <h1 id={headingId}>문의 조회</h1>
        <p>
          이메일의 안전한 문의 링크를 먼저 연 경우, 같은 브라우저에서 문의
          번호로 다시 열 수 있습니다.
        </p>
      </header>
      <form noValidate onSubmit={handleSubmit}>
        <label htmlFor={ticketNumberId}>
          문의 번호
          <input
            aria-invalid={result === 'invalid' || undefined}
            autoComplete="off"
            id={ticketNumberId}
            inputMode="numeric"
            onChange={(event) => onTicketNumberChange(event.target.value)}
            pattern="[0-9]*"
            value={ticketNumber}
          />
        </label>
        <DsButton tone="primary" type="submit">
          문의 열기
        </DsButton>
      </form>
      <div className="ds-customer-request-lookup-state">
        {result === 'invalid' ? (
          <Notification title="문의 번호를 확인해 주세요." tone="danger">
            <p>1보다 큰 정수로 된 문의 번호를 입력해 주세요.</p>
          </Notification>
        ) : result === 'missing' ? (
          <Notification title="이메일 문의 링크가 필요합니다." tone="warning">
            <p>
              이 브라우저에서 이 문의를 열었던 이메일 링크가 필요합니다. 토큰을
              입력하거나 붙여넣는 방식은 제공하지 않습니다.
            </p>
          </Notification>
        ) : (
          <ScreenState
            compact
            description="이메일에 표시된 문의 번호를 입력해 주세요."
            kind="empty"
            title="열 문의를 선택하세요"
          />
        )}
      </div>
    </section>
  )
}
