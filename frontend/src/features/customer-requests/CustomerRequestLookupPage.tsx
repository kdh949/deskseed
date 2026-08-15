import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { DsButton, Notification } from '../../design-system'
import { readRequestAccessToken } from '../customer-portal/customerAccessToken'

export function CustomerRequestLookupPage() {
  const navigate = useNavigate()
  const [ticketNumber, setTicketNumber] = useState('')
  const [result, setResult] = useState<'invalid' | 'missing' | null>(null)
  const ticketNumberId = useId()

  const openRequest = () => {
    const parsedTicketNumber = parseTicketNumber(ticketNumber)
    if (parsedTicketNumber === null) {
      setResult('invalid')
      return
    }
    if (!readRequestAccessToken(window.sessionStorage, parsedTicketNumber)) {
      setResult('missing')
      return
    }
    navigate(`/requests/${parsedTicketNumber}`)
  }

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    openRequest()
  }

  return (
    <main className="customer-page">
      <section
        aria-labelledby="customer-lookup-title"
        className="customer-lookup-card"
      >
        <p className="customer-page-eyebrow">문의 조회</p>
        <h1 id="customer-lookup-title">이 브라우저의 문의 열기</h1>
        <p>
          이메일의 안전한 문의 링크를 먼저 연 경우에만, 같은 브라우저에서 문의
          번호로 다시 열 수 있습니다.
        </p>
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
        ) : null}
        <form className="customer-lookup-form" onSubmit={handleSubmit}>
          <label htmlFor={ticketNumberId}>
            문의 번호
            <input
              autoComplete="off"
              id={ticketNumberId}
              inputMode="numeric"
              onChange={(event) => {
                setTicketNumber(event.target.value)
                setResult(null)
              }}
              pattern="[0-9]*"
              value={ticketNumber}
            />
          </label>
          <DsButton onClick={openRequest} tone="primary">
            문의 열기
          </DsButton>
        </form>
      </section>
    </main>
  )
}

function parseTicketNumber(value: string) {
  if (!/^\d+$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) && ticketNumber > 0
    ? ticketNumber
    : null
}
