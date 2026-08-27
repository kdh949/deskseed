import { useId } from 'react'
import type { CustomerSummary } from '../../api/types'
import { DsButton, DsTabs } from '../../design-system'
import type { RequesterTab } from './model/useRequesterSearch'

export type RequesterSearchFieldProps = {
  newEmail: string
  newName: string
  onNewEmailChange: (email: string) => void
  onNewNameChange: (name: string) => void
  onQueryChange: (query: string) => void
  onSelectCustomer: (customer: CustomerSummary | null) => void
  onTabChange: (tab: RequesterTab) => void
  query: string
  results: CustomerSummary[]
  searchError: string | null
  searching: boolean
  selectedCustomer: CustomerSummary | null
  tab: RequesterTab
}

export function RequesterSearchField({
  newEmail,
  newName,
  onNewEmailChange,
  onNewNameChange,
  onQueryChange,
  onSelectCustomer,
  onTabChange,
  query,
  results,
  searchError,
  searching,
  selectedCustomer,
  tab,
}: RequesterSearchFieldProps) {
  const queryId = useId()
  const nameId = useId()
  const emailId = useId()

  return (
    <div className="requester-search-field">
      <DsTabs
        activeId={tab}
        ariaLabel="요청자 지정 방식"
        items={[
          { id: 'search', label: '기존 고객 검색' },
          { id: 'new', label: '새 고객 등록' },
        ]}
        onChange={onTabChange}
      />
      {tab === 'search' ? (
        selectedCustomer ? (
          <div
            aria-label="선택된 요청자"
            className="requester-selected-customer"
            role="group"
          >
            <div className="requester-selected-customer-info">
              <strong>{selectedCustomer.name}</strong>
              <span>{selectedCustomer.email}</span>
              {selectedCustomer.verified ? (
                <span className="requester-verified-badge">인증된 고객</span>
              ) : null}
            </div>
            <DsButton onClick={() => onSelectCustomer(null)} type="button">
              다시 검색
            </DsButton>
          </div>
        ) : (
          <div className="requester-search-combobox">
            <label htmlFor={queryId}>
              <span>이름 또는 이메일로 검색</span>
              <input
                autoComplete="off"
                id={queryId}
                maxLength={200}
                onChange={(event) => onQueryChange(event.target.value)}
                value={query}
              />
            </label>
            {searching ? <p role="status">검색 중…</p> : null}
            {searchError ? <small role="alert">{searchError}</small> : null}
            {!searching && results.length > 0 ? (
              <ul aria-label="검색 결과" className="requester-search-results">
                {results.map((customer) => (
                  <li key={customer.id}>
                    <button
                      className="requester-search-result"
                      onClick={() => onSelectCustomer(customer)}
                      type="button"
                    >
                      <strong>{customer.name}</strong>
                      <span>{customer.email}</span>
                      {customer.verified ? (
                        <span className="requester-verified-badge">
                          인증된 고객
                        </span>
                      ) : null}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
            {!searching &&
            !searchError &&
            query.trim().length > 0 &&
            results.length === 0 ? (
              <p className="requester-search-empty">
                일치하는 고객이 없습니다. &ldquo;새 고객 등록&rdquo; 탭에서 새로
                만들 수 있습니다.
              </p>
            ) : null}
          </div>
        )
      ) : (
        <div className="requester-new-customer">
          <label htmlFor={nameId}>
            <span>이름</span>
            <input
              id={nameId}
              maxLength={100}
              onChange={(event) => onNewNameChange(event.target.value)}
              value={newName}
            />
          </label>
          <label htmlFor={emailId}>
            <span>이메일</span>
            <input
              id={emailId}
              maxLength={254}
              onChange={(event) => onNewEmailChange(event.target.value)}
              type="text"
              value={newEmail}
            />
          </label>
        </div>
      )}
    </div>
  )
}
