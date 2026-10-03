import { requestStaffResource } from '../../api/client'

export const CONSENT_CONTEXTS = {
  REGISTRATION: '회원가입',
  REQUEST_SUBMISSION: '문의 접수',
} as const
export const CONSENT_LIFECYCLES = {
  DRAFT: '초안',
  PUBLISHED: '발행됨',
  ARCHIVED: '보관됨',
} as const
export type ConsentContext = keyof typeof CONSENT_CONTEXTS
export type ConsentLifecycle = keyof typeof CONSENT_LIFECYCLES
export type ConsentBlock =
  | { type: 'paragraph' | 'callout' | 'quote'; text: string }
  | { type: 'heading'; level: 2 | 3; text: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'divider' }
  | { type: 'link'; text: string; url: string }
export type ConsentDocument = { schemaVersion: 1; blocks: ConsentBlock[] }
export type ConsentDraft = {
  title: string
  document: ConsentDocument
  required: boolean
  displayOrder: number
}
export type ConsentSummary = {
  id: string
  policyKey: string
  context: ConsentContext
  lifecycle: ConsentLifecycle
  aggregateVersion: number
  publishedVersion: number | null
  required: boolean
  displayOrder: number
  createdAt: string
  updatedAt: string
}
export type ConsentVersion = ConsentDraft & {
  policyId: string
  policyKey: string
  version: number
  plainText: string
  checksumSha256: string
  effectiveAt: string
  publishedByStaffId: string
  publishedByDisplayName: string
  publishedAt: string
}
export type ConsentPolicy = Omit<
  ConsentSummary,
  'publishedVersion' | 'required' | 'displayOrder'
> & {
  draft: ConsentDraft & { draftVersion: number; updatedAt: string }
  publishedVersion: ConsentVersion | null
  versions: ConsentVersion[]
}
export type ConsentPage = {
  items: ConsentSummary[]
  page: number
  size: number
  totalCount: number
  totalPages: number
}
const root = '/api/v1/admin/customer-consent-policies' as const
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const integer = (v: unknown, min = 0): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= min
const hasControlCharacters = (v: string) =>
  Array.from(v).some((character) => {
    const code = character.charCodeAt(0)
    return code < 32 || (code >= 127 && code <= 159)
  })
const text = (v: unknown, max = 10000): v is string =>
  typeof v === 'string' &&
  v.trim().length > 0 &&
  v.length <= max &&
  !/[<>]/.test(v) &&
  !hasControlCharacters(v)
const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    v,
  )
const timestamp = (v: unknown): v is string =>
  typeof v === 'string' && Number.isFinite(Date.parse(v))
const policyKey = (v: unknown): v is string =>
  typeof v === 'string' &&
  v.length <= 80 &&
  /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(v)
