export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'
export type AgentTicketStatus = TicketStatus | 'ON_HOLD' | 'CLOSED'
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type TicketVisibility = 'PUBLIC' | 'INTERNAL'
export type TicketDraftChannel = 'PUBLIC_REPLY' | 'INTERNAL_NOTE'

export const MAX_RICH_TEXT_NODES = 5_000

export type RichTextMarkV1 =
  | { type: 'bold' | 'italic' | 'underline' | 'code' }
  | { type: 'link'; attrs: { href: string } }

export interface RichTextNodeV1 {
  type:
    | 'paragraph'
    | 'heading'
    | 'bulletList'
    | 'orderedList'
    | 'listItem'
    | 'blockquote'
    | 'codeBlock'
    | 'attachmentImage'
    | 'text'
    | 'hardBreak'
  attrs?: {
    level?: 1 | 2 | 3
    textAlign?: 'left' | 'center' | 'right'
    attachmentId?: string
    alt?: string
  }
  content?: RichTextNodeV1[]
  text?: string
  marks?: RichTextMarkV1[]
}

export interface RichTextDocumentV1 {
  type: 'doc'
  content: RichTextNodeV1[]
}

export type CommentContent =
  | { format: 'PLAIN_TEXT'; text: string }
  | { format: 'RICH_TEXT_V1'; document: RichTextDocumentV1 }

export function plainTextDocument(text: string): RichTextDocumentV1 {
  return {
    type: 'doc',
    content: [
      {
        type: 'paragraph',
        ...(text ? { content: [{ type: 'text', text }] } : {}),
      },
    ],
  }
}

/** Owner-bound, recoverable composer state; it is not a ticket mutation or audit event. */
export interface TicketDraft {
  ticketNumber: number
  channel: TicketDraftChannel
  body: string
  content: CommentContent
  attachmentIds: string[]
  clientDeviceId: string
  baseTicketVersion: number
  draftVersion: number
  updatedAt: string
  expiresAt: string
}

export interface SaveTicketDraftInput {
  body?: string
  content?: CommentContent
  attachmentIds: string[]
  clientDeviceId: string
  baseTicketVersion: number
  expectedDraftVersion: number
}

export interface SubmitRequestInput {
  name: string
  email: string
  subject: string
  message: string
  privacyConsent?: boolean
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
  content: CommentContent
  createdAt: string
  attachments: TicketAttachment[]
}

/**
 * Metadata for a CLEAN attachment that is already linked to a visible comment.
 * The browser never receives an object key, scan result, checksum, or public URL.
 */
export interface TicketAttachment {
  id: string
  fileName: string
  sizeBytes: number
  contentType: string
}

/** A clean, unlinked private upload handle. It is valid only until `expiresAt`. */
export interface AttachmentUpload {
  id: string
  fileName: string
  sizeBytes: number
  contentType: string
  scanStatus: 'CLEAN'
  expiresAt: string
}

export interface AttachmentDownload {
  content: Blob
  contentType: string
  fileName: string | null
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
  currentAggregateVersion?: number
  conflictingFields?: TicketFieldName[]
}

export interface FieldError {
  field: string
  message: string
  code?: string
}

export type StaffRole = 'ADMIN' | 'AGENT' | 'SECURITY_AUDITOR'
export type StaffStatus = 'ACTIVE' | 'DISABLED'
export type GrantableAuditAuthority =
  'AUDIT_SEARCH_QUERY_REVEAL' | 'AUDIT_EXPORT' | 'AUDIT_PROJECTION_REBUILD'
export type OrganizationStatus = 'ACTIVE' | 'DISABLED'
export type CustomerAccessMode =
  'ANONYMOUS_ALLOWED' | 'REGISTRATION_OPTIONAL' | 'REGISTRATION_REQUIRED'

export interface CustomerAccessModeSetting {
  mode: CustomerAccessMode
  version: number
  updatedAt: string
}

export interface UpdateCustomerAccessModeInput {
  mode: CustomerAccessMode
  expectedVersion: number
}

export type OutboundMailIntentStatus =
  'QUEUED' | 'SENDING' | 'RETRY_WAIT' | 'SENT' | 'FAILED'

export type OutboundMailAttemptStatus =
  | 'IN_PROGRESS'
  | 'SUCCEEDED'
  | 'RETRYABLE_FAILED'
  | 'PERMANENT_FAILED'
  | 'ABANDONED'

export type OutboundMailTemplate =
  'CUSTOMER_MAGIC_LINK' | 'REQUEST_RECEIVED' | 'PUBLIC_AGENT_REPLY'

