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
  advanceStaffSessionGeneration,
  ApiError,
  getCurrentStaff,
  isCurrentStaffSessionActorMismatch,
  isCurrentStaffSessionInvalidation,
  loginStaff,
  logoutStaff,
  setConfirmedStaffActor,
  STAFF_SESSION_ACTOR_MISMATCH_EVENT,
  STAFF_SESSION_INVALID_EVENT,
} from '../../api/client'
import type { CurrentStaff } from '../../api/types'
import {
  STAFF_DRAFT_SESSION_OWNER_KEY,
  purgeStaffTicketDrafts,
  sweepStaffTicketDrafts,
} from '../ticket-workspace/model/ticketEditorModel'

type SessionStatus = 'loading' | 'authenticated' | 'anonymous' | 'error'

interface StaffSessionValue {
  status: SessionStatus
  staff: CurrentStaff | null
  signIn: (email: string, password: string) => Promise<CurrentStaff>
  signOut: () => Promise<void>
  retry: () => Promise<void>
}

const StaffSessionContext = createContext<StaffSessionValue | null>(null)

interface StaffSessionProviderProps {
  children: ReactNode
  sessionEventTarget?: EventTarget
}

export function StaffSessionProvider({
  children,
  sessionEventTarget = window,
}: StaffSessionProviderProps) {
  const [status, setStatus] = useState<SessionStatus>('loading')
  const [staff, setStaff] = useState<CurrentStaff | null>(null)
  const [initialStaffId] = useState(() => {
    const rememberedStaffId = readRememberedStaffId(localStorage)
    setConfirmedStaffActor(rememberedStaffId)
    return rememberedStaffId
  })
  const staffRef = useRef<CurrentStaff | null>(null)
  const tabStaffIdRef = useRef<string | null>(initialStaffId)
  const sessionRequestRef = useRef(0)
  const externalOwnerSignalRef = useRef({
    version: 0,
    staffId: null as string | null,
  })

  const clearStaffState = useCallback(
    (
      departingStaffId: string | null,
      removeSessionOwner = false,
      preserveConfirmedActor = false,
    ) => {
      sessionRequestRef.current += 1
      advanceStaffSessionGeneration()
      if (!preserveConfirmedActor) setConfirmedStaffActor(null)
      if (departingStaffId) tabStaffIdRef.current = departingStaffId
      staffRef.current = null
      setStaff(null)
      setStatus('anonymous')
      if (removeSessionOwner)
        removeDraftSessionOwner(localStorage, departingStaffId)
      if (departingStaffId)
        purgeDraftsBestEffort(localStorage, departingStaffId)
    },
    [],
  )

  const setAuthenticatedStaff = useCallback((current: CurrentStaff) => {
    const previousStaffId =
      staffRef.current?.id ??
      tabStaffIdRef.current ??
      readRememberedStaffId(localStorage)
    advanceStaffSessionGeneration()
    setConfirmedStaffActor(current.id)
    setDraftSessionOwner(localStorage, current.id)
    sweepDraftsBestEffort(localStorage, current.id)
    if (previousStaffId && previousStaffId !== current.id) {
      purgeDraftsBestEffort(localStorage, previousStaffId)
    }
    staffRef.current = current
    tabStaffIdRef.current = current.id
    setStaff(current)
    setStatus('authenticated')
  }, [])

  const clearAuthenticatedStaff = useCallback(() => {
    const departingStaffId =
      staffRef.current?.id ??
      tabStaffIdRef.current ??
      readRememberedStaffId(localStorage)
    clearStaffState(departingStaffId, true)
  }, [clearStaffState])

  const failClosedAfterConflictingSignIn = useCallback(
    async (conflictingStaffId: string | null) => {
      let logoutFailed = false
      let logoutFailure: unknown
      try {
        await logoutStaff({ invalidateSessionOn401: false })
      } catch (error) {
        logoutFailed = true
        logoutFailure = error
      } finally {
        const tabStaffId = tabStaffIdRef.current
        clearStaffState(tabStaffId)
        // Compare-and-remove preserves an account that supersedes the conflict
        // while logout is in flight. A matching removal notifies other tabs.
        removeDraftSessionOwner(localStorage, conflictingStaffId)
        if (conflictingStaffId && conflictingStaffId !== tabStaffId) {
          purgeDraftsBestEffort(localStorage, conflictingStaffId)
        }
      }
      if (logoutFailed) {
        throw new Error('Failed to terminate conflicting staff session', {
          cause: logoutFailure,
        })
      }
    },
    [clearStaffState],
  )

  const refresh = useCallback(async () => {
    advanceStaffSessionGeneration()
    const requestId = ++sessionRequestRef.current
    const ownerSignalVersion = externalOwnerSignalRef.current.version
    setStatus('loading')
    try {
      const current = await getCurrentStaff()
      if (requestId !== sessionRequestRef.current) return
      const latestOwnerSignal = externalOwnerSignalRef.current
      if (
        latestOwnerSignal.version !== ownerSignalVersion &&
        latestOwnerSignal.staffId !== current.id
      ) {
        clearStaffState(
          latestOwnerSignal.staffId === tabStaffIdRef.current
            ? null
            : tabStaffIdRef.current,
        )
        return
      }
      setAuthenticatedStaff(current)
    } catch (error) {
      if (requestId !== sessionRequestRef.current) return
      if (error instanceof ApiError && error.status === 401) {
        const latestOwnerSignal = externalOwnerSignalRef.current
        if (
          latestOwnerSignal.version !== ownerSignalVersion &&
          latestOwnerSignal.staffId !== null
        ) {
          clearStaffState(
            latestOwnerSignal.staffId === tabStaffIdRef.current
              ? null
              : tabStaffIdRef.current,
          )
        } else {
          clearAuthenticatedStaff()
        }
      } else {
        setStaff(null)
        setStatus('error')
      }
    }
  }, [clearAuthenticatedStaff, clearStaffState, setAuthenticatedStaff])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    const invalidateFromApi = (event: Event) => {
      if (!isCurrentStaffSessionInvalidation(event)) return
      clearAuthenticatedStaff()
    }
    const rejectMismatchedActorFromApi = (event: Event) => {
      if (!isCurrentStaffSessionActorMismatch(event)) return
      clearStaffState(
        staffRef.current?.id ?? tabStaffIdRef.current,
        false,
        true,
      )
    }
    const invalidateFromOtherTab = (event: StorageEvent) => {
      if (event.key !== STAFF_DRAFT_SESSION_OWNER_KEY) return
      externalOwnerSignalRef.current = {
        version: externalOwnerSignalRef.current.version + 1,
        staffId: event.newValue,
      }
      if (event.newValue === staffRef.current?.id) return
      if (staffRef.current === null && event.newValue !== null) {
        if (tabStaffIdRef.current === null && event.oldValue) {
          tabStaffIdRef.current = event.oldValue
        }
        advanceStaffSessionGeneration()
        return
      }
      clearStaffState(
        staffRef.current?.id ?? null,
        false,
        event.newValue !== null,
      )
    }
    sessionEventTarget.addEventListener(
      STAFF_SESSION_INVALID_EVENT,
      invalidateFromApi,
    )
    sessionEventTarget.addEventListener(
      STAFF_SESSION_ACTOR_MISMATCH_EVENT,
      rejectMismatchedActorFromApi,
    )
    window.addEventListener('storage', invalidateFromOtherTab)
    return () => {
      sessionEventTarget.removeEventListener(
        STAFF_SESSION_INVALID_EVENT,
        invalidateFromApi,
      )
      sessionEventTarget.removeEventListener(
        STAFF_SESSION_ACTOR_MISMATCH_EVENT,
        rejectMismatchedActorFromApi,
      )
      window.removeEventListener('storage', invalidateFromOtherTab)
    }
  }, [clearAuthenticatedStaff, clearStaffState, sessionEventTarget])

  const signIn = useCallback(
    async (email: string, password: string) => {
      const departingStaffId =
        staffRef.current?.id ??
        tabStaffIdRef.current ??
        readRememberedStaffId(localStorage)
      advanceStaffSessionGeneration()
      const requestId = ++sessionRequestRef.current
      const ownerSignalVersion = externalOwnerSignalRef.current.version
      const compensateSignIn = () => {
        const latestOwnerSignal = externalOwnerSignalRef.current
        const currentOwnerId =
          staffRef.current?.id ??
          readRememberedStaffId(localStorage) ??
          departingStaffId
        const conflictingStaffId =
          latestOwnerSignal.version !== ownerSignalVersion
            ? latestOwnerSignal.staffId
            : currentOwnerId
        return failClosedAfterConflictingSignIn(conflictingStaffId)
      }
      let loginMutationAttempted = false
      try {
        await loginStaff(email, password, () => {
          loginMutationAttempted = true
        })
      } catch (error) {
        const isDefiniteRejection =
          error instanceof ApiError && error.status >= 400 && error.status < 500
        if (loginMutationAttempted && !isDefiniteRejection) {
          await compensateSignIn()
        }
        throw error
      }
      if (requestId !== sessionRequestRef.current) {
        await compensateSignIn()
        throw new Error('Staff session changed while signing in')
      }
      let current: CurrentStaff
      try {
        current = await getCurrentStaff({
          invalidateSessionOn401: false,
          omitExpectedStaffActor: true,
        })
      } catch (error) {
        await compensateSignIn()
        throw error
      }
      if (requestId !== sessionRequestRef.current) {
        await compensateSignIn()
        throw new Error('Staff session changed while signing in')
      }
      const latestOwnerSignal = externalOwnerSignalRef.current
      if (
        latestOwnerSignal.version !== ownerSignalVersion &&
        latestOwnerSignal.staffId !== current.id
      ) {
        await compensateSignIn()
        throw new Error('Staff session changed while signing in')
      }
      setAuthenticatedStaff(current)
      return current
    },
    [failClosedAfterConflictingSignIn, setAuthenticatedStaff],
  )

  const signOut = useCallback(async () => {
    const departingStaffId =
      staffRef.current?.id ??
      tabStaffIdRef.current ??
      readRememberedStaffId(localStorage)
    const requestId = ++sessionRequestRef.current
    try {
      await logoutStaff({ invalidateSessionOn401: false })
    } finally {
      if (requestId === sessionRequestRef.current) {
        clearStaffState(departingStaffId, true)
      } else {
        removeDraftSessionOwner(localStorage, departingStaffId)
        if (departingStaffId) {
          purgeDraftsBestEffort(localStorage, departingStaffId)
        }
      }
    }
  }, [clearStaffState])

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

