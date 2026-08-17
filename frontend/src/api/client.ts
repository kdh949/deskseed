import type {
  ActorSummary,
  AgentComment,
  AgentReadIntent,
  AgentTicketBatchCommand,
  AgentTicketBatchItemResult,
  AgentTicketBatchResult,
  AgentTicketStatus,
  AgentTicketDetail,
  AgentTicketFilters,
  AgentTicketPage,
  AgentCustomerSearchInput,
  AgentCustomerSearchPage,
  AgentTicketSearchInput,
  AgentTicketSearchPage,
  AgentTicketSummary,
  AdminListPage,
  AuditActivity,
  AuditActivityDetail,
  AuditActivityFilters,
  AuditActivityPage,
  AuditExportArtifact,
  AuditExportDownload,
  AuditExportJob,
  AuditProjectionRebuildResult,
  AuditProjectionStatus,
  AuditSearchContext,
  BusinessInterval,
  BusinessSchedule,
  BusinessScheduleDefinition,
  BusinessSchedulePreview,
  BusinessSchedulePreviewInput,
  BusinessWeekday,
  CreateAuditExportInput,
  CreateSavedViewInput,
  CreateAgentTicketCommand,
  CreateChildTicketCommand,
  CreateChildTicketResult,
  CustomerAccessModeSetting,
  CustomerSummary,
  UpdateCustomerAccessModeInput,
  OutboundMailAttempt,
  OutboundMailAttemptStatus,
  OutboundMailIntent,
  OutboundMailIntentPage,
  OutboundMailIntentStatus,
  OutboundMailOperationsSummary,
  OutboundMailTemplate,
  CreateStaffInput,
  CurrentStaff,
  FirstReplySlaAnalytics,
  FirstReplySlaBadge,
  FirstReplySlaPolicy,
  FirstReplySlaPolicyDefinition,
  FirstReplySlaPreview,
  FirstReplySlaPreviewInput,
  GrantableAuditAuthority,
  GroupReference,
  GroupMembership,
  IntegrationClient,
  IntegrationClientStatus,
  IntegrationCredential,
  IntegrationCredentialIssue,
  IntegrationCredentialStatus,
  IntegrationResourceConstraints,
  IntegrationScope,
  IntegrationTicketField,
  IntegrationTicketKind,
  CreateIntegrationClientInput,
  RotateIntegrationCredentialInput,
  CreateExternalReferenceInput,
  CreateExternalSystemInput,
  ExternalMetadataValue,
  ExternalObjectType,
  ExternalReference,
  ExternalReferenceCommandResult,
  ExternalReferenceContext,
  ExternalReferenceLinkState,
  ExternalSystem,
  ExternalSystemStatus,
  UpdateExternalSystemInput,
  ProblemDetails,
  PublicComment,
  PublicRequest,
  StaffAccount,
  StaffRole,
  SubmitRequestInput,
  SubmittedRequest,
  SupportGroup,
  SavedAgentView,
  SavedViewCondition,
  SavedViewConditionField,
  SavedViewConditionOperator,
  SavedViewConditions,
  SavedViewDefinition,
  SavedViewOrder,
  SavedViewPreview,
  SavedViewScope,
  SavedViewSort,
  ReorderSavedViewsInput,
  SearchQueryRevealResult,
  TicketAttachment,
  TicketHistoryItem,
  TicketAssignmentGroupOption,
  TicketAssignmentOptions,
  TicketCommandResult,
  TicketFieldName,
  TicketPriority,
  TicketStatus,
  TicketVisibility,
  TransferTicketCommand,
  UpdateSavedViewInput,
  UpdateTicketCommand,
  AttachmentUpload,
  AttachmentDownload,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
export const STAFF_SESSION_INVALID_EVENT = 'deskseed:staff-session-invalid'
export const STAFF_SESSION_ACTOR_MISMATCH_EVENT =
  'deskseed:staff-session-actor-mismatch'
const EXPECTED_STAFF_ACTOR_HEADER = 'X-Deskseed-Expected-Staff-Id'
let staffSessionGeneration = 0
let confirmedStaffActor: string | null = null

export function setConfirmedStaffActor(staffId: string | null) {
  confirmedStaffActor = isCanonicalUuid(staffId) ? staffId : null
}

export function advanceStaffSessionGeneration() {
  staffSessionGeneration += 1
}

export function isCurrentStaffSessionInvalidation(event: Event) {
  return isCurrentStaffSessionEvent(event)
}

export function isCurrentStaffSessionActorMismatch(event: Event) {
  return isCurrentStaffSessionEvent(event)
}

function isCurrentStaffSessionEvent(event: Event) {
  if (!(event instanceof CustomEvent)) return true
  const detail = event.detail as { generation?: unknown } | null
  return (
    typeof detail?.generation !== 'number' ||
    detail.generation === staffSessionGeneration
  )
}
const TICKET_STATUSES = new Set<TicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'SOLVED',
])
const AGENT_TICKET_STATUSES = new Set<AgentTicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'ON_HOLD',
  'SOLVED',
  'CLOSED',
])
const ACCESS_TOKEN_MIN_LENGTH = 32
const ACCESS_TOKEN_MAX_LENGTH = 256
const TICKET_PRIORITIES = new Set<TicketPriority>([
  'LOW',
  'NORMAL',
  'HIGH',
  'URGENT',
])
const TICKET_VISIBILITIES = new Set<TicketVisibility>(['PUBLIC', 'INTERNAL'])
const FIRST_REPLY_SLA_STATES = new Set<FirstReplySlaBadge['state']>([
  'ACTIVE',
  'AT_RISK',
  'PAUSED',
  'ACHIEVED',
  'BREACHED',
  'CANCELLED',
  'NO_POLICY',
])
const SAVED_VIEW_SCOPES = new Set<SavedViewScope>([
  'PERSONAL',
  'SHARED',
  'SYSTEM',
])
const SAVED_VIEW_CONDITION_FIELDS = new Set<SavedViewConditionField>([
  'STATUS',
  'PRIORITY',
  'GROUP',
  'ASSIGNEE',
  'FIRST_REPLY_SLA_STATE',
  'TICKET_KIND',
  'UPDATED_AT',
])
const SAVED_VIEW_CONDITION_OPERATORS = new Set<SavedViewConditionOperator>([
  'EQUALS',
  'NOT_EQUALS',
  'IN',
  'NOT_IN',
  'IS_CURRENT_ACTOR',
  'IS_UNASSIGNED',
  'IS_CURRENT_ACTOR_GROUP',
  'LESS_THAN_SOLVED',
  'WITHIN_LAST_DAYS',
])
const SAVED_VIEW_COLUMNS = new Set([
  'TICKET_NUMBER',
  'SUBJECT',
  'STATUS',
  'PRIORITY',
  'GROUP',
  'ASSIGNEE',
  'UPDATED_AT',
  'FIRST_REPLY_SLA',
])
const AGENT_TICKET_SEARCH_SORTS = new Set([
  'updatedAt:desc,ticketNumber:desc',
  'score:desc,ticketNumber:desc',
])
const AUDIT_EXPORT_STATUSES = new Set([
  'REQUESTED',
  'RUNNING',
  'READY',
  'FAILED',
  'EXPIRED',
])
const AUDIT_EXPORT_ARTIFACT_STATES = new Set([
  'PENDING',
  'READY',
  'FAILED',
  'EXPIRED',
  'DELETED',
])
const AUDIT_EXPORT_CONTENT_TYPES = new Set(['text/csv', 'application/x-ndjson'])
const ACTOR_TYPES = new Set<ActorSummary['type']>([
  'CUSTOMER',
  'STAFF',
  'INTEGRATION_CLIENT',
  'TRIGGER',
  'AUTOMATION',
  'SYSTEM',
])
const TICKET_FIELD_NAMES = new Set<TicketFieldName>([
  'status',
  'priority',
  'groupId',
  'assigneeId',
])
const INTEGRATION_SCOPES = new Set<IntegrationScope>([
  'tickets:create',
  'tickets:read',
  'tickets:update',
  'tickets:comment:internal',
])
const INTEGRATION_CLIENT_STATUSES = new Set<IntegrationClientStatus>([
  'ACTIVE',
  'DISABLED',
  'REVOKED',
])
const INTEGRATION_CREDENTIAL_STATUSES = new Set<IntegrationCredentialStatus>([
  'ACTIVE',
  'RETIRING',
  'EXPIRED',
  'REVOKED',
])
const INTEGRATION_TICKET_KINDS = new Set<IntegrationTicketKind>([
  'CUSTOMER_REQUEST',
  'INTERNAL_TASK',
])
const INTEGRATION_TICKET_FIELDS = new Set<IntegrationTicketField>([
  'status',
  'priority',
  'groupId',
  'assigneeId',
])
const EXTERNAL_SYSTEM_STATUSES = new Set<ExternalSystemStatus>([
  'ACTIVE',
  'DISABLED',
])
const EXTERNAL_OBJECT_TYPES = new Set<ExternalObjectType>([
  'ORDER',
  'PAYMENT',
  'REFUND',
  'USER',
  'STORE',
  'OPS_CASE',
  'CUSTOM',
])
const EXTERNAL_LINK_STATES = new Set<ExternalReferenceLinkState>([
  'AVAILABLE',
  'SYSTEM_DISABLED',
  'HOST_NOT_ALLOWED',
])
const OUTBOUND_MAIL_INTENT_STATUSES = new Set<OutboundMailIntentStatus>([
  'QUEUED',
  'SENDING',
  'RETRY_WAIT',
  'SENT',
  'FAILED',
])
const OUTBOUND_MAIL_ATTEMPT_STATUSES = new Set<OutboundMailAttemptStatus>([
  'IN_PROGRESS',
  'SUCCEEDED',
  'RETRYABLE_FAILED',
  'PERMANENT_FAILED',
  'ABANDONED',
])
const OUTBOUND_MAIL_TEMPLATES = new Set<OutboundMailTemplate>([
  'CUSTOMER_MAGIC_LINK',
  'REQUEST_RECEIVED',
  'PUBLIC_AGENT_REPLY',
])

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly problem?: ProblemDetails,
    readonly requestId?: string,
    readonly retryAfter?: string,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isTimestamp(value: unknown): value is string {
  return isNonBlankString(value) && Number.isFinite(Date.parse(value))
}

function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
}

function isCanonicalUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
}

function isTicketStatus(value: unknown): value is TicketStatus {
  return typeof value === 'string' && TICKET_STATUSES.has(value as TicketStatus)
}

function isAgentTicketStatus(value: unknown): value is AgentTicketStatus {
  return (
    typeof value === 'string' &&
    AGENT_TICKET_STATUSES.has(value as AgentTicketStatus)
  )
}

function isTicketNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isTicketPriority(value: unknown): value is TicketPriority {
  return (
    typeof value === 'string' && TICKET_PRIORITIES.has(value as TicketPriority)
  )
}

function isTicketVisibility(value: unknown): value is TicketVisibility {
  return (
    typeof value === 'string' &&
    TICKET_VISIBILITIES.has(value as TicketVisibility)
  )
}

function decodeFieldErrors(value: unknown): ProblemDetails['fieldErrors'] {
  if (!Array.isArray(value)) return undefined
  return value.flatMap((fieldError) => {
    if (!isRecord(fieldError)) return []
    if (
      !isNonBlankString(fieldError.field) ||
      !isNonBlankString(fieldError.message)
    ) {
      return []
    }
    return [
      {
        field: fieldError.field,
        message: fieldError.message,
        ...(typeof fieldError.code === 'string'
          ? { code: fieldError.code }
          : {}),
      },
    ]
  })
}

function decodeProblem(value: unknown): ProblemDetails | undefined {
  if (!isRecord(value)) return undefined
  const fieldErrors = decodeFieldErrors(value.fieldErrors)
  const conflictingFields = Array.isArray(value.conflictingFields)
    ? value.conflictingFields.filter(
        (field): field is TicketFieldName =>
          typeof field === 'string' &&
          TICKET_FIELD_NAMES.has(field as TicketFieldName),
      )
    : undefined
  return {
    ...(typeof value.type === 'string' ? { type: value.type } : {}),
    ...(typeof value.title === 'string' ? { title: value.title } : {}),
    ...(typeof value.status === 'number' ? { status: value.status } : {}),
    ...(typeof value.detail === 'string' ? { detail: value.detail } : {}),
    ...(typeof value.instance === 'string' ? { instance: value.instance } : {}),
    ...(typeof value.requestId === 'string'
      ? { requestId: value.requestId }
      : {}),
    ...(typeof value.code === 'string' ? { code: value.code } : {}),
    ...(fieldErrors ? { fieldErrors } : {}),
    ...(typeof value.currentVersion === 'number' &&
    Number.isSafeInteger(value.currentVersion)
      ? { currentVersion: value.currentVersion }
      : {}),
    ...(typeof value.currentAggregateVersion === 'number' &&
    Number.isSafeInteger(value.currentAggregateVersion)
      ? { currentAggregateVersion: value.currentAggregateVersion }
      : {}),
    ...(Array.isArray(value.conflictingFields) &&
    conflictingFields?.length === value.conflictingFields.length
      ? { conflictingFields }
      : {}),
  }
}

function decodeSubmittedRequest(value: unknown): SubmittedRequest | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isTicketStatus(value.status) ||
    !isNonBlankString(value.accessToken) ||
    value.accessToken.length < ACCESS_TOKEN_MIN_LENGTH ||
    value.accessToken.length > ACCESS_TOKEN_MAX_LENGTH ||
    !isTimestamp(value.createdAt)
  ) {
    return undefined
  }
  return {
    ticketNumber: value.ticketNumber,
    status: value.status,
    accessToken: value.accessToken,
    createdAt: value.createdAt,
  }
}

