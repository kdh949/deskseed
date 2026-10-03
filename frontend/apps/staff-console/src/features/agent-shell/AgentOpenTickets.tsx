import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'

const storageKey = (staffId: string) => `deskseed:open-tickets:v1:${staffId}`

export function readOpenTickets(staffId: string): number[] {
  try {
    const value: unknown = JSON.parse(
      sessionStorage.getItem(storageKey(staffId)) ?? '[]',
    )
    return Array.isArray(value)
      ? [
          ...new Set(
            value.filter(
              (item): item is number =>
                typeof item === 'number' &&
                Number.isSafeInteger(item) &&
                item > 0,
            ),
          ),
        ]
      : []
  } catch {
    return []
  }
}

export function clearOpenTickets(staffId: string) {
  try {
    sessionStorage.removeItem(storageKey(staffId))
  } catch {
    /* Optional navigation state; no ticket or draft mutation. */
  }
}

type OpenTickets = {
  numbers: number[]
  remember: (number: number) => void
  forget: (number: number) => void
  pendingClose: number | null
  requestClose: (number: number | null) => void
  draftNumbers: ReadonlySet<number>
  setHasDraft: (number: number, hasDraft: boolean) => void
}
const Context = createContext<OpenTickets | null>(null)

export function AgentOpenTicketsProvider({
  staffId,
  children,
}: {
  staffId: string
  children: ReactNode
}) {
  const initial = useMemo(() => readOpenTickets(staffId), [staffId])
  const [owned, setOwned] = useState({ staffId, numbers: initial })
  const [pendingClose, requestClose] = useState<number | null>(null)
  const [draftState, setDraftState] = useState({
    staffId,
    numbers: new Set<number>(),
  })
  const draftNumbers = useMemo(
    () =>
      draftState.staffId === staffId ? draftState.numbers : new Set<number>(),
    [draftState, staffId],
  )
  const setHasDraft = useCallback(
    (number: number, hasDraft: boolean) => {
      setDraftState((current) => {
        const numbers =
          current.staffId === staffId ? current.numbers : new Set<number>()
        if (current.staffId === staffId && numbers.has(number) === hasDraft)
          return current
        const next = new Set(numbers)
        if (hasDraft) next.add(number)
        else next.delete(number)
        return { staffId, numbers: next }
      })
    },
    [staffId],
  )
  const numbers = owned.staffId === staffId ? owned.numbers : initial
  const remember = useCallback(
    (number: number) => {
      setOwned((current) => {
        const items = current.staffId === staffId ? current.numbers : initial
        return items.includes(number)
          ? current.staffId === staffId
            ? current
            : { staffId, numbers: items }
          : { staffId, numbers: [...items, number] }
      })
    },
    [staffId, initial],
  )
  const forget = useCallback(
    (number: number) => {
      setHasDraft(number, false)
      setOwned((current) => {
        const items = current.staffId === staffId ? current.numbers : initial
        return items.includes(number)
          ? { staffId, numbers: items.filter((item) => item !== number) }
          : current
      })
    },
    [staffId, initial, setHasDraft],
  )
  useEffect(() => {
    try {
      sessionStorage.setItem(storageKey(staffId), JSON.stringify(numbers))
    } catch {
      /* Storage can be disabled; in-memory navigation still works. */
    }
  }, [staffId, numbers])
  const value = useMemo(
    () => ({
      numbers,
      remember,
      forget,
      pendingClose,
      requestClose,
      draftNumbers,
      setHasDraft,
    }),
    [numbers, remember, forget, pendingClose, draftNumbers, setHasDraft],
  )
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export const useAgentOpenTickets = () => useContext(Context)

export function useRememberAgentTicket(
  number: number | null,
  loaded: boolean,
  denied: boolean,
) {
  const tickets = useAgentOpenTickets()
  const remember = tickets?.remember
  const forget = tickets?.forget
  useEffect(() => {
    if (number === null) return
    if (denied) forget?.(number)
    else if (loaded) remember?.(number)
  }, [number, loaded, denied, remember, forget])
}
