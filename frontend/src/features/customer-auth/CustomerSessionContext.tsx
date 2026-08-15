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
import {
  CustomerAuthApiError,
  deleteCustomerSession,
  getCurrentCustomer,
  type CurrentCustomer,
} from './api/customerAuthClient'

export type CustomerSessionStatus =
  'loading' | 'authenticated' | 'anonymous' | 'error'

interface CustomerSessionValue {
  customer: CurrentCustomer | null
  retry: () => Promise<void>
  signOut: () => Promise<void>
  signingOut: boolean
  status: CustomerSessionStatus
}

const CustomerSessionContext = createContext<CustomerSessionValue | null>(null)

export function CustomerSessionProvider({ children }: { children: ReactNode }) {
  const [customer, setCustomer] = useState<CurrentCustomer | null>(null)
  const [status, setStatus] = useState<CustomerSessionStatus>('loading')
  const [signingOut, setSigningOut] = useState(false)
  const requestVersion = useRef(0)

  const refresh = useCallback(async () => {
    const version = ++requestVersion.current
    setStatus('loading')
    try {
      const current = await getCurrentCustomer()
      if (version !== requestVersion.current) return
      setCustomer(current)
      setStatus(current ? 'authenticated' : 'anonymous')
    } catch {
      if (version !== requestVersion.current) return
      setCustomer(null)
      setStatus('error')
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const signOut = useCallback(async () => {
    setSigningOut(true)
    try {
      await deleteCustomerSession()
      requestVersion.current += 1
      setCustomer(null)
      setStatus('anonymous')
    } catch (error) {
      if (error instanceof CustomerAuthApiError && error.status === 401) {
        requestVersion.current += 1
        setCustomer(null)
        setStatus('anonymous')
      }
      throw error
    } finally {
      setSigningOut(false)
    }
  }, [])

  const value = useMemo(
    () => ({ customer, retry: refresh, signOut, signingOut, status }),
    [customer, refresh, signOut, signingOut, status],
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