/**
 * Contract-validated administrative projection. `recipientMasked` is deliberately
 * constrained to a star-only local part before it can reach a rendered screen.
 */
export interface OutboundMailIntent {
  id: string
  template: OutboundMailTemplate
  templateVersion: number
  status: OutboundMailIntentStatus
  recipientMasked: string
  attemptCount: number
  maxAttempts: number
  retryCycle: number
  manualRetryCount: number
  nextAttemptAt: string | null
  leaseExpiresAt: string | null
  lastErrorCode: string | null
  queuedAt: string
  sentAt: string | null
  failedAt: string | null
  attempts: OutboundMailAttempt[]
}

export interface OutboundMailAttempt {
  attemptNumber: number
  retryCycle: number
  cycleAttemptNumber: number
  status: OutboundMailAttemptStatus
  failureClass: string | null
  failureCode: string | null
  startedAt: string
  finishedAt: string | null
  nextRetryAt: string | null
}

export interface OutboundMailIntentPage {
  items: OutboundMailIntent[]
  nextCursor: string | null
}

export interface OutboundMailOperationsSummary {
  deliveryEnabled: boolean
  schedulingEnabled: boolean
  transport: 'DISABLED' | 'SMTP' | 'FAKE'
  queuedCount: number
  sendingCount: number
  retryWaitCount: number
  failedCount: number
  sentCount: number
  oldestPendingAt: string | null
}

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
  auditAuthorities: GrantableAuditAuthority[]
  lastLoginAt: string | null
}

export interface AdminListPage<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
  totalPages: number
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

export type BusinessWeekday =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY'

export interface BusinessInterval {
  start: string
  end: string
}

export interface BusinessWeekdaySchedule {
  weekday: BusinessWeekday
  enabled: boolean
  intervals: BusinessInterval[]
}

export interface BusinessScheduleException {
  date: string
  mode: 'CLOSED' | 'OPEN'
  intervals: BusinessInterval[]
  label: string | null
}

export interface BusinessScheduleDefinition {
  name: string
  timeZone: string
  weekdays: BusinessWeekdaySchedule[]
  exceptions: BusinessScheduleException[]
}

export interface BusinessSchedule extends BusinessScheduleDefinition {
  id: string
  version: number
  activeVersion: number | null
  activeTimeZone: string | null
  aggregateVersion: number
  active: boolean
  createdAt: string
  createdBy: {
    actorType: 'STAFF' | 'SYSTEM'
    actorId: string | null
    displayName: string
  }
}

export interface BusinessSchedulePreviewInput {
  schedule: BusinessScheduleDefinition
  startAt: string
  endAt: string
  businessMinutes: number
}

export interface BusinessSchedulePreview {
  dueAt: string | null
  elapsedBusinessMinutes: number
  nextOpenAt: string | null
  nextCloseAt: string | null
  dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH'
}

export type FirstReplySlaState =
  | 'ACTIVE'
  | 'AT_RISK'
  | 'PAUSED'
  | 'ACHIEVED'
  | 'BREACHED'
  | 'CANCELLED'
  | 'NO_POLICY'

export interface FirstReplySlaBadge {
  metric: 'FIRST_REPLY'
  state: FirstReplySlaState
  dueAt: string | null
  targetMinutes: number | null
  policyVersion: number | null
  scheduleVersion: number | null
}

export type TicketChannel = 'WEB' | 'AGENT' | 'EMAIL' | 'CHAT' | 'API'

export interface FirstReplySlaPolicyDefinition {
  name: string
  position: number
  scheduleId: string
  conditions: {
    groupId: string | null
    channel: TicketChannel | null
  }
  targets: Record<TicketPriority, number | null>
  pauseStatuses: AgentTicketStatus[]
}

export interface FirstReplySlaPolicy extends FirstReplySlaPolicyDefinition {
  id: string
  scheduleVersion: number
  version: number
  activeVersion: number | null
  aggregateVersion: number
  active: boolean
  createdAt: string
  createdBy: {
    actorType: 'STAFF'
    actorId: string
    displayName: string
  }
}

export interface FirstReplySlaPreviewInput {
  candidatePolicyId: string | null
  candidate: FirstReplySlaPolicyDefinition | null
  ticket: {
    priority: TicketPriority
    groupId: string | null
    channel: TicketChannel
  }
  startAt: string
}

export interface FirstReplySlaPreview {
  matched: boolean
  dueAt: string | null
  targetMinutes: number | null
  policyId: string | null
  policyVersion: number | null
  scheduleId: string | null
  scheduleVersion: number | null
  dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH'
}

