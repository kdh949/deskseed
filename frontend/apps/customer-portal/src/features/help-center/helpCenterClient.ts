const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export interface HelpCategory {
  id: string
  slug: string
  title: string
  description?: string
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
  blocks: Array<{ type: string; text?: string }>
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
}

export async function listHelpCategories(): Promise<HelpCategory[]> {
  const response = await customerFetch('/api/v1/help/categories')
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

export async function getHelpSection(slug: string): Promise<HelpSection> {
  const response = await customerFetch(
    `/api/v1/help/sections/${encodeURIComponent(slug)}`,
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
  }
}

export async function searchHelpArticles(
  query: string,
): Promise<HelpSearchHit[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/help/search`, {
    ...customerOptions(),
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, limit: 20 }),
  })
  const body: unknown = await checkedJson(response)
  if (!isRecord(body) || !Array.isArray(body.items)) return []
  return body.items.flatMap((item) => {
    if (
      !isRecord(item) ||
      typeof item.articleSlug !== 'string' ||
      typeof item.title !== 'string' ||
      typeof item.excerpt !== 'string'
    )
      return []
    return [item as unknown as HelpSearchHit]
  })
}

export async function getHelpArticle(slug: string): Promise<HelpArticle> {
  const response = await customerFetch(
    `/api/v1/help/articles/${encodeURIComponent(slug)}`,
  )
  const body: unknown = await checkedJson(response)
  if (!isRecord(body) || !isRecord(body.currentPublishedRevision))
    throw new Error('help-article-response-invalid')
  const revision = body.currentPublishedRevision
  const document = isRecord(revision.document) ? revision.document : undefined
  const blocks =
    document && Array.isArray(document.blocks)
      ? document.blocks.flatMap((block) =>
          isRecord(block) ? [block as { type: string; text?: string }] : [],
        )
      : []
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

function customerFetch(path: string) {
  return fetch(`${API_BASE_URL}${path}`, customerOptions())
}

function customerOptions(): RequestInit {
  return {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  }
}

async function checkedJson(response: Response) {
  if (!response.ok) throw new Error(`help-center-${response.status}`)
  return response.json()
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