function decodeTicketAttachment(value: unknown): TicketAttachment | undefined {
  if (
    !isRecord(value) ||
    !isUuid(value.id) ||
    !isNonBlankString(value.fileName) ||
    typeof value.sizeBytes !== 'number' ||
    !Number.isSafeInteger(value.sizeBytes) ||
    value.sizeBytes < 0 ||
    !isNonBlankString(value.contentType)
  ) {
    return undefined
  }
  return {
    id: value.id,
    fileName: value.fileName,
    sizeBytes: value.sizeBytes,
    contentType: value.contentType,
  }
}

function decodeAttachmentUpload(value: unknown): AttachmentUpload | undefined {
  const attachment = decodeTicketAttachment(value)
  if (
    !attachment ||
    !isRecord(value) ||
    value.scanStatus !== 'CLEAN' ||
    !isTimestamp(value.expiresAt)
  ) {
    return undefined
  }
  return {
    ...attachment,
    scanStatus: 'CLEAN',
    expiresAt: value.expiresAt,
  }
}

function decodePublicComment(value: unknown): PublicComment | undefined {
  if (!isRecord(value) || !Array.isArray(value.attachments)) return undefined
  const attachments = value.attachments.map(decodeTicketAttachment)
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.authorDisplayName) ||
    !isNonBlankString(value.body) ||
    !isTimestamp(value.createdAt) ||
    attachments.some((attachment) => !attachment)
  ) {
    return undefined
  }
  return {
    id: value.id,
    authorDisplayName: value.authorDisplayName,
    body: value.body,
    createdAt: value.createdAt,
    attachments: attachments as TicketAttachment[],
  }
}

function decodePublicRequest(value: unknown): PublicRequest | undefined {
  if (!isRecord(value) || !Array.isArray(value.comments)) return undefined
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.subject) ||
    !isTicketStatus(value.status) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt)
  ) {
    return undefined
  }
  const comments: PublicComment[] = []
  for (const commentValue of value.comments) {
    const comment = decodePublicComment(commentValue)
    if (!comment) return undefined
    comments.push(comment)
  }
  return {
    ticketNumber: value.ticketNumber,
    subject: value.subject,
    status: value.status,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    comments,
  }
}

async function readJson(response: Response): Promise<unknown | undefined> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

function failure(
  response: Response,
  problem: ProblemDetails | undefined,
): ApiError {
  const fieldErrors = Object.fromEntries(
    (problem?.fieldErrors ?? []).map((error) => [error.field, error.message]),
  )
  return new ApiError(
    problem?.detail ??
      problem?.title ??
      `요청이 실패했습니다. (${response.status})`,
    response.status,
    problem,
    problem?.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
    response.headers.get('Retry-After') ?? undefined,
    fieldErrors,
  )
}

function malformedSuccess(response: Response): ApiError {
  return new ApiError(
    '서버 응답을 안전하게 처리할 수 없습니다.',
    response.status,
    undefined,
    response.headers.get('X-Request-Id') ?? undefined,
  )
}

async function successfulResponseBody(response: Response): Promise<unknown> {
  const body = await readJson(response)
  if (!response.ok) throw failure(response, decodeProblem(body))
  return body
}

export async function submitRequest(
  input: SubmitRequestInput,
  authenticatedCustomer = false,
): Promise<SubmittedRequest> {
  let csrfToken: string | undefined
  if (authenticatedCustomer) {
    const csrfResponse = await fetch(`${API_BASE_URL}/api/v1/customer/csrf`, {
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    })
    const csrfBody = await successfulResponseBody(csrfResponse)
    if (!isRecord(csrfBody) || !isNonBlankString(csrfBody.token)) {
      throw malformedSuccess(csrfResponse)
    }
    csrfToken = csrfBody.token
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(csrfToken ? { 'X-CSRF-TOKEN': csrfToken } : {}),
    },
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    body: JSON.stringify(input),
  })
  const body = await successfulResponseBody(response)
  const submitted = decodeSubmittedRequest(body)
  if (!submitted) throw malformedSuccess(response)
  return submitted
}

/**
 * Keeps initial customer attachment bytes out of JSON while preserving the same
 * request-token response contract as the JSON-only submission path.
 */
export async function submitRequestWithAttachments(
  input: SubmitRequestInput,
  files: File[],
  authenticatedCustomer = false,
): Promise<SubmittedRequest> {
  let csrfToken: string | undefined
  if (authenticatedCustomer) {
    const csrfResponse = await fetch(`${API_BASE_URL}/api/v1/customer/csrf`, {
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    })
    const csrfBody = await successfulResponseBody(csrfResponse)
    if (!isRecord(csrfBody) || !isNonBlankString(csrfBody.token)) {
      throw malformedSuccess(csrfResponse)
    }
    csrfToken = csrfBody.token
  }
  const form = new FormData()
  form.set('name', input.name)
  form.set('email', input.email)
  form.set('subject', input.subject)
  form.set('message', input.message)
  if (input.privacyConsent !== undefined) {
    form.set('privacyConsent', String(input.privacyConsent))
  }
  files.forEach((file) => form.append('attachments', file, file.name))
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    credentials: 'include',
    headers: csrfToken ? { 'X-CSRF-TOKEN': csrfToken } : undefined,
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    body: form,
  })
  const body = await successfulResponseBody(response)
  const submitted = decodeSubmittedRequest(body)
  if (!submitted) throw malformedSuccess(response)
  return submitted
}

export async function getPublicRequest(
  ticketNumber: number,
  accessToken: string,
): Promise<PublicRequest> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}`,
    {
      credentials: 'include',
      headers: { 'X-Request-Access-Token': accessToken },
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    },
  )
  const body = await successfulResponseBody(response)
  const request = decodePublicRequest(body)
  if (!request) throw malformedSuccess(response)
  return request
}

export async function addAnonymousRequestComment(
  ticketNumber: number,
  accessToken: string,
  body: string,
  clientCommandId: string,
  attachmentIds: string[] = [],
): Promise<PublicComment> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}/comments`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Access-Token': accessToken,
      },
      body: JSON.stringify({
        body,
        ...(attachmentIds.length ? { attachmentIds } : {}),
        clientCommandId,
      }),
    },
  )
  const responseBody = await successfulResponseBody(response)
  const comment = decodePublicComment(responseBody)
  if (!comment) throw malformedSuccess(response)
  return comment
}

