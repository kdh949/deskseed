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

export type CommentTextMark =
  | { type: 'bold' | 'italic' | 'underline' | 'code' }
  | { type: 'link'; attrs: { href: string } }

export type CommentInlineNode =
  | { type: 'text'; text: string; marks?: CommentTextMark[] }
  | { type: 'hardBreak' }

export type CommentBlockNode =
  | {
      type: 'paragraph'
      attrs?: { textAlign?: 'left' | 'center' | 'right' }
      content?: CommentInlineNode[]
    }
  | {
      type: 'heading'
      attrs: { level: 1 | 2 | 3; textAlign?: 'left' | 'center' | 'right' }
      content?: CommentInlineNode[]
    }
  | {
      type: 'bulletList' | 'orderedList'
      content: Array<{ type: 'listItem'; content: CommentBlockNode[] }>
    }
  | { type: 'blockquote'; content: CommentBlockNode[] }
  | { type: 'codeBlock'; content?: Array<{ type: 'text'; text: string }> }
  | { type: 'attachmentImage'; attrs: { attachmentId: string; alt: string } }

export type CommentContent =
  | { format: 'PLAIN_TEXT'; text: string }
  | {
      format: 'RICH_TEXT_V1'
      document: { type: 'doc'; content: CommentBlockNode[] }
    }

export interface PublicComment {
  id: string
  authorDisplayName: string
  body: string
  content: CommentContent
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
