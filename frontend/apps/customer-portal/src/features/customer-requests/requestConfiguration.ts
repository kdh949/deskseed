import { ApiError, requestCustomerConfiguration } from '../../api/client'
import type { CustomerFieldValue } from '../../api/types'

export interface CustomerFormField {
  field: {
    id: string
    machineKey: string
    type: 'CHECKBOX' | 'NUMBER' | 'SINGLE_SELECT' | 'SHORT_TEXT' | 'LONG_TEXT'
    label: string
    description?: string | null
    validation: {
      minLength?: number
      maxLength?: number
      minimum?: number
      maximum?: number
    }
  }
  visible: boolean
  editable: boolean
  required: boolean
  options: Array<{
    id: string
    machineKey: string
    label: string
    order: number
  }>
}
export interface CustomerFormProjection {
  formId: string
  formVersion: number
  fields: CustomerFormField[]
}
export interface RequestConsentPolicy {
  policyKey: string
  version: number
  title: string
  required: boolean
  paragraphs: string[]
}
export interface RequestConfiguration {
  form: CustomerFormProjection | null
  policies: RequestConsentPolicy[]
}

export async function loadRequestConfiguration(): Promise<RequestConfiguration> {
  const [form, policies] = await Promise.all([
    requestCustomerConfiguration('/api/v1/customer/ticket-forms')
      .then(decodeForm)
      .catch((error: unknown) => {
        if (
          error instanceof ApiError &&
          error.status === 404 &&
          error.problem?.type === '/problems/customer-ticket-form-unavailable'
        )
          return null
        throw error
      }),
    requestCustomerConfiguration(
      '/api/v1/customer/consent-policies?context=REQUEST_SUBMISSION',
    ).then(decodePolicies),
  ])
  return { form, policies }
}

export async function projectRequestConfiguration(
  form: CustomerFormProjection,
  values: Record<string, CustomerFieldValue>,
): Promise<CustomerFormProjection> {
  return decodeForm(
    await requestCustomerConfiguration(
      '/api/v1/customer/ticket-form-projections',
      {
        ticketKind: 'CUSTOMER_REQUEST',
        formId: form.formId,
        formVersion: form.formVersion,
        fieldValues: values,
      },
    ),
  )
}

export function decodeForm(value: unknown): CustomerFormProjection {
  if (
    !record(value) ||
    !text(value.formId) ||
    !positive(value.formVersion) ||
    !Array.isArray(value.fields) ||
    value.fields.length > 100
  )
    throw invalid()
  const fields = value.fields.map((item): CustomerFormField => {
    if (
      !record(item) ||
      !record(item.field) ||
      !text(item.field.id) ||
      !text(item.field.machineKey) ||
      !text(item.field.label) ||
      ![
        'CHECKBOX',
        'NUMBER',
        'SINGLE_SELECT',
        'SHORT_TEXT',
        'LONG_TEXT',
      ].includes(String(item.field.type)) ||
      typeof item.visible !== 'boolean' ||
      typeof item.editable !== 'boolean' ||
      typeof item.required !== 'boolean' ||
      !Array.isArray(item.options) ||
      !record(item.field.validation)
    )
      throw invalid()
    if (
      item.options.some(
        (option) =>
          !record(option) ||
          !text(option.id) ||
          !text(option.label) ||
          !text(option.machineKey) ||
          !Number.isSafeInteger(option.order),
      )
    )
      throw invalid()
    for (const key of ['minLength', 'maxLength', 'minimum', 'maximum']) {
      const bound = item.field.validation[key]
      if (
        bound != null &&
        (typeof bound !== 'number' || !Number.isFinite(bound))
      )
        throw invalid()
    }
    return item as unknown as CustomerFormField
  })
  if (
    new Set(fields.map(({ field }) => field.machineKey)).size !== fields.length
  )
    throw invalid()
  return {
    formId: value.formId,
    formVersion: value.formVersion as number,
    fields,
  }
}

export function decodePolicies(value: unknown): RequestConsentPolicy[] {
  if (
    !record(value) ||
    value.context !== 'REQUEST_SUBMISSION' ||
    !Array.isArray(value.policies) ||
    value.policies.length > 20
  )
    throw invalid()
  const policies = value.policies.map((policy): RequestConsentPolicy => {
    if (
      !record(policy) ||
      !text(policy.policyKey) ||
      !positive(policy.version) ||
      !text(policy.title) ||
      typeof policy.required !== 'boolean' ||
      !record(policy.document) ||
      !Array.isArray(policy.document.blocks)
    )
      throw invalid()
    // Consent is readable as plain text; no arbitrary HTML or external fetch is used.
    const paragraphs = policy.document.blocks.flatMap((block): string[] => {
      if (!record(block)) throw invalid()
      if (block.type === 'divider') return []
      if (
        block.type === 'list' &&
        Array.isArray(block.items) &&
        block.items.every(text)
      )
        return block.items
      if (
        ['paragraph', 'heading', 'callout', 'quote', 'link'].includes(
          String(block.type),
        ) &&
        text(block.text)
      )
        return [block.text]
      throw invalid()
    })
    return {
      policyKey: policy.policyKey,
      version: policy.version as number,
      title: policy.title,
      required: policy.required,
      paragraphs,
    }
  })
  if (
    new Set(policies.map(({ policyKey }) => policyKey)).size !== policies.length
  )
    throw invalid()
  return policies
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function text(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}
function positive(value: unknown) {
  return Number.isSafeInteger(value) && Number(value) > 0
}
function invalid() {
  return new Error('customer-request-configuration-invalid')
}
