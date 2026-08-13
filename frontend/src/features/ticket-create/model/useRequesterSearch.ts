import { useEffect, useRef, useState } from 'react'
import { ApiError, searchAgentCustomers } from '../../../api/client'
import type { CustomerSummary } from '../../../api/types'

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
  const interactionIdRef = useRef(createInteractionId())

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
      searchAgentCustomers(
        { query: trimmed, limit: 10 },
        interactionIdRef.current,
      )
        .then((page) => {
          setResults(page.items)
        })
        .catch((cause: unknown) => {
          const apiError = cause instanceof ApiError ? cause : null
          setSearchError(
            apiError?.message ??
              '고객을 검색하지 못했습니다. 다시 시도해 주세요.',
          )
          setResults([])
        })
        .finally(() => {
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

function createInteractionId(): string {
  const webCrypto = globalThis.crypto
  if (webCrypto?.randomUUID) return webCrypto.randomUUID()

  const bytes = new Uint8Array(16)
  if (webCrypto?.getRandomValues) webCrypto.getRandomValues(bytes)
  else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
