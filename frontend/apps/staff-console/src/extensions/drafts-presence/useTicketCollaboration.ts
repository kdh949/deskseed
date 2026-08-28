import { useEffect, useState } from 'react'
import {
  releaseTicketCollaboration,
  retainTicketCollaboration,
  type CollaborationView,
} from './collaborationRealtime'

const initialView: CollaborationView = {
  connection: 'connecting',
  members: [],
  ticketUpdate: null,
}

export function useTicketCollaboration({
  composerMode,
  ticketNumber,
}: {
  composerMode?: 'public' | 'internal'
  ticketNumber: number
}) {
  const [view, setView] = useState<CollaborationView>(initialView)

  useEffect(() => {
    const client = retainTicketCollaboration(ticketNumber)
    const stopObserving = client.observe(setView)
    return () => {
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

  return view
}
