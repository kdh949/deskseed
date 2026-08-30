import type { CustomerSummary } from '../../api/types'
import {
  SeedAvatar,
  SeedButton,
  SeedNotice,
  SeedStatusBadge,
  SeedTabs,
  SeedTextField,
} from '../../design-system/canonical'
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
  return (
    <div className="seed-requester">
      <SeedTabs
        active={tab}
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
            className="seed-requester__selected"
            role="group"
          >
            <SeedAvatar
              initials={initials(selectedCustomer.name)}
              label={selectedCustomer.name}
            />
            <span>
              <strong>{selectedCustomer.name}</strong>
              <small>{selectedCustomer.email}</small>
            </span>
            {selectedCustomer.verified && (
              <SeedStatusBadge tone="positive">인증된 고객</SeedStatusBadge>
            )}
            <SeedButton onClick={() => onSelectCustomer(null)}>
              다시 검색
            </SeedButton>
          </div>
        ) : (
          <div className="seed-requester__search">
            <SeedTextField
              autoComplete="off"
              label="이름 또는 이메일로 검색"
              leadingIcon="search"
              maxLength={200}
              onChange={(event) => onQueryChange(event.target.value)}
              value={query}
            />
            {searching && <p role="status">검색 중…</p>}
            {searchError && (
              <SeedNotice title="고객 검색 실패" tone="danger">
                {searchError}
              </SeedNotice>
            )}
            {!searching && results.length > 0 && (
              <ul aria-label="검색 결과" className="seed-requester__results">
                {results.map((customer) => (
                  <li key={customer.id}>
                    <button
                      onClick={() => onSelectCustomer(customer)}
                      type="button"
                    >
                      <SeedAvatar
                        initials={initials(customer.name)}
                        label={customer.name}
                        size="small"
                      />
                      <span>
                        <strong>{customer.name}</strong>
                        <small>{customer.email}</small>
                      </span>
                      {customer.verified && (
                        <SeedStatusBadge tone="positive">인증</SeedStatusBadge>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {!searching &&
              !searchError &&
              query.trim() &&
              results.length === 0 && (
                <p className="seed-requester__empty">
                  일치하는 고객이 없습니다. 새 고객 등록 탭을 이용해 주세요.
                </p>
              )}
          </div>
        )
      ) : (
        <div className="seed-requester__new">
          <SeedTextField
            label="이름"
            maxLength={100}
            onChange={(event) => onNewNameChange(event.target.value)}
            value={newName}
          />
          <SeedTextField
            label="이메일"
            maxLength={254}
            onChange={(event) => onNewEmailChange(event.target.value)}
            type="email"
            value={newEmail}
          />
        </div>
      )}
    </div>
  )
}

function initials(name: string) {
  return (
    name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .join('')
      .toUpperCase() || 'DS'
  )
}
