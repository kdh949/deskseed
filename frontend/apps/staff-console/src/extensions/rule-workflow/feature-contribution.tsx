import type { FeatureContributionModule } from '../../extension-host/types'
import { AdminTriggersPage } from './AdminTriggersPage'
export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'rule-workflow.triggers',
    kind: 'route',
    surface: 'admin',
    path: 'triggers',
    title: '트리거',
    order: 50,
    requiredRoles: ['ADMIN'],
    element: <AdminTriggersPage />,
  },
]