export async function uploadAnonymousRequestAttachment(
  ticketNumber: number,
  accessToken: string,
  file: File,
): Promise<AttachmentUpload> {
  const form = new FormData()
  form.append('file', file, file.name)
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}/attachments/uploads`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'X-Request-Access-Token': accessToken },
      body: form,
    },
  )
  const uploaded = decodeAttachmentUpload(
    await successfulResponseBody(response),
  )
  if (!uploaded) throw malformedSuccess(response)
  return uploaded
}

export async function downloadAnonymousRequestAttachment(
  ticketNumber: number,
  attachmentId: string,
  accessToken: string,
): Promise<AttachmentDownload> {
  const response = await checkedBinary(
    await fetch(
      `${API_BASE_URL}/api/v1/requests/${ticketNumber}/attachments/${encodeURIComponent(attachmentId)}/download`,
      {
        credentials: 'include',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        headers: { 'X-Request-Access-Token': accessToken },
      },
    ),
  )
  const contentType = responseContentType(response)
  if (!contentType) throw malformedSuccess(response)
  return {
    content: await response.blob(),
    contentType,
    fileName: responseFileName(response),
  }
}

const STAFF_ROLES = new Set<StaffRole>(['ADMIN', 'AGENT', 'SECURITY_AUDITOR'])

function isStaffRole(value: unknown): value is StaffRole {
  return typeof value === 'string' && STAFF_ROLES.has(value as StaffRole)
}

function decodeCurrentStaff(value: unknown): CurrentStaff | undefined {
  if (!isRecord(value) || !Array.isArray(value.capabilities)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.email) ||
    !isNonBlankString(value.displayName) ||
    !isStaffRole(value.role) ||
    !value.capabilities.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    id: value.id,
    email: value.email,
    displayName: value.displayName,
    role: value.role,
    capabilities: value.capabilities,
  }
}

function decodeIntegrationStringSet<T extends string>(
  value: unknown,
  allowed: ReadonlySet<T>,
): T[] | undefined {
  if (!Array.isArray(value)) return undefined
  if (
    !value.every(
      (item): item is T => typeof item === 'string' && allowed.has(item as T),
    )
  ) {
    return undefined
  }
  return value
}

function decodeNullableStringSet<T extends string>(
  value: unknown,
  allowed: ReadonlySet<T>,
): T[] | null | undefined {
  if (value === null) return null
  return decodeIntegrationStringSet(value, allowed)
}

function decodeNullableStringArray(
  value: unknown,
): string[] | null | undefined {
  if (value === null) return null
  if (!Array.isArray(value) || !value.every(isNonBlankString)) return undefined
  return value
}

function decodeIntegrationConstraints(
  value: unknown,
): IntegrationResourceConstraints | undefined {
  if (!isRecord(value)) return undefined
  const allowedGroupIds = decodeNullableStringArray(value.allowedGroupIds)
  const allowedTicketKinds = decodeNullableStringSet(
    value.allowedTicketKinds,
    INTEGRATION_TICKET_KINDS,
  )
  const allowedFields = decodeNullableStringSet(
    value.allowedFields,
    INTEGRATION_TICKET_FIELDS,
  )
  const ipAllowlist = decodeNullableStringArray(value.ipAllowlist)
  if (
    allowedGroupIds === undefined ||
    allowedTicketKinds === undefined ||
    allowedFields === undefined ||
    ipAllowlist === undefined
  ) {
    return undefined
  }
  return { allowedGroupIds, allowedTicketKinds, allowedFields, ipAllowlist }
}

function decodeIntegrationCredential(
  value: unknown,
): IntegrationCredential | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.id) ||
    typeof value.sequence !== 'number' ||
    !Number.isSafeInteger(value.sequence) ||
    value.sequence < 1 ||
    !isNonBlankString(value.publicKeyId) ||
    typeof value.status !== 'string' ||
    !INTEGRATION_CREDENTIAL_STATUSES.has(
      value.status as IntegrationCredentialStatus,
    ) ||
    !isTimestamp(value.expiresAt) ||
    (value.overlapExpiresAt !== null && !isTimestamp(value.overlapExpiresAt)) ||
    !isTimestamp(value.createdAt) ||
    (value.revokedAt !== null && !isTimestamp(value.revokedAt)) ||
    (value.lastUsedAt !== null && !isTimestamp(value.lastUsedAt)) ||
    (value.lastUsedIp !== null && typeof value.lastUsedIp !== 'string')
  ) {
    return undefined
  }
  return {
    id: value.id,
    sequence: value.sequence,
    publicKeyId: value.publicKeyId,
    status: value.status as IntegrationCredentialStatus,
    expiresAt: value.expiresAt,
    overlapExpiresAt: value.overlapExpiresAt as string | null,
    createdAt: value.createdAt,
    revokedAt: value.revokedAt as string | null,
    lastUsedAt: value.lastUsedAt as string | null,
    lastUsedIp: value.lastUsedIp as string | null,
  }
}

function decodeIntegrationClient(
  value: unknown,
): IntegrationClient | undefined {
  if (!isRecord(value) || !Array.isArray(value.credentials)) return undefined
  const scopes = decodeIntegrationStringSet(value.scopes, INTEGRATION_SCOPES)
  const resourceConstraints = decodeIntegrationConstraints(
    value.resourceConstraints,
  )
  const credentials = value.credentials.map(decodeIntegrationCredential)
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name) ||
    typeof value.description !== 'string' ||
    typeof value.status !== 'string' ||
    !INTEGRATION_CLIENT_STATUSES.has(value.status as IntegrationClientStatus) ||
    !scopes ||
    !resourceConstraints ||
    credentials.some((credential) => !credential) ||
    (value.expiresAt !== null && !isTimestamp(value.expiresAt)) ||
    (value.lastUsedAt !== null && !isTimestamp(value.lastUsedAt)) ||
    (value.lastUsedIp !== null && typeof value.lastUsedIp !== 'string') ||
    !isTimestamp(value.createdAt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    description: value.description,
    status: value.status as IntegrationClientStatus,
    scopes,
    resourceConstraints,
    credentials: credentials as IntegrationCredential[],
    expiresAt: value.expiresAt as string | null,
    lastUsedAt: value.lastUsedAt as string | null,
    lastUsedIp: value.lastUsedIp as string | null,
    createdAt: value.createdAt,
  }
}

function decodeIntegrationCredentialIssue(
  value: unknown,
): IntegrationCredentialIssue | undefined {
  if (!isRecord(value)) return undefined
  const client = decodeIntegrationClient(value.client)
  const credential = decodeIntegrationCredential(value.credential)
  if (
    !client ||
    !credential ||
    typeof value.apiKey !== 'string' ||
    !/^dsk_live_[A-Za-z0-9_-]{16,32}\.[A-Za-z0-9_-]{43}$/.test(value.apiKey)
  ) {
    return undefined
  }
  return { client, credential, apiKey: value.apiKey }
}

function decodeExternalSystem(value: unknown): ExternalSystem | undefined {
  if (
    !isRecord(value) ||
    !isUuid(value.id) ||
    !isNonBlankString(value.systemKey) ||
    !isNonBlankString(value.displayName) ||
    typeof value.status !== 'string' ||
    !EXTERNAL_SYSTEM_STATUSES.has(value.status as ExternalSystemStatus) ||
    !Array.isArray(value.allowedHostnames) ||
    !value.allowedHostnames.every(isNonBlankString) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0
  ) {
    return undefined
  }
  return {
    id: value.id,
    systemKey: value.systemKey,
    displayName: value.displayName,
    status: value.status as ExternalSystemStatus,
    allowedHostnames: value.allowedHostnames,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    version: value.version,
  }
}

function decodeExternalMetadata(
  value: unknown,
): Record<string, ExternalMetadataValue> | undefined {
  if (!isRecord(value)) return undefined
  const entries = Object.entries(value)
  if (
    entries.length > 8 ||
    entries.some(
      ([, item]) =>
        !(
          typeof item === 'string' ||
          typeof item === 'boolean' ||
          (typeof item === 'number' && Number.isFinite(item))
        ),
    )
  ) {
    return undefined
  }
  return Object.fromEntries(entries) as Record<string, ExternalMetadataValue>
}

function decodeExternalReference(
  value: unknown,
): ExternalReference | undefined {
  if (!isRecord(value) || !isRecord(value.createdBy)) return undefined
  const system = decodeExternalSystem(value.system)
  const metadata = decodeExternalMetadata(value.metadata)
  if (
    !isUuid(value.id) ||
    !system ||
    typeof value.objectType !== 'string' ||
    !EXTERNAL_OBJECT_TYPES.has(value.objectType as ExternalObjectType) ||
    !isNonBlankString(value.externalId) ||
    !isNonBlankString(value.displayLabel) ||
    typeof value.linkState !== 'string' ||
    !EXTERNAL_LINK_STATES.has(value.linkState as ExternalReferenceLinkState) ||
    (value.safeDeepLink !== null && !isNonBlankString(value.safeDeepLink)) ||
    (value.linkState === 'AVAILABLE' && value.safeDeepLink === null) ||
    !metadata ||
    !isTimestamp(value.metadataObservedAt) ||
    !isUuid(value.createdBy.actorId) ||
    !isNonBlankString(value.createdBy.displayName) ||
    !isTimestamp(value.createdAt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    system,
    objectType: value.objectType as ExternalObjectType,
    externalId: value.externalId,
    displayLabel: value.displayLabel,
    linkState: value.linkState as ExternalReferenceLinkState,
    safeDeepLink: value.safeDeepLink as string | null,
    metadata,
    metadataObservedAt: value.metadataObservedAt,
    createdBy: {
      actorId: value.createdBy.actorId,
      displayName: value.createdBy.displayName,
    },
    createdAt: value.createdAt,
  }
}

function decodeExternalReferenceContext(
  value: unknown,
): ExternalReferenceContext | undefined {
  if (
    !isRecord(value) ||
    typeof value.ticketVersion !== 'number' ||
    !Number.isSafeInteger(value.ticketVersion) ||
    value.ticketVersion < 0 ||
    typeof value.canManage !== 'boolean' ||
    !Array.isArray(value.availableSystems) ||
    !Array.isArray(value.items)
  ) {
    return undefined
  }
  const availableSystems = value.availableSystems.map(decodeExternalSystem)
  const items = value.items.map(decodeExternalReference)
  if (
    availableSystems.some((system) => !system) ||
    items.some((reference) => !reference)
  ) {
    return undefined
  }
  return {
    ticketVersion: value.ticketVersion,
    canManage: value.canManage,
    availableSystems: availableSystems as ExternalSystem[],
    items: items as ExternalReference[],
  }
}

function decodeExternalReferenceCommand(
  value: unknown,
): ExternalReferenceCommandResult | undefined {
  if (
    !isRecord(value) ||
    typeof value.ticketVersion !== 'number' ||
    !Number.isSafeInteger(value.ticketVersion) ||
    value.ticketVersion < 0
  ) {
    return undefined
  }
  const reference = decodeExternalReference(value.reference)
  return reference
    ? { ticketVersion: value.ticketVersion, reference }
    : undefined
}

function decodeStaffAccount(value: unknown): StaffAccount | undefined {
  if (
    !isRecord(value) ||
    !Array.isArray(value.memberships) ||
    !Array.isArray(value.auditAuthorities)
  )
    return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.email) ||
    !isNonBlankString(value.displayName) ||
    !isStaffRole(value.role) ||
    (value.status !== 'ACTIVE' && value.status !== 'DISABLED') ||
    (value.lastLoginAt !== null && !isTimestamp(value.lastLoginAt))
  ) {
    return undefined
  }
  const memberships = value.memberships.flatMap((membership) => {
    if (
      !isRecord(membership) ||
      !isNonBlankString(membership.id) ||
      !isNonBlankString(membership.name)
    ) {
      return []
    }
    return [{ id: membership.id, name: membership.name }]
  })
  if (memberships.length !== value.memberships.length) return undefined
  const grantableAuthorities = new Set<GrantableAuditAuthority>([
    'AUDIT_SEARCH_QUERY_REVEAL',
    'AUDIT_EXPORT',
    'AUDIT_PROJECTION_REBUILD',
  ])
  if (
    !value.auditAuthorities.every(
      (authority): authority is GrantableAuditAuthority =>
        typeof authority === 'string' &&
        grantableAuthorities.has(authority as GrantableAuditAuthority),
    )
  )
    return undefined
  return {
    id: value.id,
    email: value.email,
    displayName: value.displayName,
    role: value.role,
    status: value.status,
    memberships,
    auditAuthorities: value.auditAuthorities,
    lastLoginAt: value.lastLoginAt,
  }
}

function decodeSupportGroup(value: unknown): SupportGroup | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name) ||
    (value.status !== 'ACTIVE' && value.status !== 'DISABLED') ||
    typeof value.memberCount !== 'number'
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    status: value.status,
    memberCount: value.memberCount,
  }
}

function decodeMembership(value: unknown): GroupMembership | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.groupId) ||
    !isNonBlankString(value.staffId) ||
    !isNonBlankString(value.staffDisplayName) ||
    !isStaffRole(value.role)
  ) {
    return undefined
  }
  return {
    groupId: value.groupId,
    staffId: value.staffId,
    staffDisplayName: value.staffDisplayName,
    role: value.role,
  }
}

interface StaffFetchOptions {
  invalidateSessionOn401?: boolean
  omitExpectedStaffActor?: boolean
  onMutationRequestStart?: () => void
}

interface StaffRequestSnapshot {
  generation: number
  actor: string | null
}

function captureStaffRequestSnapshot(): StaffRequestSnapshot {
  return {
    generation: staffSessionGeneration,
    actor: confirmedStaffActor,
  }
}

function isCurrentStaffRequestSnapshot(snapshot: StaffRequestSnapshot) {
  return (
    snapshot.generation === staffSessionGeneration &&
    snapshot.actor === confirmedStaffActor
  )
}

async function staffFetch(
  path: string,
  init: RequestInit = {},
  {
    invalidateSessionOn401 = true,
    omitExpectedStaffActor = false,
  }: StaffFetchOptions = {},
  requestSnapshot = captureStaffRequestSnapshot(),
) {
  const requestSessionGeneration = requestSnapshot.generation
  let headers: Record<string, string>
  if (init.headers instanceof Headers) {
    headers = {}
    init.headers.forEach((value, name) => {
      headers[name] = value
    })
  } else if (Array.isArray(init.headers)) {
    headers = Object.fromEntries(init.headers)
  } else {
    headers = { ...(init.headers ?? {}) }
  }
  for (const headerName of Object.keys(headers)) {
    if (
      headerName.toLowerCase() === EXPECTED_STAFF_ACTOR_HEADER.toLowerCase()
    ) {
      delete headers[headerName]
    }
  }
  if (!omitExpectedStaffActor && requestSnapshot.actor !== null) {
    headers[EXPECTED_STAFF_ACTOR_HEADER] = requestSnapshot.actor
  }
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    cache: 'no-store',
    ...init,
    headers,
  })
  if (
    invalidateSessionOn401 &&
    response.status === 401 &&
    typeof window !== 'undefined'
  ) {
    window.dispatchEvent(
      new CustomEvent(STAFF_SESSION_INVALID_EVENT, {
        detail: { generation: requestSessionGeneration },
      }),
    )
  }
  if (response.status === 409 && typeof window !== 'undefined') {
    const problem = decodeProblem(await readJson(response.clone()))
    if (problem?.type === '/problems/staff-session-actor-mismatch') {
      window.dispatchEvent(
        new CustomEvent(STAFF_SESSION_ACTOR_MISMATCH_EVENT, {
          detail: { generation: requestSessionGeneration },
        }),
      )
    }
  }
  return response
}

async function checkedBody(response: Response): Promise<unknown> {
  return successfulResponseBody(response)
}

async function checkedEmpty(response: Response): Promise<void> {
  if (response.ok) return
  throw failure(response, decodeProblem(await readJson(response)))
}

async function checkedBinary(response: Response): Promise<Response> {
  if (response.ok) return response
  throw failure(response, decodeProblem(await readJson(response)))
}

function responseContentType(response: Response): string | null {
  const header = response.headers.get('Content-Type')
  if (!header) return null
  const [contentType] = header.split(';', 1)
  return contentType?.trim().toLocaleLowerCase('en-US') || null
}

function responseFileName(response: Response): string | null {
  const contentDisposition = response.headers.get('Content-Disposition')
  if (!contentDisposition) return null
  const encoded = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return null
    }
  }
  return contentDisposition.match(/filename="?([^";]+)"?/i)?.[1] ?? null
}

async function csrfHeaders(
  options: StaffFetchOptions = {},
  requestSnapshot = captureStaffRequestSnapshot(),
): Promise<Record<string, string>> {
  const response = await staffFetch(
    '/api/v1/agent/csrf',
    {},
    options,
    requestSnapshot,
  )
  const body = await checkedBody(response)
  if (
    !isRecord(body) ||
    !isNonBlankString(body.token) ||
    !isNonBlankString(body.headerName)
  ) {
    throw malformedSuccess(response)
  }
  return { [body.headerName]: body.token }
}

async function unsafeStaffFetch(
  path: string,
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  body?: unknown,
  additionalHeaders: Record<string, string> = {},
  options: StaffFetchOptions = {},
) {
  const requestSnapshot = captureStaffRequestSnapshot()
  const csrf = await csrfHeaders(options, requestSnapshot)
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  options.onMutationRequestStart?.()
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  return staffFetch(
    path,
    {
      method,
      headers: {
        ...csrf,
        ...additionalHeaders,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    options,
    requestSnapshot,
  )
}

async function unsafeStaffMultipartFetch(
  path: string,
  form: FormData,
  additionalHeaders: Record<string, string> = {},
  options: StaffFetchOptions = {},
) {
  const requestSnapshot = captureStaffRequestSnapshot()
  const csrf = await csrfHeaders(options, requestSnapshot)
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  options.onMutationRequestStart?.()
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  return staffFetch(
    path,
    {
      method: 'POST',
      headers: { ...csrf, ...additionalHeaders },
      body: form,
    },
    options,
    requestSnapshot,
  )
}

export async function loginStaff(
  email: string,
  password: string,
  onMutationRequestStart?: () => void,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      '/api/v1/agent/session',
      'POST',
      {
        email,
        password,
      },
      {},
      {
        invalidateSessionOn401: false,
        omitExpectedStaffActor: true,
        onMutationRequestStart,
      },
    ),
  )
}

export async function logoutStaff(
  options: StaffFetchOptions = {},
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      '/api/v1/agent/session',
      'DELETE',
      undefined,
      {},
      options,
    ),
  )
}

export async function getCurrentStaff(
  options: StaffFetchOptions = {},
): Promise<CurrentStaff> {
  const response = await staffFetch('/api/v1/agent/me', {}, options)
  const decoded = decodeCurrentStaff(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function listStaff(
  page = 0,
  size = 50,
): Promise<AdminListPage<StaffAccount>> {
  const response = await staffFetch(
    `/api/v1/admin/staff?page=${page}&size=${size}`,
  )
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeStaffAccount)
  if (decoded.some((staff) => !staff)) throw malformedSuccess(response)
  return decodeAdminListPage(response, decoded as StaffAccount[], page, size)
}

export async function listIntegrationClients(
  page = 0,
  size = 50,
): Promise<AdminListPage<IntegrationClient>> {
  const response = await staffFetch(
    `/api/v1/admin/integration-clients?page=${page}&size=${size}`,
  )
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeIntegrationClient)
  if (decoded.some((client) => !client)) throw malformedSuccess(response)
  return decodeAdminListPage(
    response,
    decoded as IntegrationClient[],
    page,
    size,
  )
}

export async function getIntegrationClient(
  clientId: string,
): Promise<IntegrationClient> {
  const response = await staffFetch(
    `/api/v1/admin/integration-clients/${clientId}`,
  )
  const decoded = decodeIntegrationClient(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createIntegrationClient(
  input: CreateIntegrationClientInput,
): Promise<IntegrationCredentialIssue> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/integration-clients',
    'POST',
    input,
  )
  const decoded = decodeIntegrationCredentialIssue(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function disableIntegrationClient(
  clientId: string,
): Promise<IntegrationClient> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/integration-clients/${clientId}/disable`,
    'POST',
  )
  const decoded = decodeIntegrationClient(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function revokeIntegrationClient(
  clientId: string,
): Promise<IntegrationClient> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/integration-clients/${clientId}/revoke`,
    'POST',
  )
  const decoded = decodeIntegrationClient(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function rotateIntegrationClientCredential(
  clientId: string,
  input: RotateIntegrationCredentialInput,
): Promise<IntegrationCredentialIssue> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/integration-clients/${clientId}/rotate`,
    'POST',
    input,
  )
  const decoded = decodeIntegrationCredentialIssue(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function listExternalSystems(): Promise<ExternalSystem[]> {
  const response = await staffFetch('/api/v1/admin/external-systems')
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeExternalSystem)
  if (decoded.some((system) => !system)) throw malformedSuccess(response)
  return decoded as ExternalSystem[]
}

