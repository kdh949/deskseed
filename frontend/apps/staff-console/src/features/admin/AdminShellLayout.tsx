import { canUseAgentWorkspace } from '../staff-auth/staffNavigation'
import { AdminShell } from './AdminShell'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AdminShellLayout() {
  const session = useStaffSession()
  if (!session.staff) return null

  return (
    <AdminShell
      displayName={session.staff.displayName}
      canOpenWorkspace={canUseAgentWorkspace(session.staff)}
      onSignOut={() => void session.signOut()}
    />
  )
}
