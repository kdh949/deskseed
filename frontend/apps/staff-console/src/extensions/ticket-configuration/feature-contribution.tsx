import type { FeatureContributionModule } from '../../extension-host/types'
import { AdminTicketFieldsPage } from './AdminTicketFieldsPage'
import { AdminTicketFormsPage } from './AdminTicketFormsPage'

export const contribution: FeatureContributionModule['contribution'] = [
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
