import { AgentShell } from '../../design-system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShellLayout() {
  const session = useStaffSession()
  return (
    <AgentShell
      displayName={session.staff?.displayName ?? '상담사'}
      onSignOut={session.signOut}
      role={session.staff?.role}
    />
  )
}