export interface FirstReplySlaAnalytics {
  metric: 'FIRST_REPLY'
  calculationVersion: string
  active: number
  paused: number
  achieved: number
  breached: number
  cancelled: number
  noPolicy: number
  achievedRateDenominator: number
  achievedRate: number | null
}

export interface CreateStaffInput {
  email: string
  displayName: string
  role: StaffRole
  password: string
}

export type IntegrationScope =
  | 'tickets:create'
  | 'tickets:read'
  | 'tickets:update'
  | 'tickets:comment:internal'
export type IntegrationClientStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED'
export type IntegrationCredentialStatus =
  'ACTIVE' | 'RETIRING' | 'EXPIRED' | 'REVOKED'
export type IntegrationTicketKind = 'CUSTOMER_REQUEST' | 'INTERNAL_TASK'
export type IntegrationTicketField =
  'status' | 'priority' | 'groupId' | 'assigneeId'

export interface IntegrationResourceConstraints {
  allowedGroupIds: string[] | null
  allowedTicketKinds: IntegrationTicketKind[] | null
  allowedFields: IntegrationTicketField[] | null
  ipAllowlist: string[] | null
}

export interface IntegrationCredential {
  id: string
  sequence: number
  publicKeyId: string
  status: IntegrationCredentialStatus
  expiresAt: string
  overlapExpiresAt: string | null
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
  lastUsedIp: string | null
}

export interface IntegrationClient {
  id: string
  name: string
  description: string
  status: IntegrationClientStatus
  scopes: IntegrationScope[]
  resourceConstraints: IntegrationResourceConstraints
  credentials: IntegrationCredential[]
  expiresAt: string | null
  lastUsedAt: string | null
  lastUsedIp: string | null
  createdAt: string
}

export interface CreateIntegrationClientInput {
  name: string
  description: string
  scopes: IntegrationScope[]
  resourceConstraints: {
    allowedGroupIds?: string[]
    allowedTicketKinds?: IntegrationTicketKind[]
    allowedFields?: IntegrationTicketField[]
    ipAllowlist?: string[]
  }
  expiresAt: string
}

export interface RotateIntegrationCredentialInput {
  expiresAt: string
  overlapSeconds: number
}

export interface IntegrationCredentialIssue {
  client: IntegrationClient
  credential: IntegrationCredential
  apiKey: string
}

export type ExternalSystemStatus = 'ACTIVE' | 'DISABLED'
export type ExternalObjectType =
  'ORDER' | 'PAYMENT' | 'REFUND' | 'USER' | 'STORE' | 'OPS_CASE' | 'CUSTOM'
export type ExternalReferenceLinkState =
  'AVAILABLE' | 'SYSTEM_DISABLED' | 'HOST_NOT_ALLOWED'
export type ExternalMetadataValue = string | number | boolean

export interface ExternalSystem {
  id: string
  systemKey: string
  displayName: string
  status: ExternalSystemStatus
  allowedHostnames: string[]
  createdAt: string
  updatedAt: string
  version: number
}

export interface ExternalReference {
  id: string
  system: ExternalSystem
  objectType: ExternalObjectType
  externalId: string
  displayLabel: string
  linkState: ExternalReferenceLinkState
  safeDeepLink: string | null
  metadata: Record<string, ExternalMetadataValue>
  metadataObservedAt: string
  createdBy: { actorId: string; displayName: string }
  createdAt: string
}

export interface ExternalReferenceContext {
  ticketVersion: number
  canManage: boolean
  availableSystems: ExternalSystem[]
  items: ExternalReference[]
}

export interface CreateExternalSystemInput {
  systemKey: string
  displayName: string
  allowedHostnames: string[]
}

export interface UpdateExternalSystemInput {
  displayName: string
  status: ExternalSystemStatus
  allowedHostnames: string[]
  expectedVersion: number
}

export interface CreateExternalReferenceInput {
  externalSystemId: string
  objectType: ExternalObjectType
  externalId: string
  displayLabel: string
  safeDeepLink: string
  metadata: Record<string, ExternalMetadataValue>
  metadataObservedAt: string
  expectedVersion: number
}

export interface ExternalReferenceCommandResult {
  ticketVersion: number
  reference: ExternalReference
}

export type SavedViewScope = 'PERSONAL' | 'SHARED' | 'SYSTEM'
export type SavedViewConditionField =
  | 'STATUS'
  | 'PRIORITY'
  | 'GROUP'
  | 'ASSIGNEE'
  | 'FIRST_REPLY_SLA_STATE'
  | 'TICKET_KIND'
  | 'UPDATED_AT'
