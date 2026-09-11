import { HelpDocument } from './HelpDocument'
import { useHelpScope } from './useHelpScope'
import {
  useInfiniteQuery,
  useQuery,
  type UseQueryResult,
} from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  CustomerIcon,
  DsButton,
  RetryButton,
  ScreenState,
} from '../../design-system'
import heroImage from '../../assets/deskseed/customer-help-hero.png'
import {
  getHelpArticle,
  getHelpCategory,
  HelpApiError,
  getHelpSection,
  listHelpCategories,
  recordHelpArticleFeedback,
  searchHelpArticles,
  type HelpCategory,
} from './helpCenterClient'

export function HelpCenterHomePage() {
  const scope = useHelpScope()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const categories = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'categories'],
    queryFn: ({ signal }) => listHelpCategories(signal),
  })
  const announcements = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'section', 'announcements'],
    queryFn: ({ signal }) => getHelpSection('announcements', undefined, signal),
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (query.trim()) navigate(`/search?q=${encodeURIComponent(query.trim())}`)
  }
  return (
    <div className="customer-home">
      <section className="customer-home-hero">
        <div>
          <h1>
            안녕하세요!
            <br />
            무엇을 도와드릴까요?
          </h1>
          <p>
            도움말을 검색하고, 주제별 문서를 살펴보거나 지원팀에 문의하세요.
          </p>
        </div>
        <img
          alt="DeskSeed 도움말과 채팅을 표현한 노트북 일러스트"
          src={heroImage}
        />
        <form aria-label="도움말 홈 검색" onSubmit={submit} role="search">
          <CustomerIcon name="search" size="lg" />
          <label className="customer-sr-only" htmlFor="home-help-search">
            도움말 검색
          </label>
          <input
            id="home-help-search"
            onChange={(event) => setQuery(event.target.value)}
            placeholder="문서, 주제 또는 키워드 검색..."
            value={query}
          />
          <DsButton tone="primary" type="submit">
            검색
          </DsButton>
        </form>
        <nav aria-label="빠른 작업">
          <Link to="/account/requests">
            <CustomerIcon name="inbox" />내 문의 확인
          </Link>
          <Link to="/requests/new">
            <CustomerIcon name="speechBubble" />
            고객 지원 문의
          </Link>
          <Link to="/categories">
            <CustomerIcon name="book" />
            모든 문서 보기
          </Link>
        </nav>
      </section>
      <section className="customer-home-section">
        <h2>주제별 둘러보기</h2>
        <CategoryCollection query={categories} />
      </section>
      <section className="customer-home-lower customer-home-lower--single">
        <div className="customer-panel customer-announcements">
          <header>
            <h2>공지사항</h2>
            <Link to="/sections/announcements">전체 보기</Link>
          </header>
          {announcements.isPending ? (
            <div className="customer-announcement-state" role="status">
              <p>공지사항을 불러오고 있습니다.</p>
            </div>
          ) : null}
          {announcements.isError ? (
            <div className="customer-announcement-state" role="alert">
              <p>공지사항을 불러올 수 없습니다.</p>
              <RetryButton onClick={() => void announcements.refetch()} />
            </div>
          ) : null}
          {announcements.isSuccess && !announcements.data.articles.length ? (
            <div className="customer-announcement-state" role="status">
              <p>등록된 공지사항이 없습니다.</p>
            </div>
          ) : null}
          {announcements.data?.articles.slice(0, 2).map((announcement) => (
            <article key={announcement.slug}>
              <CustomerIcon name="speechBubble" />
              <div>
                <h3>
                  <Link to={`/articles/${announcement.slug}`}>
                    {announcement.title}
                  </Link>
                </h3>
                {announcement.summary ? <p>{announcement.summary}</p> : null}
              </div>
              <span>공지</span>
            </article>
          ))}
        </div>
      </section>
    </div>
  )
}

