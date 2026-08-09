export type TicketStatus =
  'NEW' | 'OPEN' | 'PENDING' | 'ON_HOLD' | 'SOLVED' | 'CLOSED'

export type CommentAuthorType = 'CUSTOMER' | 'AGENT' | 'SYSTEM' | 'AUTOMATION'

export interface SubmitRequestInput {
  name: string
  email: string
  subject: string
  message: string
}

export interface SubmittedRequest {
  ticketNumber: number
  accessToken: string
  createdAt: string
}

export interface PublicComment {
  id: string
  authorType: CommentAuthorType
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
  requestId?: string
  errors?: Record<string, string>
}
