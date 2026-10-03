import type { FeatureContributionModule } from '../../extension-host/types'
import { AdminKnowledgePage } from './AdminKnowledgePage'
import { AgentKnowledgeArticlePage } from './AgentKnowledgePanel'
export const contribution: FeatureContributionModule['contribution'] = [
  {
    id: 'knowledge.admin',
    kind: 'route',
    surface: 'admin',
    path: 'knowledge',
    title: '지식 문서',
    order: 40,
    requiredRoles: ['ADMIN'],
    element: <AdminKnowledgePage />,
  },
  {
    id: 'knowledge.agent-article',
    kind: 'route',
    surface: 'agent',
    path: 'knowledge/articles/:slug',
    title: '지식 문서',
    order: 40,
    requiredRoles: ['ADMIN', 'AGENT'],
    element: <AgentKnowledgeArticlePage />,
  },
]