export type SavedViewConditionOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'IN'
  | 'NOT_IN'
  | 'IS_CURRENT_ACTOR'
  | 'IS_UNASSIGNED'
  | 'IS_CURRENT_ACTOR_GROUP'
  | 'LESS_THAN_SOLVED'
  | 'WITHIN_LAST_DAYS'
export type SavedViewColumn =
  | 'TICKET_NUMBER'
  | 'SUBJECT'
  | 'STATUS'
  | 'PRIORITY'
  | 'GROUP'
  | 'ASSIGNEE'
  | 'UPDATED_AT'
  | 'FIRST_REPLY_SLA'
export type SavedViewSort = 'updatedAt:desc,ticketNumber:desc'

export interface SavedViewCondition {
  field: SavedViewConditionField
  operator: SavedViewConditionOperator
  values: string[]
}

/** Versioned, non-executable allowlisted condition AST. */
export interface SavedViewConditions {
  version: 1
  all: SavedViewCondition[]
  any: SavedViewCondition[]
}

export interface SavedViewDefinition {
  name: string
  description: string
  conditions: SavedViewConditions
  columns: SavedViewColumn[]
  sort: SavedViewSort
}

export interface SavedAgentView extends SavedViewDefinition {
  id: string
  key: string
  scope: SavedViewScope
  ownerStaffId: string | null
  active: boolean
  definitionVersion: number
  orderVersion: number
  categoryPath: string[]
  ticketCount: number | null
  ticketCountState: 'EXACT' | 'OMITTED_VISIBLE_LIMIT'
  ticketCountAsOf: string | null
  readScope: 'ALL_TICKETS'
  createdAt: string
  updatedAt: string
}

export interface CreateSavedViewInput extends SavedViewDefinition {
  scope: Exclude<SavedViewScope, 'SYSTEM'>
}

export interface UpdateSavedViewInput extends SavedViewDefinition {
  expectedVersion: number
}

export interface SavedViewPreview {
  items: AgentTicketSummary[]
  ticketCount: number
  ticketCountAsOf: string
  sort: SavedViewSort
}

export interface ReorderSavedViewsInput {
  scope: Exclude<SavedViewScope, 'SYSTEM'>
  expectedOrderVersion: number
  viewKeys: string[]
}

export interface SavedViewOrder {
  scope: Exclude<SavedViewScope, 'SYSTEM'>
  orderVersion: number
  viewKeys: string[]
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
  createdAt: string
  updatedAt: string
  version: number
  isChild: boolean
  openChildCount: number
  sla: FirstReplySlaBadge | null
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
  slaState?: FirstReplySlaState
}

export type AgentTicketSearchSort =
  'updatedAt:desc,ticketNumber:desc' | 'score:desc,ticketNumber:desc'

export interface AgentTicketSearchInput {
  query: string
  filters: AgentTicketSearchFilters
  sort: AgentTicketSearchSort
  cursor?: string | null
  limit: number
}

export interface AgentTicketSearchPage {
  searchEventId: string
  searchInteractionId: string
  items: AgentTicketSummary[]
  resultCount: number
  sort: AgentTicketSearchSort
  nextCursor: string | null
}

export interface AgentComment {
  id: string
  visibility: TicketVisibility
  actor: ActorSummary
  body: string
  content: CommentContent
  createdAt: string
  source: string
  attachments: TicketAttachment[]
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
    customer: TicketCustomerContext | null
    parent: AgentTicketSummary | null
    children: AgentTicketSummary[]
    /** Lightweight detail projection; fetch the lazy external-reference API for rows. */
    externalReferenceCount: number
  }
  history: TicketHistoryItem[]
  warnings: unknown[]
}

