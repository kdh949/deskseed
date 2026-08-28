import type { SubmitRequestInput } from '../../api/types'

export type RequestField = keyof SubmitRequestInput
export type RequestFieldErrors = Partial<Record<RequestField, string>>

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export const REQUEST_FIELD_LIMITS = {
  name: 100,
  email: 254,
  subject: 200,
  message: 20_000,
} as const

export const EMPTY_REQUEST_FORM: SubmitRequestInput = {
  name: '',
  email: '',
  subject: '',
  message: '',
}

export function validateRequestForm(
  form: SubmitRequestInput,
): RequestFieldErrors {
  const errors: RequestFieldErrors = {}

  if (!form.name.trim()) errors.name = '이름을 입력해 주세요.'
  else if (form.name.length > REQUEST_FIELD_LIMITS.name)
    errors.name = `이름은 ${REQUEST_FIELD_LIMITS.name}자 이하여야 합니다.`

  if (!form.email.trim()) errors.email = '이메일을 입력해 주세요.'
  else if (
    form.email.length > REQUEST_FIELD_LIMITS.email ||
    !EMAIL_PATTERN.test(form.email)
  )
    errors.email = '올바른 이메일 주소를 입력해 주세요.'

  if (!form.subject.trim()) errors.subject = '제목을 입력해 주세요.'
  else if (form.subject.length > REQUEST_FIELD_LIMITS.subject)
    errors.subject = `제목은 ${REQUEST_FIELD_LIMITS.subject}자 이하여야 합니다.`

  if (!form.message.trim()) errors.message = '문의 내용을 입력해 주세요.'
  else if (form.message.length > REQUEST_FIELD_LIMITS.message)
    errors.message = `문의 내용은 ${REQUEST_FIELD_LIMITS.message.toLocaleString()}자 이하여야 합니다.`

  return errors
}

export function isRequestField(value: string): value is RequestField {
  return ['name', 'email', 'subject', 'message'].includes(value)
}
