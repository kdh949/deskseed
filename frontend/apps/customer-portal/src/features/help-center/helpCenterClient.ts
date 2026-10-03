import { parseHelpBlocks, type HelpBlock } from './helpDocumentCodec'
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export interface HelpCategory {
  id: string
  slug: string
  title: string
  description?: string
  sections?: HelpSection[]
}

export interface HelpSearchHit {
  articleSlug: string
  title: string
  excerpt: string
  categoryTitle?: string
  sectionTitle?: string
}

export interface HelpArticle {
  slug: string
  title: string
  summary?: string
  updatedAt?: string
  blocks: HelpBlock[]
}

export interface HelpArticleListing {
  slug: string
  title: string
  summary: string
}

export interface HelpSection {
  slug: string
  title: string
  description: string
  articles: HelpArticleListing[]
  hasMore: boolean
  nextCursor: string | null
}

export async function listHelpCategories(
  signal?: AbortSignal,
): Promise<HelpCategory[]> {
  const response = await customerFetch('/api/v1/help/categories', signal)
  const body: unknown = await checkedJson(response)
  const list = Array.isArray(body)
    ? body
    : isRecord(body) && Array.isArray(body.items)
      ? body.items
      : []
  return list.flatMap((item) => {
    if (
      !isRecord(item) ||
      typeof item.id !== 'string' ||
      typeof item.slug !== 'string' ||
      typeof item.title !== 'string'
    )
      return []
    return [
      {
        id: item.id,
        slug: item.slug,
        title: item.title,
        ...(typeof item.description === 'string'
          ? { description: item.description }
          : {}),
      },
    ]
  })
}

export async function getHelpSection(
  slug: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<HelpSection> {
  const response = await customerFetch(
    `/api/v1/help/sections/${encodeURIComponent(slug)}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`,
    signal,
  )
  const body: unknown = await checkedJson(response)
  if (
    !isRecord(body) ||
    typeof body.slug !== 'string' ||
    typeof body.title !== 'string' ||
    !Array.isArray(body.articles)
  )
    throw new Error('help-section-response-invalid')

  const articles = body.articles.flatMap((item) => {
    if (
      !isRecord(item) ||
      typeof item.slug !== 'string' ||
      typeof item.title !== 'string' ||
      typeof item.summary !== 'string'
    )
      return []
    return [{ slug: item.slug, title: item.title, summary: item.summary }]
  })

  return {
    slug: body.slug,
    title: body.title,
    description: typeof body.description === 'string' ? body.description : '',
    articles,
    ...pageCursor(body),
  }
}

export async function searchHelpArticles(
  query: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<{
  items: HelpSearchHit[]
  hasMore: boolean
  nextCursor: string | null
}> {
  const response = await fetch(`${API_BASE_URL}/api/v1/help/search`, {
    ...customerOptions(signal),
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, limit: 20, ...(cursor ? { cursor } : {}) }),
  })
  const body: unknown = await checkedJson(response)
  if (!isRecord(body) || !Array.isArray(body.items))
    throw new Error('help-search-response-invalid')
  const items = body.items.flatMap((item) => {
    if (
      !isRecord(item) ||
      typeof item.articleSlug !== 'string' ||
      typeof item.title !== 'string' ||
      typeof item.excerpt !== 'string'
    )
      return []
    return [item as unknown as HelpSearchHit]
  })
  return { items, ...pageCursor(body) }
}

export async function getHelpArticle(
  slug: string,
  signal?: AbortSignal,
): Promise<HelpArticle> {
  const response = await customerFetch(
    `/api/v1/help/articles/${encodeURIComponent(slug)}`,
    signal,
  )
  const body: unknown = await checkedJson(response)
  if (!isRecord(body) || !isRecord(body.currentPublishedRevision))
    throw new Error('help-article-response-invalid')
  const revision = body.currentPublishedRevision
  const document = isRecord(revision.document) ? revision.document : undefined
  const blocks = parseHelpBlocks(document)
  if (typeof body.slug !== 'string' || typeof revision.title !== 'string')
    throw new Error('help-article-response-invalid')
  return {
    slug: body.slug,
    title: revision.title,
    ...(typeof revision.summary === 'string'
      ? { summary: revision.summary }
      : {}),
    ...(typeof revision.createdAt === 'string'
      ? { updatedAt: revision.createdAt }
      : {}),
    blocks,
  }
}

export async function recordHelpArticleFeedback(
  slug: string,
  helpful: boolean,
) {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/help/articles/${encodeURIComponent(slug)}/feedback`,
    {
      ...customerOptions(),
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ helpful }),
    },
  )
  if (!response.ok) throw new Error(`help-feedback-${response.status}`)
}

function customerFetch(path: string, signal?: AbortSignal) {
  return fetch(`${API_BASE_URL}${path}`, customerOptions(signal))
}

function customerOptions(signal?: AbortSignal): RequestInit {
  return {
    signal,
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  }
}

async function checkedJson(response: Response) {
  if (!response.ok) throw new HelpApiError(response.status)
  return response.json()
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export class HelpApiError extends Error {
  constructor(readonly status: number) {
    super(`help-center-${status}`)
  }
}
function pageCursor(body: Record<string, unknown>) {
  const nextCursor =
    typeof body.nextCursor === 'string' &&
    body.nextCursor.length > 0 &&
    body.nextCursor.length <= 1024
      ? body.nextCursor
      : null
  if (body.hasMore === true && !nextCursor)
    throw new Error('help-cursor-response-invalid')
  return { hasMore: body.hasMore === true, nextCursor }
}
export async function getHelpCategory(
  slug: string,
  signal?: AbortSignal,
): Promise<HelpCategory> {
  const body: unknown = await checkedJson(
    await customerFetch(
      `/api/v1/help/categories/${encodeURIComponent(slug)}`,
      signal,
    ),
  )
  if (
    !isRecord(body) ||
    typeof body.id !== 'string' ||
    typeof body.slug !== 'string' ||
    typeof body.title !== 'string' ||
    !Array.isArray(body.sections)
  )
    throw new Error('help-category-response-invalid')
  const sections = body.sections.map((item) => {
    if (
      !isRecord(item) ||
      typeof item.slug !== 'string' ||
      typeof item.title !== 'string'
    )
      throw new Error('help-category-response-invalid')
    return {
      slug: item.slug,
      title: item.title,
      description: typeof item.description === 'string' ? item.description : '',
      articles: [],
      hasMore: false,
      nextCursor: null,
    }
  })
  return {
    id: body.id,
    slug: body.slug,
    title: body.title,
    description: typeof body.description === 'string' ? body.description : '',
    sections,
  }
}
