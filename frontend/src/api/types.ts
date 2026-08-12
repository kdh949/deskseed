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
  currentVersion?: number
  conflictingFields?: TicketFieldName[]
}

export interface FieldError {
  field: string
  message: string
  code?: string
}

export type StaffRole = 'ADMIN' | 'AGENT' | 'SECURITY_AUDITOR'
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

export interface AgentTicketSearchFilters {
  status?: AgentTicketStatus
  priority?: TicketPriority
  groupId?: string
  assigneeId?: string
}

export interface AgentTicketSearchInput {
  query: string
  filters: AgentTicketSearchFilters
  sort: 'updatedAt:desc,ticketNumber:desc'
  limit: number
}

export interface AgentTicketSearchPage {
  searchEventId: string
  searchInteractionId: string
  items: AgentTicketSummary[]
  resultCount: number
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
  assignmentOptions: TicketAssignmentOptions
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

export type TicketFieldName = 'status' | 'priority' | 'groupId' | 'assigneeId'

export interface TicketAssignmentStaffOption {
  id: string
  displayName: string
}

export interface TicketAssignmentGroupOption {
  id: string
  name: string
  members: TicketAssignmentStaffOption[]
}

export interface TicketAssignmentOptions {
  groups: TicketAssignmentGroupOption[]
}

export interface TicketCommentDraft {
  visibility: TicketVisibility
  body: string
}

export interface UpdateTicketCommand {
  expectedVersion: number
  changedFields: TicketFieldName[]
  status?: AgentTicketStatus
  priority?: TicketPriority
  groupId?: string | null
  assigneeId?: string | null
  comment: TicketCommentDraft | null
  clientCommandId: string
}

export interface TransferTicketCommand {
  expectedVersion: number
  groupId: string
  assigneeId: string | null
  reason: string | null
  clientCommandId: string
}

export interface CreateChildTicketCommand {
  expectedVersion: number
  subject: string
  body: string
  groupId: string
  assigneeId: string | null
  priority: TicketPriority
  clientCommandId: string
}

export interface TicketCommandWarning {
  code: string
  message: string
  count: number
  relatedTicketNumbers: number[]
}

export interface TicketCommandResult {
  ticketNumber: number
  version: number
  auditId: string
  warnings: TicketCommandWarning[]
}

export interface CreateChildTicketResult {
  parentTicketNumber: number
  parentVersion: number
  childTicketNumber: number
  parentAuditId: string
  childAuditId: string
}

export type AuditLedgerType =
  'TICKET_CHANGE' | 'ACCESS_SEARCH' | 'ADMIN_SECURITY'
export type AuditOutcome = 'SUCCEEDED' | 'DENIED' | 'FAILED'

export interface AuditActivityFilters {
  from?: string
  to?: string
  ledger?: AuditLedgerType
  action?: string
  actorType?: ActorSummary['type']
  actorId?: string
  ticketNumber?: number
  groupId?: string
  field?: string
  source?: string
  outcome?: AuditOutcome
  requestId?: string
  correlationId?: string
  searchFingerprint?: string
  limit?: number
}

export interface AuditActivity {
  id: string
  ledger: AuditLedgerType
  action: string
  actor: ActorSummary
  occurredAt: string
  ticketNumber: number | null
  groupId: string | null
  field: string | null
  resourceType: string | null
  resourceId: string | null
  summary: string
  source: string
  outcome: AuditOutcome
  requestId: string | null
  correlationId: string | null
  protectedContentAvailable: boolean
  searchFingerprint: string | null
}

export interface AuditProjectionStatus {
  state: 'CURRENT' | 'DEGRADED' | 'REBUILDING'
  projectedCount: number
  lastRebuiltAt: string | null
}

export interface AuditActivityPage {
  items: AuditActivity[]
  nextCursor: string | null
  snapshotAt: string
  projection: AuditProjectionStatus
}

export interface AuditSearchContext {
  queryRedacted: string
  queryFingerprint: string
  filters: Record<string, string>
  sort: string | null
  resultCount: number
  originSearchActivityId: string | null
  openedActivityCount: number
  openedActivitiesTruncated: boolean
  openedActivities: Array<{
    activityId: string
    ticketNumber: number
    occurredAt: string
  }>
}

export interface AuditActivityDetail extends AuditActivity {
  canonicalEventId: string
  canonicalParentId: string | null
  fieldChange: {
    field: string
    before: unknown
    after: unknown
  } | null
  interactionId: string | null
  sessionFingerprint: string | null
  authType: string | null
  ipAddress: string | null
  userAgent: string | null
  search: AuditSearchContext | null
  metadata: Record<string, unknown>
}

export interface SearchQueryRevealResult {
  activityId: string
  state: 'AVAILABLE' | 'RETENTION_EXPIRED' | 'KEY_UNAVAILABLE'
  rawQuery: string | null
  keyVersion: string | null
  revealedAt: string | null
}

export interface CreateAuditExportInput {
  format: 'CSV' | 'JSONL'
  filters: AuditActivityFilters
  fields: string[]
  reason: string
}

export interface AuditExportJob {
  id: string
  status: 'REQUESTED'
  createdAt: string
  format: 'CSV' | 'JSONL'
  fields: string[]
  artifact: {
    state: 'NOT_CREATED'
    generationAvailable: false
  }
}

export interface AuditProjectionRebuildResult {
  ticketChangeCount: number
  accessSearchCount: number
  adminSecurityCount: number
  totalCount: number
  completedAt: string
  projection: AuditProjectionStatus
}
