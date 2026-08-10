import {
  createContext,
  type PropsWithChildren,
  useContext,
  useMemo,
  useState,
} from 'react'

interface RequestAccessValue {
  getAccessToken(ticketNumber: number): string | undefined
  setAccessToken(ticketNumber: number, token: string): void
  clearAccessToken(ticketNumber: number): void
}

const RequestAccessContext = createContext<RequestAccessValue | null>(null)

export function RequestAccessProvider({ children }: PropsWithChildren) {
  const [tokens, setTokens] = useState<ReadonlyMap<number, string>>(
    () => new Map(),
  )

  const value = useMemo<RequestAccessValue>(
    () => ({
      getAccessToken: (ticketNumber) => tokens.get(ticketNumber),
      setAccessToken: (ticketNumber, token) => {
        setTokens((current) => {
          const next = new Map(current)
          next.set(ticketNumber, token)
          return next
        })
      },
      clearAccessToken: (ticketNumber) => {
        setTokens((current) => {
          const next = new Map(current)
          next.delete(ticketNumber)
          return next
        })
      },
    }),
    [tokens],
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