export function HelpSearchPage() {
  const scope = useHelpScope()
  const [parameters, setParameters] = useSearchParams()
  const initial = parameters.get('q') ?? ''
  const [query, setQuery] = useState(initial)
  useEffect(() => setQuery(initial), [initial])
  const normalized = initial.trim()
  const results = useInfiniteQuery({
    enabled: scope[1] !== 'loading' && Boolean(normalized),
    queryKey: [...scope, 'search', normalized],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      searchHelpArticles(normalized, pageParam, signal),
    getNextPageParam: (page) =>
      page.hasMore ? (page.nextCursor ?? undefined) : undefined,
  })
  const hits = results.data?.pages.flatMap((page) => page.items) ?? []
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setParameters(query.trim() ? { q: query.trim() } : {})
  }
  return (
    <div className="customer-page-wide customer-search-page">
      <div className="customer-content-column">
        <span className="customer-breadcrumb">
          <Link to="/">홈</Link> / 검색 결과
        </span>
        <h1>검색 결과</h1>
        <p>
          {normalized ? `“${normalized}” 검색 결과` : '검색어를 입력해 주세요.'}
          {!normalized && <Link to="/categories">모든 문서 둘러보기</Link>}
        </p>
        <form aria-label="도움말 결과 검색" onSubmit={submit} role="search">
          <CustomerIcon name="search" />
          <input
            aria-label="도움말 검색어"
            onChange={(event) => setQuery(event.target.value)}
            value={query}
          />
          <DsButton tone="primary" type="submit">
            검색
          </DsButton>
        </form>
        {results.isPending && normalized ? (
          <ScreenState kind="loading" title="관련 문서를 찾고 있습니다." />
        ) : null}
        {results.isError ? (
          <ScreenState
            action={<RetryButton onClick={() => void results.refetch()} />}
            kind="error"
            title="검색 결과를 불러올 수 없습니다."
          />
        ) : null}
        {results.data ? (
          hits.length ? (
            <div className="customer-search-results">
              {hits.map((item, index) => (
                <Link
                  className={index === 0 ? 'is-top' : ''}
                  key={item.articleSlug}
                  to={`/articles/${item.articleSlug}`}
                >
                  <span>
                    <CustomerIcon
                      name={index === 0 ? 'inbox' : 'book'}
                      size="lg"
                    />
                  </span>
                  <div>
                    {index === 0 ? <small>가장 관련 높은 결과</small> : null}
                    <h2>{item.title}</h2>
                    <p>{item.excerpt}</p>
                    <em>
                      {[item.categoryTitle, item.sectionTitle]
                        .filter(Boolean)
                        .join(' · ')}
                    </em>
                  </div>
                  <b aria-hidden="true">›</b>
                </Link>
              ))}
            </div>
          ) : (
            <ScreenState
              description="다른 키워드로 검색하거나 지원팀에 문의해 주세요."
              kind="empty"
              title="검색 결과가 없습니다."
            />
          )
        ) : null}
        {results.hasNextPage && (
          <DsButton
            disabled={results.isFetchingNextPage}
            onClick={() => void results.fetchNextPage()}
          >
            {results.isFetchingNextPage ? '불러오는 중…' : '검색 결과 더 보기'}
          </DsButton>
        )}
      </div>
      <SearchSidebar />
    </div>
  )
}

function SearchSidebar() {
  const scope = useHelpScope()
  const categories = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'categories'],
    queryFn: ({ signal }) => listHelpCategories(signal),
  })
  return (
    <aside className="customer-aside">
      <section>
        <h2>주제</h2>
        <CategoryLinks query={categories} />
      </section>
      <section>
        <h2>원하는 답을 찾지 못했나요?</h2>
        <p>지원팀이 함께 해결해 드립니다.</p>
        <Link className="customer-aside-cta" to="/requests/new">
          <CustomerIcon name="plus" />
          문의 접수
        </Link>
      </section>
    </aside>
  )
}

