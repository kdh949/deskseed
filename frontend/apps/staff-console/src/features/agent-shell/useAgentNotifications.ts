import { useEffect, useRef, useState } from 'react'
import {
  listAgentNotifications,
  markAgentNotificationRead,
} from '../../api/client'
type Snapshot = {
  owner: string | null
  items: Awaited<ReturnType<typeof listAgentNotifications>>['items']
  unreadCount: number
  state: 'idle' | 'loading' | 'empty' | 'error'
}
export function useAgentNotifications(owner: string | null) {
  const [snapshot, setSnapshot] = useState<Snapshot>({
    owner: null,
    items: [],
    unreadCount: 0,
    state: 'loading',
  })
  const reload = useRef<() => Promise<void>>(async () => {})
  useEffect(() => {
    let disposed = false
    let pending = false
    let refreshAgain = false
    async function load() {
      if (!owner || disposed) return
      if (pending) {
        refreshAgain = true
        return
      }
      pending = true
      try {
        const page = await listAgentNotifications()
        if (!disposed)
          setSnapshot({
            owner,
            items: page.items,
            unreadCount: page.unreadCount,
            state: page.items.length ? 'idle' : 'empty',
          })
      } catch {
        if (!disposed)
          setSnapshot((current) => ({
            ...(current.owner === owner
              ? current
              : { owner, items: [], unreadCount: 0 }),
            state: 'error',
          }))
      } finally {
        pending = false
        if (refreshAgain && !disposed) {
          refreshAgain = false
          void load()
        }
      }
    }
    reload.current = load
    if (!owner)
      return () => {
        disposed = true
      }
    void load()
    let socket: WebSocket | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let retryDelay = 1000
    function reconnect() {
      if (disposed || reconnectTimer !== undefined) return
      reconnectTimer = setTimeout(() => {
        reconnectTimer = undefined
        connect()
      }, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 30000)
    }
    function connect() {
      if (disposed || typeof WebSocket === 'undefined') return
      try {
        const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        const current = new WebSocket(
          `${scheme}//${window.location.host}/ws/agent/collaboration`,
        )
        socket = current
        current.onopen = () => {
          if (disposed) return
          retryDelay = 1000
          void load()
        }
        current.onmessage = (event) => {
          if (
            disposed ||
            typeof event.data !== 'string' ||
            event.data.length > 4096
          )
            return
          try {
            const message = JSON.parse(event.data) as Record<string, unknown>
            if (
              message.version === 1 &&
              message.type === 'notification.created'
            )
              void load()
          } catch {
            /* REST remains authoritative for malformed hints. */
          }
        }
        current.onclose = () => {
          reconnect()
          void load()
        }
        current.onerror = () => {
          current.close()
          reconnect()
        }
      } catch {
        reconnect()
      }
    }
    connect()
    const refreshVisible = () => {
      if (document.visibilityState !== 'hidden') void load()
    }
    const polling = setInterval(refreshVisible, 30000)
    window.addEventListener('focus', refreshVisible)
    window.addEventListener('online', refreshVisible)
    document.addEventListener('visibilitychange', refreshVisible)
    return () => {
      disposed = true
      clearInterval(polling)
      clearTimeout(reconnectTimer)
      if (socket) {
        socket.onclose = null
        socket.onerror = null
        socket.onmessage = null
        socket.onopen = null
        socket.close()
      }
      window.removeEventListener('focus', refreshVisible)
      window.removeEventListener('online', refreshVisible)
      document.removeEventListener('visibilitychange', refreshVisible)
    }
  }, [owner])
  const current =
    snapshot.owner === owner
      ? snapshot
      : {
          owner,
          items: [],
          unreadCount: 0,
          state: owner ? ('loading' as const) : ('empty' as const),
        }
  return {
    ...current,
    load: () => void reload.current(),
    markRead: async (id: string) => {
      if (!owner) return
      await markAgentNotificationRead(id)
      await reload.current()
    },
  }
}
