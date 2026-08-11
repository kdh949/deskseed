import { Navigate, useParams } from 'react-router'
import { AgentHomeFixture } from './AgentHomeFixture'
import { AdminFixture } from './AdminFixture'
import { PublicDetailFixture, PublicFormFixture } from './PublicFixtures'
import { StateGalleryFixture } from './StateGalleryFixture'
import { ViewQueueFixture } from './ViewQueueFixture'
import { WorkspaceFixture } from './WorkspaceFixture'
import './frontend-system-fixtures.css'

export function FrontendSystemFixturePage() {
  const { fixtureName } = useParams()
  switch (fixtureName) {
    case 'agent-home':
      return <AgentHomeFixture />
    case 'view-queue':
      return <ViewQueueFixture />
    case 'workspace':
      return <WorkspaceFixture />
    case 'workspace-internal':
      return <WorkspaceFixture mode="INTERNAL" />
    case 'workspace-conflict':
      return <WorkspaceFixture conflict />
    case 'admin':
      return <AdminFixture />
    case 'public-form':
      return <PublicFormFixture />
    case 'public-detail':
      return <PublicDetailFixture />
    case 'states':
      return <StateGalleryFixture />
    default:
      return <Navigate to="/__fixtures__/frontend-system/agent-home" replace />
  }
}