export function HelpArticlePage() {
  const scope = useHelpScope()
  const { articleSlug = '' } = useParams()
  const article = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'article', articleSlug],
    queryFn: ({ signal }) => getHelpArticle(articleSlug, signal),
  })
  if (article.isPending)
    return (
      <div className="customer-page">
        <ScreenState kind="loading" title="문서를 불러오고 있습니다." />
      </div>
    )
  if (article.isError)
    return (
      <div className="customer-page">
        <ScreenState
          action={<RetryButton onClick={() => void article.refetch()} />}
          kind={
            article.error instanceof HelpApiError &&
            article.error.status === 404
              ? 'not-found'
              : article.error instanceof HelpApiError &&
                  [401, 403].includes(article.error.status)
                ? 'denied'
                : 'error'
          }
          title={
            article.error instanceof HelpApiError &&
            article.error.status === 404
              ? '문서를 찾을 수 없습니다.'
              : '문서를 불러올 수 없습니다.'
          }
        />
      </div>
    )
  return (
    <HelpArticleContent
      key={[...scope, articleSlug].join(':')}
      data={article.data}
    />
  )
}
function HelpArticleContent({
  data,
}: {
  data: Awaited<ReturnType<typeof getHelpArticle>>
}) {
  const [feedback, setFeedback] = useState<
    'ready' | 'pending' | 'complete' | 'error'
  >('ready')
  async function sendFeedback(helpful: boolean) {
    if (feedback === 'pending' || feedback === 'complete') return
    setFeedback('pending')
    try {
      await recordHelpArticleFeedback(data.slug, helpful)
      setFeedback('complete')
    } catch {
      setFeedback('error')
    }
  }
  return (
    <div className="customer-article-layout customer-article-layout--content-only">
      <article className="customer-article">
        <span className="customer-breadcrumb">
          <Link to="/categories">모든 문서</Link> / 도움말
        </span>
        <header>
          <div>
            <h1>{data.title}</h1>
            {data.summary ? <p>{data.summary}</p> : null}
            {data.updatedAt ? (
              <small>업데이트 {formatDate(data.updatedAt)}</small>
            ) : null}
          </div>
          <button onClick={() => window.print()} type="button">
            문서 인쇄
          </button>
        </header>
        {data.blocks.length ? (
          <HelpDocument blocks={data.blocks} />
        ) : (
          <ScreenState
            action={<Link to="/requests/new">지원팀에 문의하기</Link>}
            compact
            description="필요한 도움이 있다면 지원팀에 문의해 주세요."
            kind="empty"
            title="이 문서는 아직 내용이 없습니다."
          />
        )}
      </article>
      <aside className="customer-aside customer-article-aside">
        <section>
          <h2>도움이 더 필요한가요?</h2>
          <p>지원팀이 함께 해결해 드립니다.</p>
          <Link className="customer-aside-cta" to="/requests/new">
            문의 접수
          </Link>
        </section>
        <section>
          <h2>이 문서가 도움이 되었나요?</h2>
          {feedback === 'pending' && (
            <p role="status">의견을 저장하고 있습니다.</p>
          )}
          {feedback === 'error' && (
            <p role="alert">의견을 저장하지 못했습니다. 다시 선택해 주세요.</p>
          )}
          {feedback === 'complete' ? (
            <p>의견을 보내주셔서 감사합니다.</p>
          ) : (
            <>
              <DsButton
                disabled={feedback === 'pending'}
                onClick={() => void sendFeedback(true)}
              >
                네, 도움이 됐어요
              </DsButton>
              <DsButton
                disabled={feedback === 'pending'}
                onClick={() => void sendFeedback(false)}
                tone="danger"
              >
                아니요
              </DsButton>
            </>
          )}
        </section>
      </aside>
    </div>
  )
}

type CategoriesQuery = UseQueryResult<HelpCategory[], Error>

function CategoryCollection({ query }: { query: CategoriesQuery }) {
  if (query.isPending)
    return (
      <div className="customer-topic-state" role="status">
        도움말 주제를 불러오고 있습니다.
      </div>
    )
  if (query.isError)
    return (
      <ScreenState
        action={<RetryButton onClick={() => void query.refetch()} />}
        compact
        kind="error"
        title="도움말 주제를 불러올 수 없습니다."
      />
    )
  if (!query.data.length)
    return (
      <ScreenState
        action={<Link to="/requests/new">지원팀에 문의하기</Link>}
        compact
        description="필요한 도움이 있다면 지원팀에 문의해 주세요."
        kind="empty"
        title="등록된 도움말 주제가 없습니다."
      />
    )
  return (
    <div className="customer-topic-grid">
      {query.data.map((item, index) => (
        <CategoryCard category={item} index={index} key={item.slug} />
      ))}
    </div>
  )
}

function CategoryLinks({ query }: { query: CategoriesQuery }) {
  if (query.isPending) return <p role="status">주제를 불러오고 있습니다.</p>
  if (query.isError) return <p role="alert">주제를 불러올 수 없습니다.</p>
  if (!query.data.length) return <p>등록된 주제가 없습니다.</p>
  return query.data.map((item) => (
    <Link key={item.slug} to={`/categories/${encodeURIComponent(item.slug)}`}>
      <CustomerIcon name="book" />
      {item.title}
      <span aria-hidden="true">›</span>
    </Link>
  ))
}

