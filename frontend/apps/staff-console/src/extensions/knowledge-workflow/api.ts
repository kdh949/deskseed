import { requestStaffResource } from '../../api/client'
export const AUDIENCES = {
  PUBLIC: '누구나',
  SIGNED_IN_CUSTOMER: '로그인한 고객',
  STAFF: '모든 직원',
  SELECTED_STAFF_GROUPS: '선택한 직원 그룹',
} as const
export const LIFECYCLES = {
  DRAFT: '초안',
  IN_REVIEW: '검토 중',
  PUBLISHED: '발행됨',
  UNPUBLISHED: '공개 중지',
  ARCHIVED: '보관됨',
} as const
export type Audience = { type: keyof typeof AUDIENCES; groupIds: string[] }
export type Block = {
  type:
    | 'paragraph'
    | 'heading'
    | 'list'
    | 'code'
    | 'callout'
    | 'quote'
    | 'divider'
    | 'link'
    | 'attachment'
  text?: string
  level?: number
  ordered?: boolean
  items?: string[]
  language?: string | null
  url?: string
  attachmentId?: string
}
export type Document = { schemaVersion: 1; blocks: Block[] }
export type Category = {
  id: string
  slug: string
  title: string
  description: string
  active: boolean
  displayOrder: number
  version: number
}
export type Section = Category & { categoryId: string }
export type Revision = {
  id: string
  revisionNumber: number
  title: string
  summary: string
  changeNote?: string
  document: Document
  createdAt: string
}
export type Article = {
  id: string
  sectionId: string
  slug: string
  lifecycle: keyof typeof LIFECYCLES
  audience: Audience
  version: number
  currentPublishedRevision: Revision | null
}
export type ArticleDraft = {
  sectionId: string
  slug: string
  title: string
  summary: string
  changeNote: string
  audience: Audience
  document: Document
}
export type SearchHit = {
  articleSlug: string
  title: string
  excerpt: string
  audience: keyof typeof AUDIENCES
  categoryTitle: string
  sectionTitle: string
}
export type SearchPage = { items: SearchHit[]; nextCursor: string | null }
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const strings = (v: Record<string, unknown>, keys: string[]) =>
  keys.every((k) => typeof v[k] === 'string')
const list =
  <T>(decode: (v: unknown) => T | undefined) =>
  (v: unknown): T[] | undefined => {
    if (!Array.isArray(v)) return undefined
    const values = v.map(decode)
    return values.some((x) => x === undefined) ? undefined : (values as T[])
  }
export const safeLink = (value: string) => {
  try {
    const u = new URL(value)
    return u.protocol === 'https:' && !u.username && !u.password
  } catch {
    return false
  }
}
export function decodeDocument(v: unknown): Document | undefined {
  if (
    !record(v) ||
    v.schemaVersion !== 1 ||
    !Array.isArray(v.blocks) ||
    v.blocks.length > 200
  )
    return undefined
  if (
    !v.blocks.every((b) => {
      if (!record(b)) return false
      switch (b.type) {
        case 'paragraph':
        case 'callout':
        case 'quote':
        case 'code':
          return typeof b.text === 'string'
        case 'heading':
          return [2, 3].includes(Number(b.level)) && typeof b.text === 'string'
        case 'list':
          return (
            typeof b.ordered === 'boolean' &&
            Array.isArray(b.items) &&
            b.items.every((x) => typeof x === 'string')
          )
        case 'divider':
          return true
        case 'link':
          return (
            typeof b.text === 'string' &&
            typeof b.url === 'string' &&
            safeLink(b.url)
          )
        case 'attachment':
          return typeof b.attachmentId === 'string'
        default:
          return false
      }
    })
  )
    return undefined
  return v as Document
}
function decodeCategory(v: unknown): Category | undefined {
  return record(v) &&
    strings(v, ['id', 'slug', 'title', 'description']) &&
    typeof v.active === 'boolean' &&
    Number.isSafeInteger(v.version) &&
    Number.isInteger(v.displayOrder)
    ? (v as Category)
    : undefined
}
function decodeSection(v: unknown): Section | undefined {
  return decodeCategory(v) && record(v) && typeof v.categoryId === 'string'
    ? (v as Section)
    : undefined
}
export function decodeArticle(v: unknown): Article | undefined {
  if (
    !record(v) ||
    !strings(v, ['id', 'sectionId', 'slug']) ||
    !Object.hasOwn(LIFECYCLES, String(v.lifecycle)) ||
    !record(v.audience) ||
    !Object.hasOwn(AUDIENCES, String(v.audience.type)) ||
    !Array.isArray(v.audience.groupIds) ||
    !v.audience.groupIds.every((id) => typeof id === 'string')
  )
    return undefined
  return v as Article
}
const decodeRevision = (v: unknown): Revision | undefined =>
  record(v) &&
  strings(v, ['id', 'title', 'summary', 'createdAt']) &&
  Number.isInteger(v.revisionNumber) &&
  decodeDocument(v.document)
    ? (v as Revision)
    : undefined
