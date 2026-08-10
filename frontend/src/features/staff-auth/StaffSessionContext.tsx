import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  ApiError,
  getCurrentStaff,
  loginStaff,
  logoutStaff,
  STAFF_SESSION_INVALID_EVENT,
} from '../../api/client'
import type { CurrentStaff } from '../../api/types'

type SessionStatus = 'loading' | 'authenticated' | 'anonymous' | 'error'

interface StaffSessionValue {
  status: SessionStatus
  staff: CurrentStaff | null
  signIn: (email: string, password: string) => Promise<CurrentStaff>
  signOut: () => Promise<void>
  retry: () => Promise<void>
}

const StaffSessionContext = createContext<StaffSessionValue | null>(null)

export function StaffSessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading')
  const [staff, setStaff] = useState<CurrentStaff | null>(null)

  const refresh = useCallback(async () => {
    setStatus('loading')
    try {
      const current = await getCurrentStaff()
      setStaff(current)
      setStatus('authenticated')
    } catch (error) {
      setStaff(null)
      setStatus(
        error instanceof ApiError && error.status === 401
          ? 'anonymous'
          : 'error',
      )
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    const invalidate = () => {
      setStaff(null)
      setStatus('anonymous')
    }
    window.addEventListener(STAFF_SESSION_INVALID_EVENT, invalidate)
    return () =>
      window.removeEventListener(STAFF_SESSION_INVALID_EVENT, invalidate)
  }, [])

  const signIn = useCallback(async (email: string, password: string) => {
    await loginStaff(email, password)
    const current = await getCurrentStaff()
    setStaff(current)
    setStatus('authenticated')
    return current
  }, [])

  const signOut = useCallback(async () => {
    try {
      await logoutStaff()
    } finally {
      setStaff(null)
      setStatus('anonymous')
    }
  }, [])

  const value = useMemo(
    () => ({ status, staff, signIn, signOut, retry: refresh }),
    [refresh, signIn, signOut, staff, status],
  )

  return (
    <StaffSessionContext.Provider value={value}>
      {children}
    </StaffSessionContext.Provider>
  )
}

export function useStaffSession(): StaffSessionValue {
  const value = useContext(StaffSessionContext)
  if (!value) throw new Error('StaffSessionProvider is required')
  return value
}