export async function createExternalSystem(
  input: CreateExternalSystemInput,
): Promise<ExternalSystem> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/external-systems',
    'POST',
    input,
  )
  const decoded = decodeExternalSystem(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function updateExternalSystem(
  systemId: string,
  input: UpdateExternalSystemInput,
): Promise<ExternalSystem> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/external-systems/${systemId}`,
    'PUT',
    input,
    { 'If-Match': `"${input.expectedVersion}"` },
  )
  const decoded = decodeExternalSystem(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createStaff(
  input: CreateStaffInput,
): Promise<StaffAccount> {
  const response = await unsafeStaffFetch('/api/v1/admin/staff', 'POST', input)
  const decoded = decodeStaffAccount(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function disableStaff(staffId: string): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(`/api/v1/admin/staff/${staffId}`, 'DELETE'),
  )
}

export async function grantStaffAuditAuthority(
  staffId: string,
  authority: GrantableAuditAuthority,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      `/api/v1/admin/staff/${staffId}/audit-authorities/${authority}`,
      'PUT',
    ),
  )
}

export async function revokeStaffAuditAuthority(
  staffId: string,
  authority: GrantableAuditAuthority,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      `/api/v1/admin/staff/${staffId}/audit-authorities/${authority}`,
      'DELETE',
    ),
  )
}

export async function listGroups(
  page = 0,
  size = 50,
): Promise<AdminListPage<SupportGroup>> {
  const response = await staffFetch(
    `/api/v1/admin/groups?page=${page}&size=${size}`,
  )
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeSupportGroup)
  if (decoded.some((group) => !group)) throw malformedSuccess(response)
  return decodeAdminListPage(response, decoded as SupportGroup[], page, size)
}

export async function createGroup(name: string): Promise<SupportGroup> {
  const response = await unsafeStaffFetch('/api/v1/admin/groups', 'POST', {
    name,
  })
  const decoded = decodeSupportGroup(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function renameGroup(
  groupId: string,
  name: string,
): Promise<SupportGroup> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/groups/${groupId}`,
    'PATCH',
    { name },
  )
  const decoded = decodeSupportGroup(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function disableGroup(groupId: string): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(`/api/v1/admin/groups/${groupId}`, 'DELETE'),
  )
}

export async function listGroupMembers(
  groupId: string,
  page = 0,
  size = 50,
): Promise<AdminListPage<GroupMembership>> {
  const response = await staffFetch(
    `/api/v1/admin/groups/${groupId}/members?page=${page}&size=${size}`,
  )
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeMembership)
  if (decoded.some((membership) => !membership))
    throw malformedSuccess(response)
  return decodeAdminListPage(response, decoded as GroupMembership[], page, size)
}

function decodeAdminListPage<T>(
  response: Response,
  items: T[],
  requestedPage: number,
  requestedSize: number,
): AdminListPage<T> {
  const headerValues = [
    response.headers.get('X-Page-Number'),
    response.headers.get('X-Page-Size'),
    response.headers.get('X-Total-Count'),
    response.headers.get('X-Total-Pages'),
  ]
  if (headerValues.every((value) => value === null)) {
    return {
      items,
      page: requestedPage,
      size: requestedSize,
      totalCount: items.length,
      totalPages: items.length === 0 ? 0 : requestedPage + 1,
    }
  }
  const values = headerValues.map((value) =>
    value !== null && /^\d+$/.test(value) ? Number(value) : Number.NaN,
  )
  const page = values[0]!
  const size = values[1]!
  const totalCount = values[2]!
  const totalPages = values[3]!
  if (
    values.some((value) => !Number.isSafeInteger(value)) ||
    page < 0 ||
    size < 1 ||
    size > 100 ||
    totalCount < 0 ||
    totalPages < 0
  ) {
    throw malformedSuccess(response)
  }
  return { items, page, size, totalCount, totalPages }
}

export async function addGroupMember(
  groupId: string,
  staffId: string,
): Promise<GroupMembership> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/groups/${groupId}/members`,
    'POST',
    { staffId },
  )
  const decoded = decodeMembership(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function removeGroupMember(
  groupId: string,
  staffId: string,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      `/api/v1/admin/groups/${groupId}/members/${staffId}`,
      'DELETE',
    ),
  )
}

const BUSINESS_WEEKDAYS = new Set<BusinessWeekday>([
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
])

function decodeBusinessInterval(value: unknown): BusinessInterval | undefined {
  if (
    !isRecord(value) ||
    typeof value.start !== 'string' ||
    typeof value.end !== 'string' ||
    !/^\d{2}:\d{2}$/.test(value.start) ||
    !/^\d{2}:\d{2}$/.test(value.end)
  ) {
    return undefined
  }
  return { start: value.start, end: value.end }
}

function decodeBusinessSchedule(value: unknown): BusinessSchedule | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.id) ||
    !isNonBlankString(value.name) ||
    !isNonBlankString(value.timeZone) ||
    !Array.isArray(value.weekdays) ||
    !Array.isArray(value.exceptions) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    (value.activeVersion !== null &&
      (typeof value.activeVersion !== 'number' ||
        !Number.isSafeInteger(value.activeVersion) ||
        value.activeVersion < 1)) ||
    (value.activeTimeZone !== null &&
      !isNonBlankString(value.activeTimeZone)) ||
    value.version < 1 ||
    typeof value.aggregateVersion !== 'number' ||
    !Number.isSafeInteger(value.aggregateVersion) ||
    value.aggregateVersion < 0 ||
    typeof value.active !== 'boolean' ||
    !isTimestamp(value.createdAt) ||
    !isRecord(value.createdBy) ||
    !['STAFF', 'SYSTEM'].includes(String(value.createdBy.actorType)) ||
    (value.createdBy.actorId !== null &&
      !isCanonicalUuid(value.createdBy.actorId)) ||
    !isNonBlankString(value.createdBy.displayName)
  ) {
    return undefined
  }
  const weekdays = value.weekdays.flatMap((weekday) => {
    if (
      !isRecord(weekday) ||
      typeof weekday.weekday !== 'string' ||
      !BUSINESS_WEEKDAYS.has(weekday.weekday as BusinessWeekday) ||
      typeof weekday.enabled !== 'boolean' ||
      !Array.isArray(weekday.intervals)
    ) {
      return []
    }
    const intervals = weekday.intervals.map(decodeBusinessInterval)
    if (intervals.some((interval) => !interval)) return []
    return [
      {
        weekday: weekday.weekday as BusinessWeekday,
        enabled: weekday.enabled,
        intervals: intervals as BusinessInterval[],
      },
    ]
  })
  const exceptions = value.exceptions.flatMap((exception) => {
    if (
      !isRecord(exception) ||
      typeof exception.date !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(exception.date) ||
      !['CLOSED', 'OPEN'].includes(String(exception.mode)) ||
      !Array.isArray(exception.intervals) ||
      (exception.label !== null && typeof exception.label !== 'string')
    ) {
      return []
    }
    const intervals = exception.intervals.map(decodeBusinessInterval)
    if (intervals.some((interval) => !interval)) return []
    return [
      {
        date: exception.date,
        mode: exception.mode as 'CLOSED' | 'OPEN',
        intervals: intervals as BusinessInterval[],
        label: exception.label as string | null,
      },
    ]
  })
  if (
    weekdays.length !== value.weekdays.length ||
    weekdays.length !== 7 ||
    exceptions.length !== value.exceptions.length
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    timeZone: value.timeZone,
    weekdays,
    exceptions,
    version: value.version,
    activeVersion: value.activeVersion as number | null,
    activeTimeZone: value.activeTimeZone as string | null,
    aggregateVersion: value.aggregateVersion,
    active: value.active,
    createdAt: value.createdAt,
    createdBy: {
      actorType: value.createdBy.actorType as 'STAFF' | 'SYSTEM',
      actorId: value.createdBy.actorId as string | null,
      displayName: value.createdBy.displayName,
    },
  }
}

function decodedBusinessSchedules(response: Response, body: unknown) {
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const schedules = body.map(decodeBusinessSchedule)
  if (schedules.some((schedule) => !schedule)) throw malformedSuccess(response)
  return schedules as BusinessSchedule[]
}

export async function listBusinessSchedules(): Promise<BusinessSchedule[]> {
  const response = await staffFetch('/api/v1/admin/business-schedules')
  return decodedBusinessSchedules(response, await checkedBody(response))
}

export async function createBusinessSchedule(
  definition: BusinessScheduleDefinition,
): Promise<BusinessSchedule> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/business-schedules',
    'POST',
    definition,
  )
  const schedule = decodeBusinessSchedule(await checkedBody(response))
  if (!schedule) throw malformedSuccess(response)
  return schedule
}

export async function listBusinessScheduleVersions(
  scheduleId: string,
): Promise<BusinessSchedule[]> {
  const response = await staffFetch(
    `/api/v1/admin/business-schedules/${scheduleId}/versions`,
  )
  return decodedBusinessSchedules(response, await checkedBody(response))
}

export async function createBusinessScheduleVersion(
  scheduleId: string,
  aggregateVersion: number,
  definition: BusinessScheduleDefinition,
): Promise<BusinessSchedule> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/business-schedules/${scheduleId}/versions`,
    'POST',
    definition,
    { 'If-Match': `"${aggregateVersion}"` },
  )
  const schedule = decodeBusinessSchedule(await checkedBody(response))
  if (!schedule) throw malformedSuccess(response)
  return schedule
}

export async function activateBusinessScheduleVersion(
  scheduleId: string,
  version: number,
  aggregateVersion: number,
): Promise<BusinessSchedule> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/business-schedules/${scheduleId}/versions/${version}/activation`,
    'PUT',
    undefined,
    { 'If-Match': `"${aggregateVersion}"` },
  )
  const schedule = decodeBusinessSchedule(await checkedBody(response))
  if (!schedule) throw malformedSuccess(response)
  return schedule
}

export async function previewBusinessSchedule(
  input: BusinessSchedulePreviewInput,
): Promise<BusinessSchedulePreview> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/business-schedules/preview',
    'POST',
    input,
  )
  const body = await checkedBody(response)
  if (
    !isRecord(body) ||
    (body.dueAt !== null && !isTimestamp(body.dueAt)) ||
    typeof body.elapsedBusinessMinutes !== 'number' ||
    !Number.isSafeInteger(body.elapsedBusinessMinutes) ||
    (body.nextOpenAt !== null && !isTimestamp(body.nextOpenAt)) ||
    (body.nextCloseAt !== null && !isTimestamp(body.nextCloseAt)) ||
    body.dstPolicy !== 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH'
  ) {
    throw malformedSuccess(response)
  }
  return {
    dueAt: body.dueAt,
    elapsedBusinessMinutes: body.elapsedBusinessMinutes,
    nextOpenAt: body.nextOpenAt,
    nextCloseAt: body.nextCloseAt,
    dstPolicy: body.dstPolicy,
  }
}

function decodeFirstReplySlaPolicy(
  value: unknown,
): FirstReplySlaPolicy | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.id) ||
    !isNonBlankString(value.name) ||
    typeof value.position !== 'number' ||
    !Number.isSafeInteger(value.position) ||
    !isCanonicalUuid(value.scheduleId) ||
    typeof value.scheduleVersion !== 'number' ||
    !Number.isSafeInteger(value.scheduleVersion) ||
    !isRecord(value.conditions) ||
    !isRecord(value.targets) ||
    !Array.isArray(value.pauseStatuses) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    typeof value.aggregateVersion !== 'number' ||
    !Number.isSafeInteger(value.aggregateVersion) ||
    typeof value.active !== 'boolean' ||
    !isTimestamp(value.createdAt) ||
    !isRecord(value.createdBy) ||
    value.createdBy.actorType !== 'STAFF' ||
    !isCanonicalUuid(value.createdBy.actorId) ||
    !isNonBlankString(value.createdBy.displayName)
  ) {
    return undefined
  }
  const targetRecord = value.targets as Record<string, unknown>
  const target = (priority: TicketPriority): number | null | undefined => {
    const minutes = targetRecord[priority]
    return minutes === undefined || minutes === null
      ? null
      : typeof minutes === 'number' &&
          Number.isSafeInteger(minutes) &&
          minutes > 0
        ? minutes
        : undefined
  }
  const targets = {
    LOW: target('LOW'),
    NORMAL: target('NORMAL'),
    HIGH: target('HIGH'),
    URGENT: target('URGENT'),
  }
  if (
    Object.values(targets).some((minutes) => minutes === undefined) ||
    (value.conditions.groupId !== null &&
      !isCanonicalUuid(value.conditions.groupId)) ||
    (value.conditions.channel !== null &&
      !['WEB', 'AGENT', 'EMAIL', 'CHAT', 'API'].includes(
        String(value.conditions.channel),
      )) ||
    !value.pauseStatuses.every(
      (status) =>
        isAgentTicketStatus(status) &&
        status !== 'SOLVED' &&
        status !== 'CLOSED',
    )
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    position: value.position,
    scheduleId: value.scheduleId,
    scheduleVersion: value.scheduleVersion,
    conditions: {
      groupId: value.conditions.groupId as string | null,
      channel: value.conditions
        .channel as FirstReplySlaPolicy['conditions']['channel'],
    },
    targets: targets as FirstReplySlaPolicy['targets'],
    pauseStatuses: value.pauseStatuses as FirstReplySlaPolicy['pauseStatuses'],
    version: value.version,
    activeVersion: value.activeVersion as number | null,
    aggregateVersion: value.aggregateVersion,
    active: value.active,
    createdAt: value.createdAt,
    createdBy: {
      actorType: 'STAFF',
      actorId: value.createdBy.actorId,
      displayName: value.createdBy.displayName,
    },
  }
}

function decodeFirstReplySlaPolicies(response: Response, body: unknown) {
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const policies = body.map(decodeFirstReplySlaPolicy)
  if (policies.some((policy) => !policy)) throw malformedSuccess(response)
  return policies as FirstReplySlaPolicy[]
}

export async function listFirstReplySlaPolicies(): Promise<
  FirstReplySlaPolicy[]
> {
  const response = await staffFetch('/api/v1/admin/sla-policies')
  return decodeFirstReplySlaPolicies(response, await checkedBody(response))
}

export async function listFirstReplySlaPolicyVersions(
  policyId: string,
): Promise<FirstReplySlaPolicy[]> {
  const response = await staffFetch(
    `/api/v1/admin/sla-policies/${policyId}/versions`,
  )
  return decodeFirstReplySlaPolicies(response, await checkedBody(response))
}

export async function createFirstReplySlaPolicy(
  definition: FirstReplySlaPolicyDefinition,
): Promise<FirstReplySlaPolicy> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/sla-policies',
    'POST',
    definition,
  )
  const policy = decodeFirstReplySlaPolicy(await checkedBody(response))
  if (!policy) throw malformedSuccess(response)
  return policy
}

export async function createFirstReplySlaPolicyVersion(
  policyId: string,
  aggregateVersion: number,
  definition: FirstReplySlaPolicyDefinition,
): Promise<FirstReplySlaPolicy> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/sla-policies/${policyId}/versions`,
    'POST',
    definition,
    { 'If-Match': `"${aggregateVersion}"` },
  )
  const policy = decodeFirstReplySlaPolicy(await checkedBody(response))
  if (!policy) throw malformedSuccess(response)
  return policy
}

