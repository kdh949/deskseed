import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { SubmittedRequest } from '../../api/types'
import { StatusBadge } from '../../components/StatusBadge'

interface RequestSuccessProps {
  submitted: SubmittedRequest
  onReset(): void
}

export function RequestSuccess({ submitted, onReset }: RequestSuccessProps) {
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [copyStatus, setCopyStatus] = useState('')

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  const copyToken = async () => {
    try {
      await navigator.clipboard.writeText(submitted.accessToken)
      setCopyStatus('조회 키를 복사했습니다.')
    } catch {
      setCopyStatus('복사하지 못했습니다. 조회 키를 직접 선택해 복사해 주세요.')
    }
  }

  return (
    <section
      className="narrow-panel success-panel"
      aria-labelledby="success-title"
    >
      <p className="eyebrow">접수가 완료되었습니다</p>
      <h1 id="success-title" ref={headingRef} tabIndex={-1}>
        문의 #{submitted.ticketNumber}
      </h1>
      <StatusBadge status={submitted.status} />
      <p>
        아래 조회 키는 지금 한 번만 표시됩니다. 새로고침하거나 이 탭을 닫으면
        사라지며, 서버에서도 원문을 다시 확인할 수 없습니다.
      </p>
      <div className="token-box">
        <code aria-label="문의 조회 키">{submitted.accessToken}</code>
        <button
          className="button small secondary"
          type="button"
          onClick={copyToken}
        >
          키 복사
        </button>
      </div>
      <p className="muted" aria-live="polite">
        {copyStatus}
      </p>
      <div className="button-row">
        <Link
          className="button primary"
          to={`/requests/${submitted.ticketNumber}`}
        >
          문의 내용 보기
        </Link>
        <button className="button secondary" type="button" onClick={onReset}>
          다른 문의 접수
        </button>
      </div>
    </section>
  )
}
