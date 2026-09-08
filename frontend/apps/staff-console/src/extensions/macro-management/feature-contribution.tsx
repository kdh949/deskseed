import type { FeatureContributionModule } from '../../extension-host/types'
import { MacroManagementPage } from './MacroManagementPage'
export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'macros.personal',
    kind: 'route',
    surface: 'agent',
    path: 'personal-macros',
    title: '내 매크로',
    order: 30,
    requiredRoles: ['ADMIN', 'AGENT'],
    element: <MacroManagementPage scope="PERSONAL" />,
  },
  {
    id: 'macros.personal-navigation',
    kind: 'shell-navigation',
    surface: 'agent',
    label: '내 매크로',
    to: '/agent/personal-macros',
    order: 30,
    requiredRoles: ['ADMIN', 'AGENT'],
  },
  {
    id: 'macros.shared',
    kind: 'route',
    surface: 'admin',
    path: 'shared-macros',
    title: '공유 매크로',
    order: 30,
    requiredRoles: ['ADMIN'],
    element: <MacroManagementPage scope="SHARED" />,
  },
]