export async function activateFirstReplySlaPolicyVersion(
  policyId: string,
  version: number,
  aggregateVersion: number,
): Promise<FirstReplySlaPolicy> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/sla-policies/${policyId}/versions/${version}/activation`,
    'PUT',
    undefined,
    { 'If-Match': `"${aggregateVersion}"` },
  )
  const policy = decodeFirstReplySlaPolicy(await checkedBody(response))
  if (!policy) throw malformedSuccess(response)
  return policy
}

export async function previewFirstReplySlaPolicy(
  input: FirstReplySlaPreviewInput,
): Promise<FirstReplySlaPreview> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/sla-policies/preview',
    'POST',
    input,
  )
  const value = await checkedBody(response)
  if (
    !isRecord(value) ||
    typeof value.matched !== 'boolean' ||
    (value.dueAt !== null && !isTimestamp(value.dueAt)) ||
    (value.targetMinutes !== null && typeof value.targetMinutes !== 'number') ||
    (value.policyId !== null && !isCanonicalUuid(value.policyId)) ||
    (value.policyVersion !== null && typeof value.policyVersion !== 'number') ||
    (value.scheduleId !== null && !isCanonicalUuid(value.scheduleId)) ||
    (value.scheduleVersion !== null &&
      typeof value.scheduleVersion !== 'number') ||
    value.dstPolicy !== 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH'
  ) {
    throw malformedSuccess(response)
  }
  return value as unknown as FirstReplySlaPreview
}

export async function getFirstReplySlaAnalytics(): Promise<FirstReplySlaAnalytics> {
  const response = await staffFetch('/api/v1/analytics/first-reply-sla')
  const value = await checkedBody(response)
  const countFields = [
    'active',
    'paused',
    'achieved',
    'breached',
    'cancelled',
    'noPolicy',
    'achievedRateDenominator',
  ]
  if (
    !isRecord(value) ||
    value.metric !== 'FIRST_REPLY' ||
    !isNonBlankString(value.calculationVersion) ||
    !countFields.every(
      (field) =>
        typeof value[field] === 'number' && Number.isSafeInteger(value[field]),
    ) ||
    (value.achievedRate !== null && typeof value.achievedRate !== 'number')
  ) {
    throw malformedSuccess(response)
  }
  return value as unknown as FirstReplySlaAnalytics
}

export async function getCustomerAccessModeSetting(): Promise<CustomerAccessModeSetting> {
  const response = await staffFetch(
    '/api/v1/admin/settings/customer-access-mode',
  )
  const decoded = decodeCustomerAccessModeSetting(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function updateCustomerAccessModeSetting(
  input: UpdateCustomerAccessModeInput,
): Promise<CustomerAccessModeSetting> {
  const response = await unsafeStaffFetch(
    '/api/v1/admin/settings/customer-access-mode',
    'PUT',
    input,
  )
  const decoded = decodeCustomerAccessModeSetting(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

function decodeCustomerAccessModeSetting(
  value: unknown,
): CustomerAccessModeSetting | undefined {
  if (!isRecord(value)) return undefined
  if (
    ![
      'ANONYMOUS_ALLOWED',
      'REGISTRATION_OPTIONAL',
      'REGISTRATION_REQUIRED',
    ].includes(String(value.mode)) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    !isTimestamp(value.updatedAt)
  )
    return undefined
  return {
    mode: value.mode as CustomerAccessModeSetting['mode'],
    version: value.version,
    updatedAt: value.updatedAt,
  }
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isNullableTimestamp(value: unknown): value is string | null {
  return value === null || isTimestamp(value)
}

function isSafeOutboundMailCode(
  value: unknown,
  maxLength: number,
): value is string | null {
  return (
    value === null ||
    (typeof value === 'string' &&
      value.length > 0 &&
      value.length <= maxLength &&
      /^[A-Z0-9][A-Z0-9_:-]*$/.test(value))
  )
}

/**
 * The operations API promises a masked local part. Validate that promise at the
 * browser boundary so an accidental raw mailbox can never be rendered.
 */
function isSafeMaskedRecipient(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 254 &&
    /^\*+@[^\s@]+$/.test(value) &&
    !hasControlCharacter(value)
  )
}

function hasControlCharacter(value: string) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0)
    return codePoint === undefined || codePoint <= 0x1f || codePoint === 0x7f
  })
}

function decodeOutboundMailAttempt(
  value: unknown,
): OutboundMailAttempt | undefined {
  if (
    !isRecord(value) ||
    !isPositiveSafeInteger(value.attemptNumber) ||
    !isNonNegativeSafeInteger(value.retryCycle) ||
    !isPositiveSafeInteger(value.cycleAttemptNumber) ||
    typeof value.status !== 'string' ||
    !OUTBOUND_MAIL_ATTEMPT_STATUSES.has(
      value.status as OutboundMailAttemptStatus,
    ) ||
    !isSafeOutboundMailCode(value.failureClass, 40) ||
    !isSafeOutboundMailCode(value.failureCode, 80) ||
    !isTimestamp(value.startedAt) ||
    !isNullableTimestamp(value.finishedAt) ||
    !isNullableTimestamp(value.nextRetryAt)
  ) {
    return undefined
  }
  return {
    attemptNumber: value.attemptNumber,
    retryCycle: value.retryCycle,
    cycleAttemptNumber: value.cycleAttemptNumber,
    status: value.status as OutboundMailAttemptStatus,
    failureClass: value.failureClass,
    failureCode: value.failureCode,
    startedAt: value.startedAt,
    finishedAt: value.finishedAt,
    nextRetryAt: value.nextRetryAt,
  }
}

function decodeOutboundMailIntent(
  value: unknown,
): OutboundMailIntent | undefined {
  if (!isRecord(value) || !Array.isArray(value.attempts)) return undefined
  const attempts = value.attempts.map(decodeOutboundMailAttempt)
  if (
    !isCanonicalUuid(value.id) ||
    typeof value.template !== 'string' ||
    !OUTBOUND_MAIL_TEMPLATES.has(value.template as OutboundMailTemplate) ||
    !isPositiveSafeInteger(value.templateVersion) ||
    typeof value.status !== 'string' ||
    !OUTBOUND_MAIL_INTENT_STATUSES.has(
      value.status as OutboundMailIntentStatus,
    ) ||
    !isSafeMaskedRecipient(value.recipientMasked) ||
    !isNonNegativeSafeInteger(value.attemptCount) ||
    !isPositiveSafeInteger(value.maxAttempts) ||
    !isNonNegativeSafeInteger(value.retryCycle) ||
    !isNonNegativeSafeInteger(value.manualRetryCount) ||
    !isNullableTimestamp(value.nextAttemptAt) ||
    !isNullableTimestamp(value.leaseExpiresAt) ||
    !isSafeOutboundMailCode(value.lastErrorCode, 80) ||
    !isTimestamp(value.queuedAt) ||
    !isNullableTimestamp(value.sentAt) ||
    !isNullableTimestamp(value.failedAt) ||
    attempts.some((attempt) => !attempt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    template: value.template as OutboundMailTemplate,
    templateVersion: value.templateVersion,
    status: value.status as OutboundMailIntentStatus,
    recipientMasked: value.recipientMasked,
    attemptCount: value.attemptCount,
    maxAttempts: value.maxAttempts,
    retryCycle: value.retryCycle,
    manualRetryCount: value.manualRetryCount,
    nextAttemptAt: value.nextAttemptAt,
    leaseExpiresAt: value.leaseExpiresAt,
    lastErrorCode: value.lastErrorCode,
    queuedAt: value.queuedAt,
    sentAt: value.sentAt,
    failedAt: value.failedAt,
    attempts: attempts as OutboundMailAttempt[],
  }
}

function decodeOutboundMailIntentPage(
  value: unknown,
): OutboundMailIntentPage | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined
  const items = value.items.map(decodeOutboundMailIntent)
  if (
    items.some((item) => !item) ||
    (value.nextCursor !== null && typeof value.nextCursor !== 'string') ||
    (typeof value.nextCursor === 'string' &&
      (value.nextCursor.length === 0 || value.nextCursor.length > 2000))
  ) {
    return undefined
  }
  return {
    items: items as OutboundMailIntent[],
    nextCursor: value.nextCursor,
  }
}

function decodeOutboundMailOperationsSummary(
  value: unknown,
): OutboundMailOperationsSummary | undefined {
  if (
    !isRecord(value) ||
    typeof value.deliveryEnabled !== 'boolean' ||
    typeof value.schedulingEnabled !== 'boolean' ||
    (value.transport !== 'DISABLED' &&
      value.transport !== 'SMTP' &&
      value.transport !== 'FAKE') ||
    !isNonNegativeSafeInteger(value.queuedCount) ||
    !isNonNegativeSafeInteger(value.sendingCount) ||
    !isNonNegativeSafeInteger(value.retryWaitCount) ||
    !isNonNegativeSafeInteger(value.failedCount) ||
    !isNonNegativeSafeInteger(value.sentCount) ||
    !isNullableTimestamp(value.oldestPendingAt)
  ) {
    return undefined
  }
  return {
    deliveryEnabled: value.deliveryEnabled,
    schedulingEnabled: value.schedulingEnabled,
    transport: value.transport,
    queuedCount: value.queuedCount,
    sendingCount: value.sendingCount,
    retryWaitCount: value.retryWaitCount,
    failedCount: value.failedCount,
    sentCount: value.sentCount,
    oldestPendingAt: value.oldestPendingAt,
  }
}

export async function getOutboundMailSummary(): Promise<OutboundMailOperationsSummary> {
  const response = await staffFetch('/api/v1/admin/mail/summary')
  const summary = decodeOutboundMailOperationsSummary(
    await checkedBody(response),
  )
  if (!summary) throw malformedSuccess(response)
  return summary
}

export async function listOutboundMailIntents({
  cursor,
  limit = 50,
  status,
}: {
  cursor?: string
  limit?: number
  status?: OutboundMailIntentStatus
} = {}): Promise<OutboundMailIntentPage> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    throw new Error('Outbound mail list limit must be between 1 and 100')
  }
  if (cursor !== undefined && (cursor.length === 0 || cursor.length > 2000)) {
    throw new Error('Outbound mail cursor is invalid')
  }
  const search = new URLSearchParams({ limit: String(limit) })
  if (status) search.set('status', status)
  if (cursor) search.set('cursor', cursor)
  const response = await staffFetch(`/api/v1/admin/mail/intents?${search}`)
  const page = decodeOutboundMailIntentPage(await checkedBody(response))
  if (!page) throw malformedSuccess(response)
  return page
}

export async function getOutboundMailIntent(
  intentId: string,
): Promise<OutboundMailIntent> {
  const response = await staffFetch(`/api/v1/admin/mail/intents/${intentId}`)
  const intent = decodeOutboundMailIntent(await checkedBody(response))
  if (!intent) throw malformedSuccess(response)
  return intent
}

export async function retryOutboundMailIntent(
  intentId: string,
  reason: string,
): Promise<OutboundMailIntent> {
  const normalizedReason = reason.trim()
  if (!normalizedReason || normalizedReason.length > 500) {
    throw new Error(
      'Manual mail retry reason must be between 1 and 500 characters',
    )
  }
  const response = await unsafeStaffFetch(
    `/api/v1/admin/mail/intents/${intentId}/retry`,
    'POST',
    { reason: normalizedReason },
  )
  const intent = decodeOutboundMailIntent(await checkedBody(response))
  if (!intent) throw malformedSuccess(response)
  return intent
}

function decodeActorSummary(value: unknown): ActorSummary | undefined {
  if (!isRecord(value)) return undefined
  if (
    (value.id !== null && !isNonBlankString(value.id)) ||
    typeof value.type !== 'string' ||
    !ACTOR_TYPES.has(value.type as ActorSummary['type']) ||
    !isNonBlankString(value.displayName)
  ) {
    return undefined
  }
  return {
    id: value.id,
    type: value.type as ActorSummary['type'],
    displayName: value.displayName,
  }
}

function decodeGroupReference(value: unknown): GroupReference | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name)
  ) {
    return undefined
  }
  return { id: value.id, name: value.name }
}

function decodeTicketReference(
  value: unknown,
): AgentTicketSummary['assignee'] | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.displayName)
  ) {
    return undefined
  }
  return { id: value.id, displayName: value.displayName }
}

function decodeFirstReplySlaBadge(
  value: unknown,
): FirstReplySlaBadge | undefined {
  if (
    !isRecord(value) ||
    value.metric !== 'FIRST_REPLY' ||
    typeof value.state !== 'string' ||
    !FIRST_REPLY_SLA_STATES.has(value.state as FirstReplySlaBadge['state']) ||
    (value.dueAt !== null && !isTimestamp(value.dueAt)) ||
    (value.targetMinutes !== null &&
      (typeof value.targetMinutes !== 'number' ||
        !Number.isSafeInteger(value.targetMinutes))) ||
    (value.policyVersion !== null &&
      (typeof value.policyVersion !== 'number' ||
        !Number.isSafeInteger(value.policyVersion))) ||
    (value.scheduleVersion !== null &&
      (typeof value.scheduleVersion !== 'number' ||
        !Number.isSafeInteger(value.scheduleVersion)))
  ) {
    return undefined
  }
  return {
    metric: 'FIRST_REPLY',
    state: value.state as FirstReplySlaBadge['state'],
    dueAt: value.dueAt,
    targetMinutes: value.targetMinutes,
    policyVersion: value.policyVersion,
    scheduleVersion: value.scheduleVersion,
  }
}

function decodeAgentTicketSummary(
  value: unknown,
): AgentTicketSummary | undefined {
  if (!isRecord(value)) return undefined
  const requester = decodeActorSummary(value.requester)
  const group = value.group === null ? null : decodeGroupReference(value.group)
  const assignee =
    value.assignee === null ? null : decodeTicketReference(value.assignee)
  const sla = value.sla === null ? null : decodeFirstReplySlaBadge(value.sla)
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.subject) ||
    !isAgentTicketStatus(value.status) ||
    !isTicketPriority(value.priority) ||
    !requester ||
    group === undefined ||
    assignee === undefined ||
    !isTimestamp(value.updatedAt) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    typeof value.isChild !== 'boolean' ||
    typeof value.openChildCount !== 'number' ||
    !Number.isSafeInteger(value.openChildCount) ||
    sla === undefined
  ) {
    return undefined
  }
  return {
    ticketNumber: value.ticketNumber,
    subject: value.subject,
    status: value.status,
    priority: value.priority,
    requester,
    group,
    assignee,
    updatedAt: value.updatedAt,
    version: value.version,
    isChild: value.isChild,
    openChildCount: value.openChildCount,
    sla,
  }
}

function decodeSavedViewCondition(
  value: unknown,
): SavedViewCondition | undefined {
  if (
    !isRecord(value) ||
    typeof value.field !== 'string' ||
    !SAVED_VIEW_CONDITION_FIELDS.has(value.field as SavedViewConditionField) ||
    typeof value.operator !== 'string' ||
    !SAVED_VIEW_CONDITION_OPERATORS.has(
      value.operator as SavedViewConditionOperator,
    ) ||
    !Array.isArray(value.values) ||
    value.values.length > 10 ||
    !value.values.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    field: value.field as SavedViewConditionField,
    operator: value.operator as SavedViewConditionOperator,
    values: value.values,
  }
}

function decodeSavedViewConditions(
  value: unknown,
): SavedViewConditions | undefined {
  if (
    !isRecord(value) ||
    value.version !== 1 ||
    !Array.isArray(value.all) ||
    !Array.isArray(value.any) ||
    value.all.length > 12 ||
    value.any.length > 12
  ) {
    return undefined
  }
  const all = value.all.map(decodeSavedViewCondition)
  const any = value.any.map(decodeSavedViewCondition)
  if (
    all.some((condition) => !condition) ||
    any.some((condition) => !condition)
  ) {
    return undefined
  }
  return {
    version: 1,
    all: all as SavedViewCondition[],
    any: any as SavedViewCondition[],
  }
}

function decodeSavedViewDefinition(
  value: unknown,
): SavedViewDefinition | undefined {
  if (!isRecord(value) || !Array.isArray(value.columns)) return undefined
  const conditions = decodeSavedViewConditions(value.conditions)
  const description = value.description === undefined ? '' : value.description
  if (
    !isNonBlankString(value.name) ||
    typeof description !== 'string' ||
    description.length > 500 ||
    hasIsoControlCharacters(description) ||
    !conditions ||
    value.columns.length < 1 ||
    value.columns.length > 12 ||
    !value.columns.every(
      (column) => typeof column === 'string' && SAVED_VIEW_COLUMNS.has(column),
    ) ||
    new Set(value.columns).size !== value.columns.length ||
    value.sort !== 'updatedAt:desc,ticketNumber:desc'
  ) {
    return undefined
  }
  return {
    name: value.name,
    description,
    conditions,
    columns: value.columns as SavedViewDefinition['columns'],
    sort: value.sort as SavedViewSort,
  }
}

function hasIsoControlCharacters(value: string) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint <= 31 || (codePoint >= 127 && codePoint <= 159)
  })
}

function decodeSavedAgentView(value: unknown): SavedAgentView | undefined {
  if (!isRecord(value) || !Array.isArray(value.categoryPath)) return undefined
  const definition = decodeSavedViewDefinition(value)
  const ticketCountAsOf = value.ticketCountAsOf ?? null
  if (
    !definition ||
    !isUuid(value.id) ||
    !isNonBlankString(value.key) ||
    typeof value.scope !== 'string' ||
    !SAVED_VIEW_SCOPES.has(value.scope as SavedViewScope) ||
    (value.ownerStaffId !== null && !isUuid(value.ownerStaffId)) ||
    typeof value.active !== 'boolean' ||
    typeof value.definitionVersion !== 'number' ||
    !Number.isSafeInteger(value.definitionVersion) ||
    value.definitionVersion < 1 ||
    typeof value.orderVersion !== 'number' ||
    !Number.isSafeInteger(value.orderVersion) ||
    value.orderVersion < 1 ||
    !value.categoryPath.every(isNonBlankString) ||
    (value.ticketCount !== null &&
      (typeof value.ticketCount !== 'number' ||
        !Number.isSafeInteger(value.ticketCount) ||
        value.ticketCount < 0)) ||
    (value.ticketCountState !== 'EXACT' &&
      value.ticketCountState !== 'OMITTED_VISIBLE_LIMIT') ||
    (ticketCountAsOf !== null && !isTimestamp(ticketCountAsOf)) ||
    value.readScope !== 'ALL_TICKETS' ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt)
  ) {
    return undefined
  }
  return {
    ...definition,
    id: value.id,
    key: value.key,
    scope: value.scope as SavedViewScope,
    ownerStaffId: value.ownerStaffId,
    active: value.active,
    definitionVersion: value.definitionVersion,
    orderVersion: value.orderVersion,
    categoryPath: value.categoryPath,
    ticketCount: value.ticketCount,
    ticketCountState: value.ticketCountState,
    ticketCountAsOf,
    readScope: 'ALL_TICKETS',
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

function decodeAgentTicketPage(value: unknown): AgentTicketPage | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined
  const items = value.items.map(decodeAgentTicketSummary)
  if (
    items.some((ticket) => !ticket) ||
    (value.nextCursor !== null && !isNonBlankString(value.nextCursor)) ||
    (value.totalApproximate !== null &&
      (typeof value.totalApproximate !== 'number' ||
        !Number.isSafeInteger(value.totalApproximate) ||
        value.totalApproximate < 0)) ||
    value.sort !== 'updatedAt:desc,ticketNumber:desc'
  ) {
    return undefined
  }
  return {
    items: items as AgentTicketSummary[],
    nextCursor: value.nextCursor,
    totalApproximate: value.totalApproximate,
    sort: 'updatedAt:desc,ticketNumber:desc',
  }
}

function decodeSavedViewPreview(value: unknown): SavedViewPreview | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined
  const items = value.items.map(decodeAgentTicketSummary)
  if (
    items.some((ticket) => !ticket) ||
    typeof value.ticketCount !== 'number' ||
    !Number.isSafeInteger(value.ticketCount) ||
    value.ticketCount < 0 ||
    !isTimestamp(value.ticketCountAsOf) ||
    value.sort !== 'updatedAt:desc,ticketNumber:desc'
  ) {
    return undefined
  }
  return {
    items: items as AgentTicketSummary[],
    ticketCount: value.ticketCount,
    ticketCountAsOf: value.ticketCountAsOf,
    sort: 'updatedAt:desc,ticketNumber:desc',
  }
}

function decodeSavedViewOrder(value: unknown): SavedViewOrder | undefined {
  if (
    !isRecord(value) ||
    (value.scope !== 'PERSONAL' && value.scope !== 'SHARED') ||
    typeof value.orderVersion !== 'number' ||
    !Number.isSafeInteger(value.orderVersion) ||
    value.orderVersion < 1 ||
    !Array.isArray(value.viewKeys) ||
    value.viewKeys.some((key) => !isNonBlankString(key))
  ) {
    return undefined
  }
  return {
    scope: value.scope,
    orderVersion: value.orderVersion,
    viewKeys: value.viewKeys,
  }
}

function decodeAgentComment(value: unknown): AgentComment | undefined {
  if (!isRecord(value) || !Array.isArray(value.attachments)) return undefined
  const actor = decodeActorSummary(value.actor)
  const attachments = value.attachments.map(decodeTicketAttachment)
  if (
    !isNonBlankString(value.id) ||
    !isTicketVisibility(value.visibility) ||
    !actor ||
    !isNonBlankString(value.body) ||
    !isTimestamp(value.createdAt) ||
    !isNonBlankString(value.source) ||
    attachments.some((attachment) => !attachment)
  ) {
    return undefined
  }
  return {
    id: value.id,
    visibility: value.visibility,
    actor,
    body: value.body,
    createdAt: value.createdAt,
    source: value.source,
    attachments: attachments as TicketAttachment[],
  }
}

function decodeHistory(value: unknown): TicketHistoryItem | undefined {
  if (!isRecord(value)) return undefined
  const actor = decodeActorSummary(value.actor)
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.eventType) ||
    !actor ||
    !isTimestamp(value.occurredAt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    eventType: value.eventType,
    actor,
    occurredAt: value.occurredAt,
  }
}

function decodeAgentTicketDetail(
  value: unknown,
): AgentTicketDetail | undefined {
  if (
    !isRecord(value) ||
    !Array.isArray(value.comments) ||
    !Array.isArray(value.capabilities) ||
    !isRecord(value.assignmentOptions) ||
    !isRecord(value.context) ||
    !Array.isArray(value.history) ||
    !Array.isArray(value.warnings)
  ) {
    return undefined
  }
  const ticket = decodeAgentTicketSummary(value.ticket)
  const comments = value.comments.map(decodeAgentComment)
  const customerValue = value.context.customer
  const customer =
    customerValue === null
      ? null
      : isRecord(customerValue) &&
          isNonBlankString(customerValue.id) &&
          isNonBlankString(customerValue.displayName) &&
          isNonBlankString(customerValue.email)
        ? {
            id: customerValue.id,
            displayName: customerValue.displayName,
            email: customerValue.email,
          }
        : undefined
  const parent =
    value.context.parent === null
      ? null
      : decodeAgentTicketSummary(value.context.parent)
  const children = Array.isArray(value.context.children)
    ? value.context.children.map(decodeAgentTicketSummary)
    : []
  const history = value.history.map(decodeHistory)
  const assignmentOptions = decodeTicketAssignmentOptions(
    value.assignmentOptions,
  )
  if (
    !ticket ||
    comments.some((comment) => !comment) ||
    customer === undefined ||
    parent === undefined ||
    !Array.isArray(value.context.children) ||
    children.some((child) => !child) ||
    typeof value.context.externalReferenceCount !== 'number' ||
    !Number.isSafeInteger(value.context.externalReferenceCount) ||
    value.context.externalReferenceCount < 0 ||
    history.some((item) => !item) ||
    !assignmentOptions ||
    !value.capabilities.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    ticket,
    comments: comments as AgentComment[],
    capabilities: value.capabilities,
    assignmentOptions,
    context: {
      customer,
      parent,
      children: children as AgentTicketSummary[],
      externalReferenceCount: value.context.externalReferenceCount,
    },
    history: history as TicketHistoryItem[],
    warnings: [...value.warnings],
  }
}

function decodeTicketAssignmentOptions(
  value: Record<string, unknown>,
): TicketAssignmentOptions | undefined {
  if (!Array.isArray(value.groups)) return undefined
  const groups = value.groups.map(decodeTicketAssignmentGroupOption)
  if (groups.some((group) => !group)) return undefined
  return { groups: groups as TicketAssignmentGroupOption[] }
}

function decodeTicketAssignmentGroupOption(
  value: unknown,
): TicketAssignmentGroupOption | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name) ||
    !Array.isArray(value.members)
  ) {
    return undefined
  }
  const members = value.members.flatMap((member) => {
    if (
      !isRecord(member) ||
      !isNonBlankString(member.id) ||
      !isNonBlankString(member.displayName)
    ) {
      return []
    }
    return [{ id: member.id, displayName: member.displayName }]
  })
  if (members.length !== value.members.length) return undefined
  return { id: value.id, name: value.name, members }
}

function decodeTicketCommandResult(
  value: unknown,
): TicketCommandResult | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.ticketNumber) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    !isNonBlankString(value.auditId) ||
    !Array.isArray(value.warnings)
  ) {
    return undefined
  }
  const warnings = value.warnings.flatMap((warning) => {
    if (
      !isRecord(warning) ||
      !isNonBlankString(warning.code) ||
      !isNonBlankString(warning.message) ||
      typeof warning.count !== 'number' ||
      !Number.isSafeInteger(warning.count) ||
      warning.count < 1 ||
      !Array.isArray(warning.relatedTicketNumbers) ||
      !warning.relatedTicketNumbers.every(isTicketNumber) ||
      warning.count !== warning.relatedTicketNumbers.length
    ) {
      return []
    }
    return [
      {
        code: warning.code,
        message: warning.message,
        count: warning.count,
        relatedTicketNumbers: warning.relatedTicketNumbers,
      },
    ]
  })
  if (warnings.length !== value.warnings.length) return undefined
  return {
    ticketNumber: value.ticketNumber,
    version: value.version,
    auditId: value.auditId,
    warnings,
  }
}

function decodeAgentTicketBatchItemResult(
  value: unknown,
): AgentTicketBatchItemResult | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.clientCommandId) ||
    ![
      'SUCCEEDED',
      'CONFLICT',
      'DENIED',
      'NOT_FOUND',
      'VALIDATION_FAILED',
    ].includes(String(value.outcome)) ||
    typeof value.replayed !== 'boolean' ||
    (value.resultVersion !== null &&
      (typeof value.resultVersion !== 'number' ||
        !Number.isSafeInteger(value.resultVersion) ||
        value.resultVersion < 0)) ||
    (value.auditId !== null && !isUuid(value.auditId)) ||
    (value.code !== null &&
      ![
        'TICKET_FIELD_CONFLICT',
        'VERSION_PRECONDITION_FAILED',
        'CLIENT_COMMAND_ID_REUSED',
        'TICKET_WRITE_FORBIDDEN',
        'TICKET_NOT_FOUND',
        'VALIDATION_FAILED',
      ].includes(String(value.code)))
  ) {
    return undefined
  }
  return {
    ticketNumber: value.ticketNumber,
    clientCommandId: value.clientCommandId,
    outcome: value.outcome as AgentTicketBatchItemResult['outcome'],
    replayed: value.replayed,
    resultVersion: value.resultVersion,
    auditId: value.auditId,
    code: value.code as AgentTicketBatchItemResult['code'],
  }
}

function decodeAgentTicketBatchResult(
  value: unknown,
): AgentTicketBatchResult | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.correlationId) ||
    !Array.isArray(value.results)
  ) {
    return undefined
  }
  const results = value.results.map(decodeAgentTicketBatchItemResult)
  if (
    results.length < 1 ||
    results.length > 100 ||
    results.some((result) => !result)
  ) {
    return undefined
  }
  return {
    correlationId: value.correlationId,
    results: results as AgentTicketBatchItemResult[],
  }
}

function decodeCreateChildTicketResult(
  value: unknown,
): CreateChildTicketResult | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.parentTicketNumber) ||
    typeof value.parentVersion !== 'number' ||
    !Number.isSafeInteger(value.parentVersion) ||
    value.parentVersion < 0 ||
    !isTicketNumber(value.childTicketNumber) ||
    !isNonBlankString(value.parentAuditId) ||
    !isNonBlankString(value.childAuditId)
  ) {
    return undefined
  }
  return {
    parentTicketNumber: value.parentTicketNumber,
    parentVersion: value.parentVersion,
    childTicketNumber: value.childTicketNumber,
    parentAuditId: value.parentAuditId,
    childAuditId: value.childAuditId,
  }
}

function decodeCustomerSummary(value: unknown): CustomerSummary | undefined {
  if (
    !isRecord(value) ||
    !isUuid(value.id) ||
    !isNonBlankString(value.name) ||
    !isNonBlankString(value.email) ||
    typeof value.verified !== 'boolean'
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    email: value.email,
    verified: value.verified,
  }
}

export async function listAgentViews(): Promise<SavedAgentView[]> {
  const response = await staffFetch('/api/v1/agent/views')
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const views = body.map(decodeSavedAgentView)
  if (views.some((view) => !view)) throw malformedSuccess(response)
  return views as SavedAgentView[]
}

export async function createAgentSavedView(
  input: CreateSavedViewInput,
): Promise<SavedAgentView> {
  const response = await unsafeStaffFetch('/api/v1/agent/views', 'POST', input)
  const decoded = decodeSavedAgentView(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function previewAgentSavedView(
  input: SavedViewDefinition,
  interactionId: string,
): Promise<SavedViewPreview> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/views/preview',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const decoded = decodeSavedViewPreview(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function reorderAgentSavedViews(
  input: ReorderSavedViewsInput,
): Promise<SavedViewOrder> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/views/reorder',
    'POST',
    input,
  )
  const decoded = decodeSavedViewOrder(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function updateAgentSavedView(
  viewKey: string,
  input: UpdateSavedViewInput,
): Promise<SavedAgentView> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/views/${encodeURIComponent(viewKey)}`,
    'PATCH',
    input,
  )
  const decoded = decodeSavedAgentView(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function deleteAgentSavedView(
  viewKey: string,
  expectedVersion: number,
): Promise<void> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/views/${encodeURIComponent(viewKey)}`,
    'DELETE',
    undefined,
    { 'If-Match': `"${expectedVersion}"` },
  )
  await checkedEmpty(response)
}

export async function listTicketsInView(
  viewKey: string,
  filters: AgentTicketFilters = {},
): Promise<AgentTicketPage> {
  const search = new URLSearchParams()
  if (filters.status) search.set('status', filters.status)
  if (filters.priority) search.set('priority', filters.priority)
  if (filters.groupId) search.set('groupId', filters.groupId)
  if (filters.assigneeId) search.set('assigneeId', filters.assigneeId)
  if (filters.slaState) search.set('slaState', filters.slaState)
  if (filters.cursor) search.set('cursor', filters.cursor)
  if (filters.limit) search.set('limit', String(filters.limit))
  const query = search.size ? `?${search.toString()}` : ''
  const response = await staffFetch(
    `/api/v1/agent/views/${encodeURIComponent(viewKey)}/tickets${query}`,
  )
  const decoded = decodeAgentTicketPage(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function searchAgentTickets(
  input: AgentTicketSearchInput,
  interactionId: string,
): Promise<AgentTicketSearchPage> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/search',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const body = await checkedBody(response)
  if (!isRecord(body) || !Array.isArray(body.items)) {
    throw malformedSuccess(response)
  }
  const items = body.items.map(decodeAgentTicketSummary)
  if (
    !isUuid(body.searchEventId) ||
    !isUuid(body.searchInteractionId) ||
    items.some((ticket) => !ticket) ||
    typeof body.resultCount !== 'number' ||
    !Number.isSafeInteger(body.resultCount) ||
    body.resultCount < 0 ||
    typeof body.sort !== 'string' ||
    !AGENT_TICKET_SEARCH_SORTS.has(body.sort) ||
    (body.nextCursor !== null && !isNonBlankString(body.nextCursor))
  ) {
    throw malformedSuccess(response)
  }
  return {
    searchEventId: body.searchEventId,
    searchInteractionId: body.searchInteractionId,
    items: items as AgentTicketSummary[],
    resultCount: body.resultCount,
    sort: body.sort as AgentTicketSearchPage['sort'],
    nextCursor: body.nextCursor,
  }
}

export async function getAgentTicket(
  ticketNumber: number,
  interactionId: string,
  intent: AgentReadIntent,
  originSearchEventId?: string,
): Promise<AgentTicketDetail> {
  const response = await staffFetch(`/api/v1/agent/tickets/${ticketNumber}`, {
    headers: {
      'X-Interaction-Id': interactionId,
      'X-Deskseed-Read-Intent': intent,
      ...(originSearchEventId
        ? { 'X-Origin-Search-Event-Id': originSearchEventId }
        : {}),
    },
  })
  const detail = decodeAgentTicketDetail(await checkedBody(response))
  if (!detail) throw malformedSuccess(response)
  return detail
}

export async function uploadAgentAttachment(
  file: File,
): Promise<AttachmentUpload> {
  const form = new FormData()
  form.append('file', file, file.name)
  const response = await unsafeStaffMultipartFetch(
    '/api/v1/agent/attachments/uploads',
    form,
  )
  const decoded = decodeAttachmentUpload(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function downloadAgentAttachment(
  attachmentId: string,
  interactionId: string,
): Promise<AttachmentDownload> {
  const response = await checkedBinary(
    await staffFetch(
      `/api/v1/agent/attachments/${encodeURIComponent(attachmentId)}/download`,
      { headers: { 'X-Interaction-Id': interactionId } },
    ),
  )
  const contentType = responseContentType(response)
  if (!contentType) throw malformedSuccess(response)
  return {
    content: await response.blob(),
    contentType,
    fileName: responseFileName(response),
  }
}

export async function updateAgentTicket(
  ticketNumber: number,
  command: UpdateTicketCommand,
): Promise<TicketCommandResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/commands`,
    'POST',
    command,
  )
  const result = decodeTicketCommandResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function transferAgentTicket(
  ticketNumber: number,
  command: TransferTicketCommand,
): Promise<TicketCommandResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/transfer`,
    'POST',
    command,
    { 'If-Match': `"${command.expectedVersion}"` },
  )
  const result = decodeTicketCommandResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function executeAgentTicketBatch(
  command: AgentTicketBatchCommand,
): Promise<AgentTicketBatchResult> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/tickets/batch-commands',
    'POST',
    command,
  )
  const decoded = decodeAgentTicketBatchResult(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createChildTicket(
  ticketNumber: number,
  command: CreateChildTicketCommand,
): Promise<CreateChildTicketResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/children`,
    'POST',
    command,
    { 'If-Match': `"${command.expectedVersion}"` },
  )
  const result = decodeCreateChildTicketResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function createAgentTicket(
  command: CreateAgentTicketCommand,
): Promise<TicketCommandResult> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/tickets',
    'POST',
    command,
  )
  const result = decodeTicketCommandResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function searchAgentCustomers(
  input: AgentCustomerSearchInput,
  interactionId: string,
): Promise<AgentCustomerSearchPage> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/customers/search',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const body = await checkedBody(response)
  if (!isRecord(body) || !Array.isArray(body.items)) {
    throw malformedSuccess(response)
  }
  const items = body.items.map(decodeCustomerSummary)
  if (
    !isUuid(body.searchEventId) ||
    !isUuid(body.searchInteractionId) ||
    items.some((customer) => !customer) ||
    typeof body.resultCount !== 'number' ||
    !Number.isSafeInteger(body.resultCount) ||
    body.resultCount < 0
  ) {
    throw malformedSuccess(response)
  }
  return {
    searchEventId: body.searchEventId,
    searchInteractionId: body.searchInteractionId,
    items: items as CustomerSummary[],
    resultCount: body.resultCount,
  }
}

