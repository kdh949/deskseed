import type { FeatureContributionModule } from '../../extension-host/types'
import {
  ComposerPresenceStatus,
  TicketPresenceContext,
} from './DraftsPresenceContribution'
import './draftsPresence.css'

export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'collaboration.presence-context',
    kind: 'workspace-slot',
    order: 10,
    requiredRoles: ['ADMIN', 'AGENT'],
    slot: 'ticket-workspace.context',
    render: ({ ticketNumber }) => (
      <TicketPresenceContext ticketNumber={Number(ticketNumber)} />
    ),
  },
  {
    id: 'collaboration.presence-composer-status',
    kind: 'workspace-slot',
    order: 10,
    requiredRoles: ['ADMIN', 'AGENT'],
    slot: 'ticket-composer.status',
    render: ({ composerMode, ticketNumber }) =>
      composerMode ? (
        <ComposerPresenceStatus
          composerMode={composerMode}
          ticketNumber={Number(ticketNumber)}
        />
      ) : (
        <span />
      ),
  },
]
