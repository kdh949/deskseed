import { useEffect, useRef, useState } from 'react'
import { ApiError, searchAgentCustomers } from '../../../api/client'
import type { CustomerSummary } from '../../../api/types'
import { createOpaqueUuid } from '../../../api/uuid'

const SEARCH_DEBOUNCE_MS = 300
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export type RequesterTab = 'search' | 'new'

export type RequesterSelection =
  | { mode: 'existing'; customer: CustomerSummary }
  | { mode: 'new'; name: string; email: string }

export function useRequesterSearch() {
  const [tab, setTabState] = useState<RequesterTab>('search')
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<CustomerSummary[]>([])
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [selectedCustomer, setSelectedCustomer] =
    useState<CustomerSummary | null>(null)
  const [newName, setNewName] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const interactionIdRef = useRef(createOpaqueUuid())
  const latestRequestIdRef = useRef(0)

  useEffect(() => {
    if (tab !== 'search' || selectedCustomer) {
      setResults([])
      return
    }
    const trimmed = query.trim()
    if (trimmed.length === 0) {
      setResults([])
      setSearchError(null)
      setSearching(false)
      return
    }
    setSearching(true)
    setSearchError(null)
    const timeoutId = window.setTimeout(() => {
      const requestId = (latestRequestIdRef.current += 1)
      searchAgentCustomers(
        { query: trimmed, limit: 10 },
        interactionIdRef.current,
      )
        .then((page) => {
          if (latestRequestIdRef.current !== requestId) return
          setResults(page.items)
        })
        .catch((cause: unknown) => {
          if (latestRequestIdRef.current !== requestId) return
          const apiError = cause instanceof ApiError ? cause : null
          setSearchError(
            apiError?.message ??
              '고객을 검색하지 못했습니다. 다시 시도해 주세요.',
          )
          setResults([])
        })
        .finally(() => {
          if (latestRequestIdRef.current !== requestId) return
          setSearching(false)
        })
    }, SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timeoutId)
  }, [query, tab, selectedCustomer])

  const setTab = (nextTab: RequesterTab) => {
    setTabState(nextTab)
    setSelectedCustomer(null)
  }

  const trimmedName = newName.trim()
  const trimmedEmail = newEmail.trim()
  const isNewCustomerValid =
    trimmedName.length > 0 &&
    trimmedName.length <= 100 &&
    trimmedEmail.length <= 254 &&
    EMAIL_PATTERN.test(trimmedEmail)

  const selection: RequesterSelection | null =
    tab === 'search'
      ? selectedCustomer
        ? { mode: 'existing', customer: selectedCustomer }
        : null
      : isNewCustomerValid
        ? { mode: 'new', name: trimmedName, email: trimmedEmail }
        : null

  return {
    tab,
    setTab,
    query,
    setQuery,
    results,
    searching,
    searchError,
    selectedCustomer,
    setSelectedCustomer,
    newName,
    setNewName,
    newEmail,
    setNewEmail,
    selection,
  }
}