function readRememberedStaffId(storage: Storage) {
  let staffId: string | null
  try {
    staffId = storage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)
  } catch {
    return null
  }
  if (isCanonicalStaffId(staffId)) {
    return staffId
  }
  if (staffId !== null) removeDraftSessionOwner(storage)
  return null
}

function isCanonicalStaffId(value: string | null): value is string {
  return (
    value !== null &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
}

function setDraftSessionOwner(storage: Storage, staffId: string) {
  try {
    storage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffId)
  } catch {
    // Browser storage is recovery-only; authentication state must not depend on it.
  }
}

function removeDraftSessionOwner(
  storage: Storage,
  expectedStaffId?: string | null,
) {
  try {
    if (
      expectedStaffId !== undefined &&
      storage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY) !== expectedStaffId
    ) {
      return
    }
    storage.removeItem(STAFF_DRAFT_SESSION_OWNER_KEY)
  } catch {
    // Session state is already fail-closed above even when storage is unavailable.
  }
}

function purgeDraftsBestEffort(storage: Storage, staffId: string) {
  try {
    purgeStaffTicketDrafts(storage, staffId)
  } catch {
    // An unavailable storage area cannot keep the protected staff UI authenticated.
  }
}

function sweepDraftsBestEffort(storage: Storage, staffId: string) {
  try {
    sweepStaffTicketDrafts(storage, staffId)
  } catch {
    // Draft recovery is best-effort; authentication state remains authoritative.
  }
}
