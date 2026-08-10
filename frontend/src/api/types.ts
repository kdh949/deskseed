export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'

export interface SubmitRequestInput {
  name: string
  email: string
  subject: string
  message: string
}

export interface SubmittedRequest {
  ticketNumber: number
  status: TicketStatus
  accessToken: string
  createdAt: string
}

export interface PublicComment {
  id: string
  authorDisplayName: string
  body: string
  createdAt: string
}

export interface PublicRequest {
  ticketNumber: number
  subject: string
  status: TicketStatus
  createdAt: string
  updatedAt: string
  comments: PublicComment[]
}

export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  requestId?: string
  code?: string
  fieldErrors?: FieldError[]
}

export interface FieldError {
  field: string
  message: string
  code?: string
}

export type StaffRole = 'ADMIN' | 'AGENT'
export type StaffStatus = 'ACTIVE' | 'DISABLED'
export type OrganizationStatus = 'ACTIVE' | 'DISABLED'

export interface CurrentStaff {
  id: string
  email: string
  displayName: string
  role: StaffRole
  capabilities: string[]
}

export interface GroupReference {
  id: string
  name: string
}

export interface StaffAccount {
  id: string
  email: string
  displayName: string
  role: StaffRole
  status: StaffStatus
  memberships: GroupReference[]
  lastLoginAt: string | null
}

export interface SupportGroup {
  id: string
  name: string
  status: OrganizationStatus
  memberCount: number
}

export interface GroupMembership {
  groupId: string
  staffId: string
  staffDisplayName: string
  role: StaffRole
}

export interface CreateStaffInput {
  email: string
  displayName: string
  role: StaffRole
  password: string
}
