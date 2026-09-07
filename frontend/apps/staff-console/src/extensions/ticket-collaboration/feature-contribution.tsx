import type { FeatureContributionModule } from '../../extension-host/types'
import { TicketCollaborationActions } from './TicketCollaborationActions'
export const contribution: FeatureContributionModule['contribution'] = {
  id: 'ticket-collaboration.commands',
  kind: 'workspace-slot',
  slot: 'ticket-workspace.context',
  order: 20,
  requiredRoles: ['ADMIN', 'AGENT'],
  render: ({ ticketNumber }) => (
    <TicketCollaborationActions
      key={ticketNumber}
      ticketNumber={Number(ticketNumber)}
    />
  ),
}