const decodeSearch = (v: unknown): SearchPage | undefined =>
  record(v) &&
  Array.isArray(v.items) &&
  (v.nextCursor === null || typeof v.nextCursor === 'string') &&
  v.items.every(
    (x) =>
      record(x) &&
      strings(x, [
        'articleSlug',
        'title',
        'excerpt',
        'categoryTitle',
        'sectionTitle',
      ]) &&
      Object.hasOwn(AUDIENCES, String(x.audience)),
  )
    ? (v as SearchPage)
    : undefined
const admin = '/api/v1/admin/knowledge' as const
export const listCategories = () =>
  requestStaffResource(`${admin}/categories`, list(decodeCategory))
export const listSections = () =>
  requestStaffResource(`${admin}/sections`, list(decodeSection))
export const saveCategory = (
  draft: Omit<Category, 'id' | 'version' | 'active'>,
  existing?: Category,
) =>
  requestStaffResource(
    existing ? `${admin}/categories/${existing.id}` : `${admin}/categories`,
    decodeCategory,
    {
      method: existing ? 'PATCH' : 'POST',
      body: { ...draft, ...(existing ? { active: existing.active } : {}) },
      version: existing?.version,
    },
  )
export const saveSection = (
  draft: Omit<Section, 'id' | 'version' | 'active'>,
  existing?: Section,
) =>
  requestStaffResource(
    existing ? `${admin}/sections/${existing.id}` : `${admin}/sections`,
    decodeSection,
    {
      method: existing ? 'PATCH' : 'POST',
      body: { ...draft, ...(existing ? { active: existing.active } : {}) },
      version: existing?.version,
    },
  )
export const listArticles = (lifecycle: string, cursor?: string) =>
  requestStaffResource(
    `${admin}/articles?${new URLSearchParams({ ...(lifecycle ? { lifecycle } : {}), ...(cursor ? { cursor } : {}) })}`,
    (v) => {
      if (
        !record(v) ||
        !(v.nextCursor === null || typeof v.nextCursor === 'string')
      )
        return undefined
      const items = list(decodeArticle)(v.items)
      return items ? { items, nextCursor: v.nextCursor } : undefined
    },
  )
export const getArticle = (id: string) =>
  requestStaffResource(`${admin}/articles/${id}`, decodeArticle)
export const listRevisions = (id: string) =>
  requestStaffResource(
    `${admin}/articles/${id}/revisions`,
    list(decodeRevision),
  )
export const saveArticle = (draft: ArticleDraft, existing?: Article) =>
  requestStaffResource(
    existing ? `${admin}/articles/${existing.id}` : `${admin}/articles`,
    decodeArticle,
    {
      method: existing ? 'PATCH' : 'POST',
      body: draft,
      version: existing?.version,
    },
  )
export const transitionArticle = (article: Article, action: string) =>
  requestStaffResource(
    `${admin}/articles/${article.id}/${action}`,
    decodeArticle,
    { method: 'POST', version: article.version },
  )
export const searchKnowledge = (query: string, cursor?: string) =>
  requestStaffResource('/api/v1/agent/knowledge/search', decodeSearch, {
    method: 'POST',
    body: { query, cursor, limit: 20 },
  })
export const suggestKnowledge = (ticketNumber: number) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/knowledge-suggestions`,
    decodeSearch,
  )
export const readKnowledge = (slug: string) =>
  requestStaffResource(
    `/api/v1/agent/knowledge/articles/${encodeURIComponent(slug)}`,
    (v) => {
      const a = decodeArticle(v)
      if (!a || !decodeRevision(a.currentPublishedRevision)) return undefined
      return a
    },
  )
export function articleLink(
  article: Pick<Article, 'slug' | 'audience'>,
  mode: 'PUBLIC' | 'INTERNAL',
  origin: string,
) {
  const customer = ['PUBLIC', 'SIGNED_IN_CUSTOMER'].includes(
    article.audience.type,
  )
  if (mode === 'PUBLIC' && !customer) return undefined
  const url = new URL(
    `${customer ? '/articles/' : '/agent/knowledge/articles/'}${encodeURIComponent(article.slug)}`,
    origin,
  )
  return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined
}
