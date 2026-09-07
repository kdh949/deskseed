import type { FeatureContributionModule } from '../../extension-host/types'
import { AgentTicketConfigurationPanel } from './AgentTicketConfigurationPanel'
import { AdminTicketFieldsPage } from './AdminTicketFieldsPage'
import { AdminTicketLabelsPage } from './AdminTicketLabelsPage'
import { AdminTicketFormsPage } from './AdminTicketFormsPage'

export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'ticket-configuration.tags',
    kind: 'route',
    surface: 'admin',
    path: 'ticket-tags',
    title: '티켓 태그',
    order: 22,
    requiredRoles: ['ADMIN'],
    element: <AdminTicketLabelsPage kind="tags" />,
  },
  {
    id: 'ticket-configuration.statuses',
    kind: 'route',
    surface: 'admin',
    path: 'ticket-statuses',
    title: '업무 상태',
    order: 23,
    requiredRoles: ['ADMIN'],
    element: <AdminTicketLabelsPage kind="statuses" />,
  },
  {
    id: 'ticket-configuration.editor',
    kind: 'workspace-slot',
    slot: 'ticket-workspace.context',
    order: 15,
    requiredRoles: ['ADMIN', 'AGENT'],
    render: ({ ticketNumber }) => (
      <AgentTicketConfigurationPanel
        key={ticketNumber}
        ticketNumber={Number(ticketNumber)}
      />
    ),
  },
  {
    id: 'ticket-configuration.forms',
    kind: 'route',
    surface: 'admin',
    path: 'ticket-forms',
    title: '티켓 폼',
    order: 21,
    requiredRoles: ['ADMIN'],
    element: <AdminTicketFormsPage />,
  },
  {
    id: 'ticket-configuration.fields',
    kind: 'route',
    surface: 'admin',
    path: 'ticket-fields',
    title: '티켓 필드',
    order: 20,
    requiredRoles: ['ADMIN'],
    element: <AdminTicketFieldsPage />,
  },
]
