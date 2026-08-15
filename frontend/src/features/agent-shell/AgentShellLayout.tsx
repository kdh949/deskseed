import { AgentShell } from '../../design-system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShellLayout() {
  const session = useStaffSession()
  const staff = session.staff
  const displayName = staff?.displayName

  if (!displayName) return null

  const canCreateTicket =
    (staff.role === 'AGENT' || staff.role === 'ADMIN') &&
    staff.capabilities.includes('AGENT_WORKSPACE')

  return (
    <AgentShell
      canCreateTicket={canCreateTicket}
      displayName={displayName}
      onSignOut={session.signOut}
    />
  )
}
