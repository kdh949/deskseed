import { useCallback, useState } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'

/** Local editor/route exit orchestration; the page owns its confirmation UI. */
export function useAdminDraftExit(dirty: boolean, busy: boolean) {
  const [pending, setPending] = useState<(() => void) | null>(null)
  const blocker = useBlocker(dirty || busy)
  useBeforeUnload(
    useCallback(
      (event: BeforeUnloadEvent) => {
        if (!dirty && !busy) return
        event.preventDefault()
        event.returnValue = ''
      },
      [dirty, busy],
    ),
  )
  return {
    open: pending !== null || blocker.state === 'blocked',
    request: (action: () => void) => {
      if (busy) return
      if (dirty) setPending(() => action)
      else action()
    },
    cancel: () => {
      setPending(null)
      if (blocker.state === 'blocked') blocker.reset()
    },
    discard: () => {
      if (busy) return
      setPending(null)
      if (blocker.state === 'blocked') blocker.proceed()
      else pending?.()
    },
  }
}
