import { AgentShell } from '../../design-system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { frontendExtensions } from '../../extension-host/catalog'

export function AgentShellLayout() {
  const session = useStaffSession()
  const staff = session.staff
  const displayName = staff?.displayName

  if (!displayName) return null

  const canCreateTicket =
    (staff.role === 'AGENT' || staff.role === 'ADMIN') &&
    staff.capabilities.includes('AGENT_WORKSPACE')
  const extensionNavigationItems = frontendExtensions.agentNavigationFor({
    role: staff.role,
    capabilities: staff.capabilities,
  })

  return (
    <AgentShell
      canCreateTicket={canCreateTicket}
      displayName={displayName}
      extensionNavigationItems={extensionNavigationItems}
      onSignOut={session.signOut}
    />
  )
}