export async function listTicketAssignmentOptions(): Promise<TicketAssignmentOptions> {
  const response = await staffFetch('/api/v1/agent/assignment-options')
  const body = await checkedBody(response)
  if (!isRecord(body)) throw malformedSuccess(response)
  const decoded = decodeTicketAssignmentOptions(body)
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function listTicketExternalReferences(
  ticketNumber: number,
  interactionId: string,
): Promise<ExternalReferenceContext> {
  const response = await staffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/external-references`,
    { headers: { 'X-Interaction-Id': interactionId } },
  )
  const decoded = decodeExternalReferenceContext(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createTicketExternalReference(
  ticketNumber: number,
  input: CreateExternalReferenceInput,
): Promise<ExternalReferenceCommandResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/external-references`,
    'POST',
    input,
    { 'If-Match': `"${input.expectedVersion}"` },
  )
  const decoded = decodeExternalReferenceCommand(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function deleteTicketExternalReference(
  ticketNumber: number,
  referenceId: string,
  expectedVersion: number,
): Promise<{ ticketVersion: number; removedReferenceId: string }> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/external-references/${referenceId}`,
    'DELETE',
    undefined,
    { 'If-Match': `"${expectedVersion}"` },
  )
  const body = await checkedBody(response)
  if (
    !isRecord(body) ||
    typeof body.ticketVersion !== 'number' ||
    !Number.isSafeInteger(body.ticketVersion) ||
    body.ticketVersion < 0 ||
    !isUuid(body.removedReferenceId)
  ) {
    throw malformedSuccess(response)
  }
  return {
    ticketVersion: body.ticketVersion,
    removedReferenceId: body.removedReferenceId,
  }
}

const AUDIT_LEDGERS = new Set([
  'TICKET_CHANGE',
  'ACCESS_SEARCH',
  'ADMIN_SECURITY',
])
const AUDIT_OUTCOMES = new Set(['SUCCEEDED', 'DENIED', 'FAILED'])
const AUDIT_PROJECTION_STATES = new Set(['CURRENT', 'DEGRADED', 'REBUILDING'])

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function decodeAuditActivity(value: unknown): AuditActivity | undefined {
  if (!isRecord(value)) return undefined
  const actor = decodeActorSummary(value.actor)
  if (
    !isCanonicalUuid(value.id) ||
    typeof value.ledger !== 'string' ||
    !AUDIT_LEDGERS.has(value.ledger) ||
    !isNonBlankString(value.action) ||
    !actor ||
    !isTimestamp(value.occurredAt) ||
    (value.ticketNumber !== null && !isTicketNumber(value.ticketNumber)) ||
    !isNullableString(value.groupId) ||
    !isNullableString(value.field) ||
    !isNullableString(value.resourceType) ||
    !isNullableString(value.resourceId) ||
    !isNonBlankString(value.summary) ||
    !isNonBlankString(value.source) ||
    typeof value.outcome !== 'string' ||
    !AUDIT_OUTCOMES.has(value.outcome) ||
    !isNullableString(value.requestId) ||
    !isNullableString(value.correlationId) ||
    typeof value.protectedContentAvailable !== 'boolean' ||
    !isNullableString(value.searchFingerprint)
  ) {
    return undefined
  }
  return {
    id: value.id,
    ledger: value.ledger as AuditActivity['ledger'],
    action: value.action,
    actor,
    occurredAt: value.occurredAt,
    ticketNumber: value.ticketNumber,
    groupId: value.groupId,
    field: value.field,
    resourceType: value.resourceType,
    resourceId: value.resourceId,
    summary: value.summary,
    source: value.source,
    outcome: value.outcome as AuditActivity['outcome'],
    requestId: value.requestId,
    correlationId: value.correlationId,
    protectedContentAvailable: value.protectedContentAvailable,
    searchFingerprint: value.searchFingerprint,
  }
}

function decodeAuditProjectionStatus(
  value: unknown,
): AuditProjectionStatus | undefined {
  if (
    !isRecord(value) ||
    typeof value.state !== 'string' ||
    !AUDIT_PROJECTION_STATES.has(value.state) ||
    typeof value.projectedCount !== 'number' ||
    !Number.isSafeInteger(value.projectedCount) ||
    value.projectedCount < 0 ||
    (value.lastRebuiltAt !== null && !isTimestamp(value.lastRebuiltAt))
  ) {
    return undefined
  }
  return {
    state: value.state as AuditProjectionStatus['state'],
    projectedCount: value.projectedCount,
    lastRebuiltAt: value.lastRebuiltAt,
  }
}

function decodeAuditSearchContext(
  value: unknown,
): AuditSearchContext | null | undefined {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !isNonBlankString(value.queryRedacted) ||
    !isNonBlankString(value.queryFingerprint) ||
    !isRecord(value.filters) ||
    !Object.values(value.filters).every((item) => typeof item === 'string') ||
    !isNullableString(value.sort) ||
    typeof value.resultCount !== 'number' ||
    !Number.isSafeInteger(value.resultCount) ||
    value.resultCount < 0 ||
    !isNullableString(value.originSearchActivityId) ||
    typeof value.openedActivityCount !== 'number' ||
    !Number.isSafeInteger(value.openedActivityCount) ||
    value.openedActivityCount < 0 ||
    typeof value.openedActivitiesTruncated !== 'boolean' ||
    !Array.isArray(value.openedActivities)
  ) {
    return undefined
  }
  const openedActivities = value.openedActivities.flatMap((item) => {
    if (
      !isRecord(item) ||
      !isCanonicalUuid(item.activityId) ||
      !isTicketNumber(item.ticketNumber) ||
      !isTimestamp(item.occurredAt)
    ) {
      return []
    }
    return [
      {
        activityId: item.activityId,
        ticketNumber: item.ticketNumber,
        occurredAt: item.occurredAt,
      },
    ]
  })
  if (openedActivities.length !== value.openedActivities.length)
    return undefined
  return {
    queryRedacted: value.queryRedacted,
    queryFingerprint: value.queryFingerprint,
    filters: value.filters as Record<string, string>,
    sort: value.sort,
    resultCount: value.resultCount,
    originSearchActivityId: value.originSearchActivityId,
    openedActivityCount: value.openedActivityCount,
    openedActivitiesTruncated: value.openedActivitiesTruncated,
    openedActivities,
  }
}

function decodeAuditActivityPage(
  value: unknown,
): AuditActivityPage | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined
  const items = value.items.map(decodeAuditActivity)
  const projection = decodeAuditProjectionStatus(value.projection)
  if (
    items.some((item) => !item) ||
    !isNullableString(value.nextCursor) ||
    !isTimestamp(value.snapshotAt) ||
    !projection
  ) {
    return undefined
  }
  return {
    items: items as AuditActivity[],
    nextCursor: value.nextCursor,
    snapshotAt: value.snapshotAt,
    projection,
  }
}

function decodeAuditActivityDetail(
  value: unknown,
): AuditActivityDetail | undefined {
  const activity = decodeAuditActivity(value)
  if (!activity || !isRecord(value)) return undefined
  const search = decodeAuditSearchContext(value.search)
  const fieldChange = value.fieldChange
  if (
    !isCanonicalUuid(value.canonicalEventId) ||
    !isNullableString(value.canonicalParentId) ||
    (fieldChange !== null &&
      (!isRecord(fieldChange) || !isNonBlankString(fieldChange.field))) ||
    !isNullableString(value.interactionId) ||
    !isNullableString(value.sessionFingerprint) ||
    !isNullableString(value.authType) ||
    !isNullableString(value.ipAddress) ||
    !isNullableString(value.userAgent) ||
    search === undefined ||
    !isRecord(value.metadata)
  ) {
    return undefined
  }
  return {
    ...activity,
    canonicalEventId: value.canonicalEventId,
    canonicalParentId: value.canonicalParentId,
    fieldChange:
      fieldChange === null
        ? null
        : {
            field: fieldChange.field as string,
            before: fieldChange.before,
            after: fieldChange.after,
          },
    interactionId: value.interactionId,
    sessionFingerprint: value.sessionFingerprint,
    authType: value.authType,
    ipAddress: value.ipAddress,
    userAgent: value.userAgent,
    search,
    metadata: value.metadata,
  }
}

function decodeSearchQueryRevealResult(
  value: unknown,
): SearchQueryRevealResult | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.activityId) ||
    !['AVAILABLE', 'RETENTION_EXPIRED', 'KEY_UNAVAILABLE'].includes(
      String(value.state),
    ) ||
    !isNullableString(value.rawQuery) ||
    !isNullableString(value.keyVersion) ||
    (value.revealedAt !== null && !isTimestamp(value.revealedAt))
  ) {
    return undefined
  }
  return {
    activityId: value.activityId,
    state: value.state as SearchQueryRevealResult['state'],
    rawQuery: value.rawQuery,
    keyVersion: value.keyVersion,
    revealedAt: value.revealedAt,
  }
}

function decodeAuditExportArtifact(
  value: unknown,
): AuditExportArtifact | undefined {
  if (
    !isRecord(value) ||
    typeof value.state !== 'string' ||
    !AUDIT_EXPORT_ARTIFACT_STATES.has(value.state) ||
    (value.rowCount !== null &&
      (typeof value.rowCount !== 'number' ||
        !Number.isSafeInteger(value.rowCount) ||
        value.rowCount < 0)) ||
    (value.sizeBytes !== null &&
      (typeof value.sizeBytes !== 'number' ||
        !Number.isSafeInteger(value.sizeBytes) ||
        value.sizeBytes < 0)) ||
    (value.checksumSha256 !== null &&
      (typeof value.checksumSha256 !== 'string' ||
        !/^[0-9a-f]{64}$/.test(value.checksumSha256))) ||
    (value.expiresAt !== null && !isTimestamp(value.expiresAt)) ||
    (value.contentType !== null &&
      (typeof value.contentType !== 'string' ||
        !AUDIT_EXPORT_CONTENT_TYPES.has(value.contentType))) ||
    (value.failureCode !== null &&
      !['GENERATION_FAILED', 'ARTIFACT_STORE_UNAVAILABLE', 'EXPIRED'].includes(
        String(value.failureCode),
      ))
  ) {
    return undefined
  }
  return {
    state: value.state as AuditExportArtifact['state'],
    rowCount: value.rowCount,
    sizeBytes: value.sizeBytes,
    checksumSha256: value.checksumSha256,
    expiresAt: value.expiresAt,
    contentType: value.contentType as AuditExportArtifact['contentType'],
    failureCode: value.failureCode as AuditExportArtifact['failureCode'],
  }
}

function decodeAuditExportJob(value: unknown): AuditExportJob | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.id) ||
    typeof value.status !== 'string' ||
    !AUDIT_EXPORT_STATUSES.has(value.status) ||
    !isTimestamp(value.createdAt) ||
    !['CSV', 'JSONL'].includes(String(value.format)) ||
    !Array.isArray(value.fields) ||
    !value.fields.every(isNonBlankString)
  ) {
    return undefined
  }
  const artifact = decodeAuditExportArtifact(value.artifact)
  if (!artifact) return undefined
  return {
    id: value.id,
    status: value.status as AuditExportJob['status'],
    createdAt: value.createdAt,
    format: value.format as AuditExportJob['format'],
    fields: value.fields,
    artifact,
  }
}

function appendAuditFilters(
  search: URLSearchParams,
  filters: AuditActivityFilters,
) {
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, String(value))
  })
}

export async function listAuditActivities(
  filters: AuditActivityFilters,
  cursor: string | null,
  interactionId: string,
): Promise<AuditActivityPage> {
  const search = new URLSearchParams()
  appendAuditFilters(search, filters)
  if (cursor) search.set('cursor', cursor)
  const response = await staffFetch(`/api/v1/audit/activities?${search}`, {
    headers: { 'X-Interaction-Id': interactionId },
  })
  const decoded = decodeAuditActivityPage(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function getAuditActivity(
  activityId: string,
  interactionId: string,
): Promise<AuditActivityDetail> {
  const response = await staffFetch(
    `/api/v1/audit/activities/${encodeURIComponent(activityId)}`,
    { headers: { 'X-Interaction-Id': interactionId } },
  )
  const decoded = decodeAuditActivityDetail(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function revealAuditSearchQuery(
  activityId: string,
  reason: string,
  interactionId: string,
): Promise<SearchQueryRevealResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/audit/activities/${encodeURIComponent(activityId)}/search-query-reveal`,
    'POST',
    { reason },
    { 'X-Interaction-Id': interactionId },
  )
  const decoded = decodeSearchQueryRevealResult(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createAuditExport(
  input: CreateAuditExportInput,
  interactionId: string,
): Promise<AuditExportJob> {
  const response = await unsafeStaffFetch(
    '/api/v1/audit/exports',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const decoded = decodeAuditExportJob(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function getAuditExport(
  jobId: string,
  interactionId: string,
): Promise<AuditExportJob> {
  const response = await staffFetch(
    `/api/v1/audit/exports/${encodeURIComponent(jobId)}`,
    { headers: { 'X-Interaction-Id': interactionId } },
  )
  const decoded = decodeAuditExportJob(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function downloadAuditExport(
  jobId: string,
  interactionId: string,
): Promise<AuditExportDownload> {
  const response = await checkedBinary(
    await staffFetch(
      `/api/v1/audit/exports/${encodeURIComponent(jobId)}/download`,
      { headers: { 'X-Interaction-Id': interactionId } },
    ),
  )
  const contentType = responseContentType(response)
  const checksumSha256 = response.headers.get('X-Content-Checksum-SHA256')
  if (
    !contentType ||
    !AUDIT_EXPORT_CONTENT_TYPES.has(contentType) ||
    !checksumSha256 ||
    !/^[0-9a-f]{64}$/.test(checksumSha256)
  ) {
    throw malformedSuccess(response)
  }
  return {
    content: await response.blob(),
    contentType: contentType as AuditExportDownload['contentType'],
    fileName: responseFileName(response),
    checksumSha256,
  }
}

export async function rebuildAuditProjection(
  interactionId: string,
): Promise<AuditProjectionRebuildResult> {
  const response = await unsafeStaffFetch(
    '/api/v1/audit/projection/rebuild',
    'POST',
    undefined,
    { 'X-Interaction-Id': interactionId },
  )
  const body = await checkedBody(response)
  if (!isRecord(body)) throw malformedSuccess(response)
  const projection = decodeAuditProjectionStatus(body.projection)
  if (
    !projection ||
    ![
      'ticketChangeCount',
      'accessSearchCount',
      'adminSecurityCount',
      'totalCount',
    ].every(
      (key) =>
        typeof body[key] === 'number' &&
        Number.isSafeInteger(body[key]) &&
        (body[key] as number) >= 0,
    ) ||
    !isTimestamp(body.completedAt)
  ) {
    throw malformedSuccess(response)
  }
  return {
    ticketChangeCount: body.ticketChangeCount as number,
    accessSearchCount: body.accessSearchCount as number,
    adminSecurityCount: body.adminSecurityCount as number,
    totalCount: body.totalCount as number,
    completedAt: body.completedAt,
    projection,
  }
}
