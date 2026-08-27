export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'

export type CustomerAccessMode =
  'ANONYMOUS_ALLOWED' | 'REGISTRATION_OPTIONAL' | 'REGISTRATION_REQUIRED'

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

export interface TicketAttachment {
  id: string
  fileName: string
  sizeBytes: number
  contentType: string
}

export interface AttachmentUpload extends TicketAttachment {
  scanStatus: 'CLEAN'
  expiresAt: string
}

export interface AttachmentDownload {
  content: Blob
  contentType: string
  fileName: string | null
}

export interface PublicComment {
  id: string
  authorDisplayName: string
  body: string
  createdAt: string
  attachments: TicketAttachment[]
}

export interface PublicRequest {
  ticketNumber: number
  subject: string
  status: TicketStatus
  createdAt: string
  updatedAt: string
  comments: PublicComment[]
}

export interface FieldError {
  field: string
  message: string
  code?: string
}

export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  requestId?: string
  fieldErrors?: FieldError[]
}
