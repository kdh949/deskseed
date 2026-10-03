import { useCallback, useEffect, useRef, useState } from 'react'
import {
  releaseTicketCollaboration,
  retainTicketCollaboration,
  type CollaborationView,
  type TicketCollaborationRealtime,
} from './collaborationRealtime'

const initialView: CollaborationView = {
  connection: 'connecting',
  members: [],
  ticketUpdate: null,
  lastConfirmedAt: null,
  reconnectAt: null,
  snapshotReceived: false,
}

export function useTicketCollaboration({
  composerMode,
  ticketNumber,
}: {
  composerMode?: 'public' | 'internal'
  ticketNumber: number
}) {
  const clientRef = useRef<TicketCollaborationRealtime | null>(null)
  const retry = useCallback(() => clientRef.current?.retry(), [])
  const [view, setView] = useState<CollaborationView>(initialView)

  useEffect(() => {
    const client = retainTicketCollaboration(ticketNumber)
    clientRef.current = client
    const stopObserving = client.observe(setView)
    return () => {
      clientRef.current = null
      stopObserving()
      releaseTicketCollaboration(ticketNumber)
    }
  }, [ticketNumber])

  useEffect(() => {
    if (composerMode === undefined) return
    const client = retainTicketCollaboration(ticketNumber)
    client.reportComposerMode(composerMode)
    return () => {
      client.reportComposerMode(null)
      releaseTicketCollaboration(ticketNumber)
    }
  }, [composerMode, ticketNumber])

  return { ...view, retry }
}