export interface AgentTicketFilters {
  status?: AgentTicketStatus
  priority?: TicketPriority
  groupId?: string
  assigneeId?: string
  slaState?: FirstReplySlaState
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

interface TicketCommentDraftBase {
  visibility: TicketVisibility
  attachmentIds?: string[]
}

export type TicketCommentDraft = TicketCommentDraftBase &
  (
    | { body: string; content?: never }
    | { body?: never; content: CommentContent }
  )

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

export interface CollaborationStaffSummary {
  id: string
  displayName: string
}

export interface TicketCollaborationNote {
  id: string
  ticketNumber: number
  author: ActorSummary
  body: string
  mentionedStaff: CollaborationStaffSummary[]
  createdAt: string
}

export interface CollaborationNotePage {
  items: TicketCollaborationNote[]
  nextCursor: string | null
}

export interface CreateCollaborationNoteResult {
  note: TicketCollaborationNote
  auditId: string
  replayed: boolean
}

export interface AgentNotification {
  id: string
  type: 'COLLABORATION_MENTION'
  ticketNumber: number
  noteId: string
  actor: ActorSummary
  createdAt: string
  readAt: string | null
}

export interface AgentNotificationPage {
  items: AgentNotification[]
  unreadCount: number
  nextCursor: string | null
}

export interface AgentMacroDefinition {
  id: string
  name: string
  scope: 'PERSONAL' | 'SHARED'
  ownerStaffId?: string | null
  currentVersion: number
  activeVersion: number | null
  aggregateVersion: number
  actions: unknown[]
  createdAt: string
  updatedAt: string
}

export interface MacroPreview {
  macroId: string
  macroVersion: number
  ticketNumber: number
  ticketVersion: number
  changes: Array<{ field: string; before: string | null; after: string | null }>
  comment: {
    visibility: TicketVisibility
    body: string
    content: CommentContent
  } | null
}

export interface MacroApplyResult extends TicketCommandResult {
  replayed: boolean
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

export type BatchTicketCommand =
  | {
      type: 'UPDATE'
      changedFields: Array<'status' | 'priority' | 'assigneeId'>
      status?: AgentTicketStatus
      priority?: TicketPriority
      assigneeId?: string | null
    }
  | {
      type: 'TRANSFER'
      groupId: string
      assigneeId?: string | null
      reason: string
    }

export interface AgentTicketBatchItem {
  ticketNumber: number
  expectedVersion: number
  clientCommandId: string
  command: BatchTicketCommand
}

export interface AgentTicketBatchCommand {
  items: AgentTicketBatchItem[]
}

export type AgentTicketBatchOutcome =
  'SUCCEEDED' | 'CONFLICT' | 'DENIED' | 'NOT_FOUND' | 'VALIDATION_FAILED'

export interface AgentTicketBatchItemResult {
  ticketNumber: number
  clientCommandId: string
  outcome: AgentTicketBatchOutcome
  replayed: boolean
  resultVersion: number | null
  auditId: string | null
  code:
    | 'TICKET_FIELD_CONFLICT'
    | 'VERSION_PRECONDITION_FAILED'
    | 'CLIENT_COMMAND_ID_REUSED'
    | 'TICKET_WRITE_FORBIDDEN'
    | 'TICKET_NOT_FOUND'
    | 'VALIDATION_FAILED'
    | null
}

export interface AgentTicketBatchResult {
  correlationId: string
  results: AgentTicketBatchItemResult[]
}

export interface CustomerSummary {
  id: string
  name: string
  email: string
  verified: boolean
}

export interface AgentCustomerSearchInput {
  query: string
  limit: number
}

export interface AgentCustomerSearchPage {
  searchEventId: string
  searchInteractionId: string
  items: CustomerSummary[]
  resultCount: number
}

export interface CreateAgentTicketRequester {
  customerId?: string
  name?: string
  email?: string
}

export interface CreateAgentTicketCommand {
  requester: CreateAgentTicketRequester
  subject: string
  firstComment: TicketCommentDraft
  priority: TicketPriority
  groupId?: string | null
  assigneeId?: string | null
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

export type AuditExportStatus =
  'REQUESTED' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED'

export interface AuditExportArtifact {
  state: 'PENDING' | 'READY' | 'FAILED' | 'EXPIRED' | 'DELETED'
  rowCount: number | null
  sizeBytes: number | null
  checksumSha256: string | null
  expiresAt: string | null
  contentType: 'text/csv' | 'application/x-ndjson' | null
  failureCode:
    'GENERATION_FAILED' | 'ARTIFACT_STORE_UNAVAILABLE' | 'EXPIRED' | null
}

export interface AuditExportJob {
  id: string
  status: AuditExportStatus
  createdAt: string
  format: 'CSV' | 'JSONL'
  fields: string[]
  artifact: AuditExportArtifact
}

export interface AuditExportDownload {
  content: Blob
  contentType: 'text/csv' | 'application/x-ndjson'
  fileName: string | null
  checksumSha256: string
}

export interface AuditProjectionRebuildResult {
  ticketChangeCount: number
  accessSearchCount: number
  adminSecurityCount: number
  totalCount: number
  completedAt: string
  projection: AuditProjectionStatus
}
