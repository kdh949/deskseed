import { useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router'
import { useRequestAccess } from '../features/customer-requests/RequestAccessContext'

const TOKEN_MIN_LENGTH = 32
const TOKEN_MAX_LENGTH = 256

export function LookupPage() {
  const navigate = useNavigate()
  const requestAccess = useRequestAccess()
  const queryClient = useQueryClient()
  const [ticketNumber, setTicketNumber] = useState('')
  const [token, setToken] = useState('')
  const number = Number(ticketNumber)
  const cleanToken = token.trim()
  const validNumber = Number.isSafeInteger(number) && number > 0
  const validToken =
    cleanToken.length >= TOKEN_MIN_LENGTH &&
    cleanToken.length <= TOKEN_MAX_LENGTH
  const isValid = validNumber && validToken

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isValid) return
    queryClient.removeQueries({ queryKey: ['public-request', number] })
    requestAccess.setAccessToken(number, cleanToken)
    navigate(`/requests/${number}`)
  }

  return (
    <section className="narrow-panel" aria-labelledby="lookup-title">
      <p className="eyebrow">문의 조회</p>
      <h1 id="lookup-title">접수한 문의를 확인하세요.</h1>
      <p className="muted">
        접수 완료 화면에 표시된 접수 번호와 조회 키가 모두 필요합니다. 조회 키는
        이 탭의 메모리에서만 사용합니다.
      </p>
      <form className="support-form" onSubmit={submit} noValidate>
        <label className="form-field" htmlFor="lookup-ticket-number">
          <span>
            접수 번호 <span aria-hidden="true">*</span>
          </span>
          <input
            id="lookup-ticket-number"
            required
            inputMode="numeric"
            autoComplete="off"
            placeholder="예: 1042"
            value={ticketNumber}
            aria-describedby="lookup-ticket-number-help"
            onChange={(event) =>
              setTicketNumber(event.target.value.replace(/[^0-9]/g, ''))
            }
          />
          <small id="lookup-ticket-number-help">숫자로 된 접수 번호</small>
        </label>
        <label className="form-field" htmlFor="lookup-access-key">
          <span>
            조회 키 <span aria-hidden="true">*</span>
          </span>
          <textarea
            id="lookup-access-key"
            required
            rows={3}
            minLength={TOKEN_MIN_LENGTH}
            maxLength={TOKEN_MAX_LENGTH}
            autoComplete="off"
            spellCheck={false}
            value={token}
            aria-describedby="lookup-access-key-help"
            onChange={(event) => setToken(event.target.value)}
          />
          <small id="lookup-access-key-help">
            접수 완료 화면에서 발급된 32자 이상의 키를 입력하세요.
          </small>
        </label>
        <button className="button primary" type="submit" disabled={!isValid}>
          문의 보기
        </button>
      </form>
    </section>
  )
}
