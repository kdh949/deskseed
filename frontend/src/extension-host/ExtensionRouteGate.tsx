import { useStaffSession } from '../features/staff-auth/StaffSessionContext'
import { allowsContribution } from './catalog'
import type { RouteContribution } from './types'

export function ExtensionRouteGate({
  contribution,
}: {
  contribution: RouteContribution
}) {
  const session = useStaffSession()
  const staff = session.staff
  const allowed =
    staff !== null &&
    allowsContribution(contribution, {
      role: staff.role,
      capabilities: staff.capabilities,
    })

  if (!allowed) {
    return (
      <main
        aria-labelledby="extension-route-denied-title"
        className="workspace-error-state"
      >
        <h1 id="extension-route-denied-title">이 기능에 접근할 수 없습니다.</h1>
        <p>현재 권한으로는 이 기능을 열 수 없습니다.</p>
      </main>
    )
  }

  return contribution.element
}
