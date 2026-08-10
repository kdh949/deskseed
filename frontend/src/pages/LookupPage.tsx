import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router'
import { saveRequestToken } from '../api/tokenStore'

export function LookupPage() {
  const navigate = useNavigate()
  const [ticketNumber, setTicketNumber] = useState('')
  const [token, setToken] = useState('')

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const number = Number(ticketNumber)
    if (!Number.isSafeInteger(number) || number <= 0) return
    saveRequestToken(number, token.trim())
    navigate(`/requests/${number}`)
  }

  return (
    <section className="narrow-panel">
      <p className="eyebrow">문의 조회</p>
      <h1>접수 번호와 조회 키를 입력하세요.</h1>
      <p className="muted">
        같은 브라우저에서 접수했다면 조회 키가 이미 저장되어 있을 수 있습니다.
      </p>
      <form className="support-form" onSubmit={submit}>
        <label>
          접수 번호
          <input
            required
            inputMode="numeric"
            placeholder="예: 1000"
            value={ticketNumber}
            onChange={(event) =>
              setTicketNumber(event.target.value.replace(/[^0-9]/g, ''))
            }
          />
        </label>
        <label>
          조회 키
          <textarea
            required
            rows={3}
            autoComplete="off"
            spellCheck={false}
            value={token}
            onChange={(event) => setToken(event.target.value)}
          />
        </label>
        <button className="button primary" type="submit">
          문의 보기
        </button>
      </form>
    </section>
  )
}
