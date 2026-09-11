import type { CurrentStaff } from '../../api/types'
export function canUseAgentWorkspace(staff: CurrentStaff | null) {
  return (
    !!staff &&
    ['ADMIN', 'AGENT'].includes(staff.role) &&
    staff.capabilities.includes('AGENT_WORKSPACE')
  )
}
export function canManageStaff(staff: CurrentStaff | null) {
  return staff?.role === 'ADMIN' && staff.capabilities.includes('ADMIN_MANAGE')
}
export function canReadAudit(staff: CurrentStaff | null) {
  return staff?.role === 'SECURITY_AUDITOR'
}
export function staffHome(staff: CurrentStaff) {
  if (canReadAudit(staff)) return '/agent/audit'
  if (canManageStaff(staff)) return '/admin/operations/mail'
  return '/agent/views/my-open'
}
export function staffDestination(staff: CurrentStaff, value: unknown) {
  if (typeof value !== 'string' || !/^\/[a-zA-Z0-9/_-]+$/.test(value))
    return staffHome(staff)
  if (
    canReadAudit(staff) &&
    (value === '/agent/audit' ||
      /^\/agent\/audit\/exports\/[a-zA-Z0-9-]+$/.test(value))
  )
    return value
  if (canManageStaff(staff) && value.startsWith('/admin/')) return value
  if (
    canUseAgentWorkspace(staff) &&
    (value === '/agent/search' ||
      /^\/agent\/(views|tickets)\/[a-zA-Z0-9-]+$/.test(value))
  )
    return value
  return staffHome(staff)
}