function CategoryCard({
  category,
  index,
}: {
  category: HelpCategory
  index: number
}) {
  return (
    <Link
      className={`customer-topic customer-topic--${(index % 5) + 1}`}
      to={`/categories/${encodeURIComponent(category.slug)}`}
    >
      <span>
        <CustomerIcon
          name={
            index === 1
              ? 'inbox'
              : index === 2
                ? 'pencil'
                : index === 3
                  ? 'user'
                  : index === 4
                    ? 'speechBubble'
                    : 'book'
          }
          size="lg"
        />
      </span>
      <h3>{category.title}</h3>
      {category.description ? <p>{category.description}</p> : null}
      <b aria-hidden="true">›</b>
    </Link>
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(
    new Date(value),
  )
}

export function HelpCategoriesPage() {
  const scope = useHelpScope()
  const categories = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'categories'],
    queryFn: ({ signal }) => listHelpCategories(signal),
  })
  return (
    <div className="customer-page">
      <Link to="/">홈</Link>
      <h1>모든 문서</h1>
      <p>주제를 선택해 섹션과 문서를 둘러보세요.</p>
      <h2>주제별 둘러보기</h2>
      <CategoryCollection query={categories} />
    </div>
  )
}
export function HelpCategoryPage() {
  const scope = useHelpScope()
  const { categorySlug = '' } = useParams()
  const category = useQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'category', categorySlug],
    queryFn: ({ signal }) => getHelpCategory(categorySlug, signal),
  })
  return (
    <div className="customer-page">
      <Link to="/categories">모든 문서</Link>
      {category.isPending ? (
        <ScreenState kind="loading" title="주제를 불러오고 있습니다." />
      ) : category.isError ? (
        <ScreenState
          kind={
            category.error instanceof HelpApiError &&
            category.error.status === 404
              ? 'not-found'
              : 'error'
          }
          title="주제를 불러올 수 없습니다."
          action={<RetryButton onClick={() => void category.refetch()} />}
        />
      ) : (
        <>
          <h1>{category.data.title}</h1>
          <p>{category.data.description}</p>
          {category.data.sections?.length ? (
            <ul>
              {category.data.sections.map((section) => (
                <li key={section.slug}>
                  <Link to={`/sections/${encodeURIComponent(section.slug)}`}>
                    {section.title}
                  </Link>
                  <p>{section.description}</p>
                </li>
              ))}
            </ul>
          ) : (
            <ScreenState kind="empty" title="등록된 섹션이 없습니다." />
          )}
        </>
      )}
    </div>
  )
}
export function HelpSectionPage() {
  const scope = useHelpScope()
  const { sectionSlug = '' } = useParams()
  const section = useInfiniteQuery({
    enabled: scope[1] !== 'loading',
    queryKey: [...scope, 'section-pages', sectionSlug],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      getHelpSection(sectionSlug, pageParam, signal),
    getNextPageParam: (page) =>
      page.hasMore ? (page.nextCursor ?? undefined) : undefined,
  })
  const first = section.data?.pages[0]
  const articles = section.data?.pages.flatMap((page) => page.articles) ?? []
  return (
    <div className="customer-page">
      <Link to="/categories">모든 문서</Link>
      {section.isPending && (
        <ScreenState kind="loading" title="문서 목록을 불러오고 있습니다." />
      )}
      {section.isError && (
        <ScreenState
          kind={
            section.error instanceof HelpApiError &&
            section.error.status === 404
              ? 'not-found'
              : 'error'
          }
          title="문서 목록을 불러올 수 없습니다."
          action={<RetryButton onClick={() => void section.refetch()} />}
        />
      )}
      {first && (
        <>
          <h1>{first.title}</h1>
          <p>{first.description}</p>
          {articles.length ? (
            <ul>
              {articles.map((article) => (
                <li key={article.slug}>
                  <Link to={`/articles/${encodeURIComponent(article.slug)}`}>
                    {article.title}
                  </Link>
                  <p>{article.summary}</p>
                </li>
              ))}
            </ul>
          ) : (
            <ScreenState kind="empty" title="등록된 문서가 없습니다." />
          )}
        </>
      )}
      {section.hasNextPage && (
        <DsButton
          disabled={section.isFetchingNextPage}
          onClick={() => void section.fetchNextPage()}
        >
          {section.isFetchingNextPage ? '불러오는 중…' : '문서 더 보기'}
        </DsButton>
      )}
    </div>
  )
}
