export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'
export type AgentTicketStatus = TicketStatus | 'ON_HOLD' | 'CLOSED'
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type TicketVisibility = 'PUBLIC' | 'INTERNAL'

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

export interface SavedAgentView {
  key: string
  name: string
  scope: 'PERSONAL' | 'SHARED' | 'SYSTEM'
  categoryPath: string[]
  ticketCount: number | null
  readScope: 'ALL_TICKETS'
}

export interface ActorSummary {
  id: string | null
  type:
    | 'CUSTOMER'
    | 'STAFF'
    | 'INTEGRATION_CLIENT'
    | 'TRIGGER'
    | 'AUTOMATION'
    | 'SYSTEM'
  displayName: string
}

export interface TicketReference {
  id: string
  displayName: string
}

export interface AgentTicketSummary {
  ticketNumber: number
  subject: string
  status: AgentTicketStatus
  priority: TicketPriority
  requester: ActorSummary
  group: GroupReference | null
  assignee: TicketReference | null
  updatedAt: string
  version: number
  isChild: boolean
  openChildCount: number
  sla: null
}

export interface AgentTicketPage {
  items: AgentTicketSummary[]
  nextCursor: string | null
  totalApproximate: number | null
  sort: 'updatedAt:desc,ticketNumber:desc'
}

export interface AgentComment {
  id: string
  visibility: TicketVisibility
  actor: ActorSummary
  body: string
  createdAt: string
  source: string
  attachments: unknown[]
}

export interface TicketCustomerContext {
  id: string
  displayName: string
  email: string
}

export interface TicketHistoryItem {
  id: string
  eventType: string
  actor: ActorSummary
  occurredAt: string
}

export interface AgentTicketDetail {
  ticket: AgentTicketSummary
  comments: AgentComment[]
  capabilities: string[]
  context: {
    customer: TicketCustomerContext
    parent: AgentTicketSummary | null
    children: AgentTicketSummary[]
    externalReferences: unknown[]
  }
  history: TicketHistoryItem[]
  warnings: unknown[]
}

export interface AgentTicketFilters {
  status?: AgentTicketStatus
  priority?: TicketPriority
  groupId?: string
  assigneeId?: string
  cursor?: string
  limit?: number
}

export type AgentReadIntent = 'NAVIGATION' | 'BACKGROUND'