export function consentHttpsUrl(v: unknown): v is string {
  if (
    typeof v !== 'string' ||
    v.length > 2048 ||
    /\s/.test(v) ||
    hasControlCharacters(v)
  )
    return false
  try {
    const url = new URL(v)
    return (
      v.startsWith('https://') &&
      url.protocol === 'https:' &&
      !!url.hostname &&
      !url.username &&
      !url.password
    )
  } catch {
    return false
  }
}
export function decodeConsentDocument(v: unknown): ConsentDocument | undefined {
  if (
    !record(v) ||
    v.schemaVersion !== 1 ||
    !Array.isArray(v.blocks) ||
    v.blocks.length < 1 ||
    v.blocks.length > 100
  )
    return undefined
  const blocks: ConsentBlock[] = []
  for (const block of v.blocks) {
    if (!record(block)) return undefined
    if (block.type === 'divider') blocks.push({ type: 'divider' })
    else if (
      block.type === 'list' &&
      typeof block.ordered === 'boolean' &&
      Array.isArray(block.items) &&
      block.items.length > 0 &&
      block.items.length <= 100 &&
      block.items.every((item) => text(item))
    )
      blocks.push({
        type: 'list',
        ordered: block.ordered,
        items: block.items as string[],
      })
    else if (
      block.type === 'heading' &&
      (block.level === 2 || block.level === 3) &&
      text(block.text)
    )
      blocks.push({ type: 'heading', level: block.level, text: block.text })
    else if (
      block.type === 'link' &&
      text(block.text) &&
      consentHttpsUrl(block.url)
    )
      blocks.push({ type: 'link', text: block.text, url: block.url })
    else if (
      (block.type === 'paragraph' ||
        block.type === 'callout' ||
        block.type === 'quote') &&
      text(block.text)
    )
      blocks.push({ type: block.type, text: block.text })
    else return undefined
  }
  return { schemaVersion: 1, blocks }
}
export function consentPlainText(document: ConsentDocument) {
  return document.blocks
    .filter((block) => block.type !== 'divider')
    .map((block) =>
      block.type === 'list'
        ? block.items.map((item) => item.trim()).join('\n')
        : 'text' in block
          ? block.text.trim()
          : '',
    )
    .join('\n')
}
function validDraftShape(v: unknown): boolean {
  if (
    !record(v) ||
    !text(v.title, 200) ||
    typeof v.required !== 'boolean' ||
    !integer(v.displayOrder) ||
    v.displayOrder > 10000
  )
    return false
  const document = decodeConsentDocument(v.document)
  return document !== undefined
}
export function validConsentDraft(v: unknown): boolean {
  if (!record(v) || !validDraftShape(v)) return false
  const plain = consentPlainText(decodeConsentDocument(v.document)!)
  return (
    Array.from(plain).length <= 50000 &&
    new TextEncoder().encode(plain).length <= 200000
  )
}
function policyIdentity(v: Record<string, unknown>) {
  return (
    uuid(v.id) &&
    policyKey(v.policyKey) &&
    Object.hasOwn(CONSENT_CONTEXTS, String(v.context)) &&
    Object.hasOwn(CONSENT_LIFECYCLES, String(v.lifecycle)) &&
    integer(v.aggregateVersion) &&
    timestamp(v.createdAt) &&
    timestamp(v.updatedAt)
  )
}
function version(v: unknown): v is ConsentVersion {
  return (
    record(v) &&
    validConsentDraft(v) &&
    uuid(v.policyId) &&
    policyKey(v.policyKey) &&
    integer(v.version, 1) &&
    typeof v.plainText === 'string' &&
    typeof v.checksumSha256 === 'string' &&
    /^[a-f0-9]{64}$/.test(v.checksumSha256) &&
    timestamp(v.effectiveAt) &&
    uuid(v.publishedByStaffId) &&
    text(v.publishedByDisplayName, 100) &&
    timestamp(v.publishedAt)
  )
}
export function decodeConsentPolicy(v: unknown): ConsentPolicy | undefined {
  if (
    !record(v) ||
    !policyIdentity(v) ||
    !record(v.draft) ||
    !validDraftShape(v.draft) ||
    !integer(v.draft.draftVersion, 1) ||
    !timestamp(v.draft.updatedAt) ||
    !(v.publishedVersion === null || version(v.publishedVersion)) ||
    !Array.isArray(v.versions) ||
    v.versions.length > 200 ||
    !v.versions.every(version)
  )
    return undefined
  return v as ConsentPolicy
}
function decodePage(v: unknown): ConsentPage | undefined {
  if (
    !record(v) ||
    !Array.isArray(v.items) ||
    v.items.length > 100 ||
    !v.items.every(
      (item) =>
        record(item) &&
        policyIdentity(item) &&
        (item.publishedVersion === null || integer(item.publishedVersion, 1)) &&
        typeof item.required === 'boolean' &&
        integer(item.displayOrder),
    ) ||
    !integer(v.page) ||
    !integer(v.size, 1) ||
    v.size > 100 ||
    !integer(v.totalCount) ||
    !integer(v.totalPages)
  )
    return undefined
  return v as ConsentPage
}
export const listConsentPolicies = (
  context: string,
  lifecycle: string,
  page: number,
) => {
  const query = new URLSearchParams({ page: String(page), size: '20' })
  if (context) query.set('context', context)
  if (lifecycle) query.set('lifecycle', lifecycle)
  return requestStaffResource(`${root}?${query}`, decodePage)
}
export const getConsentPolicy = (id: string) =>
  requestStaffResource(`${root}/${id}`, decodeConsentPolicy)
export const createConsentPolicy = (
  draft: ConsentDraft,
  key: string,
  context: ConsentContext,
) =>
  requestStaffResource(root, decodeConsentPolicy, {
    method: 'POST',
    headers: { 'If-None-Match': '*' },
    body: { ...draft, policyKey: key, context },
  })
export const updateConsentPolicy = (
  policy: ConsentPolicy,
  draft: ConsentDraft,
) =>
  requestStaffResource(`${root}/${policy.id}`, decodeConsentPolicy, {
    method: 'PUT',
    version: policy.aggregateVersion,
    body: draft,
  })
export const transitionConsentPolicy = (
  policy: ConsentPolicy,
  action: 'publish' | 'archive',
) =>
  requestStaffResource(`${root}/${policy.id}/${action}`, decodeConsentPolicy, {
    method: 'POST',
    version: policy.aggregateVersion,
  })
