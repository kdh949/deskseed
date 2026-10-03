import type { FeatureContributionModule } from '../../extension-host/types'
import { AdminAutomationsPage } from './AdminAutomationsPage'
import { AdminTriggersPage } from './AdminTriggersPage'
export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'rule-workflow.automations',
    kind: 'route',
    surface: 'admin',
    path: 'automations',
    title: '시간 자동화',
    order: 51,
    requiredRoles: ['ADMIN'],
    element: <AdminAutomationsPage />,
  },
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
