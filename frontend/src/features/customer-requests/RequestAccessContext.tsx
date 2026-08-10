import {
  createContext,
  type PropsWithChildren,
  useContext,
  useMemo,
  useRef,
  useState,
} from 'react'

interface RequestAccessValue {
  getAccessToken(ticketNumber: number): string | undefined
  getGrantRevision(ticketNumber: number): number | undefined
  setAccessToken(ticketNumber: number, token: string): number
  clearAccessToken(ticketNumber: number): void
}

interface RequestAccessGrant {
  token: string
  revision: number
}

const RequestAccessContext = createContext<RequestAccessValue | null>(null)

export function RequestAccessProvider({ children }: PropsWithChildren) {
  const [grants, setGrants] = useState<ReadonlyMap<number, RequestAccessGrant>>(
    () => new Map(),
  )
  const nextRevision = useRef(0)

  const value = useMemo<RequestAccessValue>(
    () => ({
      getAccessToken: (ticketNumber) => grants.get(ticketNumber)?.token,
      getGrantRevision: (ticketNumber) => grants.get(ticketNumber)?.revision,
      setAccessToken: (ticketNumber, token) => {
        const revision = ++nextRevision.current
        setGrants((current) => {
          const next = new Map(current)
          next.set(ticketNumber, { token, revision })
          return next
        })
        return revision
      },
      clearAccessToken: (ticketNumber) => {
        setGrants((current) => {
          const next = new Map(current)
          next.delete(ticketNumber)
          return next
        })
      },
    }),
    [grants],
  )

  return (
    <RequestAccessContext.Provider value={value}>
      {children}
    </RequestAccessContext.Provider>
  )
}

export function useRequestAccess(): RequestAccessValue {
  const value = useContext(RequestAccessContext)
  if (!value) {
    throw new Error(
      'useRequestAccess must be used within RequestAccessProvider',
    )
  }
  return value
}
