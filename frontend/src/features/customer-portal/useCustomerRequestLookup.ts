import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router'
import type { CustomerRequestLookupResult } from '../../design-system'
import { readRequestAccessToken } from './customerAccessToken'

export function useCustomerRequestLookup() {
  const navigate = useNavigate()
  const [ticketNumber, setTicketNumber] = useState('')
  const [result, setResult] = useState<CustomerRequestLookupResult>(null)

  const updateTicketNumber = useCallback((value: string) => {
    setTicketNumber(value)
    setResult(null)
  }, [])

  const openRequest = useCallback(() => {
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
  }, [navigate, ticketNumber])

  return {
    openRequest,
    result,
    ticketNumber,
    updateTicketNumber,
  }
}

function parseTicketNumber(value: string) {
  if (!/^\d+$/.test(value)) return null
  const ticketNumber = Number(value)
  return Number.isSafeInteger(ticketNumber) && ticketNumber > 0
    ? ticketNumber
    : null
}
