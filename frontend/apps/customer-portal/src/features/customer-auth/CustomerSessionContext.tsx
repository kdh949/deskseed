import { useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { customerRequestQueryKeys } from '../customer-portal/customerRequestQueryKeys'
import {
  CustomerAuthApiError,
  deleteCustomerSession,
  getCurrentCustomer,
  type CurrentCustomer,
} from './api/customerAuthClient'

export type CustomerSessionStatus =
  'loading' | 'authenticated' | 'anonymous' | 'error'

interface CustomerSessionValue {
  acceptAuthenticatedCustomer: (customer: CurrentCustomer) => void
  customer: CurrentCustomer | null
  retry: () => Promise<void>
  signOut: () => Promise<void>
  signingOut: boolean
  status: CustomerSessionStatus
}

const CustomerSessionContext = createContext<CustomerSessionValue | null>(null)

export function CustomerSessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [customer, setCustomer] = useState<CurrentCustomer | null>(null)
  const [status, setStatus] = useState<CustomerSessionStatus>('loading')
  const [signingOut, setSigningOut] = useState(false)
  const customerIdRef = useRef<string | null>(null)
  const requestVersion = useRef(0)

  const clearOwnedRequestQueries = useCallback(() => {
    void queryClient.cancelQueries({
      queryKey: customerRequestQueryKeys.listRoot,
    })
    void queryClient.cancelQueries({
      queryKey: customerRequestQueryKeys.detailRoot,
    })
    queryClient.removeQueries({ queryKey: customerRequestQueryKeys.listRoot })
    queryClient.removeQueries({ queryKey: customerRequestQueryKeys.detailRoot })
  }, [queryClient])

  const replaceCustomer = useCallback(
    (nextCustomer: CurrentCustomer | null) => {
      const nextCustomerId = nextCustomer?.id ?? null
      if (customerIdRef.current !== nextCustomerId) {
        clearOwnedRequestQueries()
        customerIdRef.current = nextCustomerId
      }
      setCustomer(nextCustomer)
    },
    [clearOwnedRequestQueries],
  )

  const refresh = useCallback(async () => {
    const version = ++requestVersion.current
    setStatus('loading')
    try {
      const current = await getCurrentCustomer()
      if (version !== requestVersion.current) return
      replaceCustomer(current)
      setStatus(current ? 'authenticated' : 'anonymous')
    } catch {
      if (version !== requestVersion.current) return
      replaceCustomer(null)
      setStatus('error')
    }
  }, [replaceCustomer])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const signOut = useCallback(async () => {
    setSigningOut(true)
    try {
      await deleteCustomerSession()
      requestVersion.current += 1
      replaceCustomer(null)
      setStatus('anonymous')
    } catch (error) {
      if (error instanceof CustomerAuthApiError && error.status === 401) {
        requestVersion.current += 1
        replaceCustomer(null)
        setStatus('anonymous')
        return
      }
      throw error
    } finally {
      setSigningOut(false)
    }
  }, [replaceCustomer])

  const acceptAuthenticatedCustomer = useCallback(
    (current: CurrentCustomer) => {
      requestVersion.current += 1
      replaceCustomer(current)
      setStatus('authenticated')
    },
    [replaceCustomer],
  )

  const value = useMemo(
    () => ({
      acceptAuthenticatedCustomer,
      customer,
      retry: refresh,
      signOut,
      signingOut,
      status,
    }),
    [
      acceptAuthenticatedCustomer,
      customer,
      refresh,
      signOut,
      signingOut,
      status,
    ],
  )

  return (
    <CustomerSessionContext.Provider value={value}>
      {children}
    </CustomerSessionContext.Provider>
  )
}

export function useCustomerSession() {
  const value = useContext(CustomerSessionContext)
  if (!value) throw new Error('CustomerSessionProvider is required')
  return value
}

export function useOptionalCustomerSession() {
  return useContext(CustomerSessionContext)
}
