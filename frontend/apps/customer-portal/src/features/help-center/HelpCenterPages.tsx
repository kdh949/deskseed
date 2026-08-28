import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
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
  getHelpSection,
  listHelpCategories,
  recordHelpArticleFeedback,
  searchHelpArticles,
  type HelpCategory,
} from './helpCenterClient'

export function HelpCenterHomePage() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const categories = useQuery({
    queryKey: ['help', 'categories'],
    queryFn: listHelpCategories,
  })
  const announcements = useQuery({
    queryKey: ['help', 'section', 'announcements'],
    queryFn: () => getHelpSection('announcements'),
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
        <form onSubmit={submit} role="search">
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
          <Link to="/search">
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
            <Link to="/search?q=공지">전체 보기</Link>
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
  const [parameters, setParameters] = useSearchParams()
  const initial = parameters.get('q') ?? ''
  const [query, setQuery] = useState(initial)
  const normalized = initial.trim()
  const results = useQuery({
    enabled: Boolean(normalized),
    queryKey: ['help', 'search', normalized],
    queryFn: () => searchHelpArticles(normalized),
  })
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
        </p>
        <form onSubmit={submit} role="search">
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
          results.data.length ? (
            <div className="customer-search-results">
              {results.data.map((item, index) => (
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
      </div>
      <SearchSidebar />
    </div>
  )
}

function SearchSidebar() {
  const categories = useQuery({
    queryKey: ['help', 'categories'],
    queryFn: listHelpCategories,
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
  const { articleSlug = '' } = useParams()
  const article = useQuery({
    queryKey: ['help', 'article', articleSlug],
    queryFn: () => getHelpArticle(articleSlug),
  })
  const [feedback, setFeedback] = useState<'yes' | 'no' | null>(null)
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
          kind="not-found"
          title="문서를 찾을 수 없습니다."
        />
      </div>
    )
  const data = article.data
  const textBlocks = data.blocks.flatMap((block, index) => {
    const text = block.text?.trim()
    return text ? [{ key: `${block.type}-${index}`, text }] : []
  })
  return (
    <div className="customer-article-layout customer-article-layout--content-only">
      <article className="customer-article">
        <span className="customer-breadcrumb">
          <Link to="/search">모든 문서</Link> / 도움말
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
        {textBlocks.length ? (
          <section aria-label="문서 본문" className="customer-article-body">
            {textBlocks.map((block) => (
              <p key={block.key}>{block.text}</p>
            ))}
          </section>
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
          {feedback ? (
            <p>의견을 보내주셔서 감사합니다.</p>
          ) : (
            <>
              <DsButton
                onClick={() => {
                  setFeedback('yes')
                  void recordHelpArticleFeedback(articleSlug, true)
                }}
              >
                네, 도움이 됐어요
              </DsButton>
              <DsButton
                onClick={() => {
                  setFeedback('no')
                  void recordHelpArticleFeedback(articleSlug, false)
                }}
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
      {query.data.slice(0, 5).map((item, index) => (
        <CategoryCard category={item} index={index} key={item.slug} />
      ))}
    </div>
  )
}

function CategoryLinks({ query }: { query: CategoriesQuery }) {
  if (query.isPending) return <p role="status">주제를 불러오고 있습니다.</p>
  if (query.isError) return <p role="alert">주제를 불러올 수 없습니다.</p>
  if (!query.data.length) return <p>등록된 주제가 없습니다.</p>
  return query.data.slice(0, 5).map((item) => (
    <Link key={item.slug} to={`/search?q=${encodeURIComponent(item.title)}`}>
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
      className={`customer-topic customer-topic--${index + 1}`}
      to={`/search?q=${encodeURIComponent(category.title)}`}
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
