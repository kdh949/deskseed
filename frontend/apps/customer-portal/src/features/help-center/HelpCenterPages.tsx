import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  CustomerIcon,
  DsButton,
  RetryButton,
  ScreenState,
} from '../../design-system'
import heroImage from '../../assets/deskseed/customer-help-hero.png'
import articleImage from '../../assets/deskseed/customer-article-billing.png'
import {
  getHelpArticle,
  getHelpSection,
  listHelpCategories,
  recordHelpArticleFeedback,
  searchHelpArticles,
} from './helpCenterClient'

const fallbackTopics = [
  ['orders', '주문', '주문 상태, 배송, 반품과 교환'],
  ['billing', '결제', '청구서, 결제 수단과 환불'],
  ['technical', '기술 문제', '오류와 연결 문제 해결'],
  ['account', '계정', '프로필, 보안과 설정'],
  ['feedback', '제품 의견', '아이디어와 개선 제안'],
] as const

const featured = [
  ['getting-started', 'DeskSeed 시작하기'],
  ['update-billing-information', '결제 정보 변경 방법'],
  ['troubleshooting-login', '로그인 문제 해결'],
  ['understanding-plans', 'DeskSeed 플랜과 기능'],
  ['export-data', '데이터 내보내기'],
] as const

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
  const topics = categories.data?.length
    ? categories.data
        .slice(0, 5)
        .map(
          (item) =>
            [
              item.slug,
              item.title,
              item.description || '관련 도움말을 확인하세요.',
            ] as const,
        )
    : fallbackTopics
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (query.trim()) navigate(`/search?q=${encodeURIComponent(query.trim())}`)
  }
  return (
    <div className="customer-home">
      <section className="customer-home-hero">
        <div>
          <span className="customer-eyebrow">DeskSeed Help Center</span>
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
        <div className="customer-topic-grid">
          {topics.map(([slug, title, description], index) => (
            <Link
              className={`customer-topic customer-topic--${index + 1}`}
              key={slug}
              to={`/search?q=${encodeURIComponent(title)}`}
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
              <h3>{title}</h3>
              <p>{description}</p>
              <b aria-hidden="true">›</b>
            </Link>
          ))}
        </div>
      </section>
      <section className="customer-home-lower">
        <div className="customer-panel">
          <header>
            <h2>추천 문서</h2>
            <Link to="/search">전체 보기</Link>
          </header>
          <ul>
            {featured.map(([slug, title]) => (
              <li key={slug}>
                <CustomerIcon name="book" />
                <Link to={`/articles/${slug}`}>{title}</Link>
                <time>2026. 8.</time>
              </li>
            ))}
          </ul>
        </div>
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
                <p>{announcement.summary}</p>
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
                  <b>›</b>
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
  return (
    <aside className="customer-aside">
      <section>
        <h2>인기 주제</h2>
        {fallbackTopics.map(([slug, title]) => (
          <Link key={slug} to={`/search?q=${encodeURIComponent(title)}`}>
            <CustomerIcon name="book" />
            {title}
            <span>›</span>
          </Link>
        ))}
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
  return (
    <div className="customer-article-layout">
      <aside aria-label="이 페이지 목차" className="customer-article-nav">
        <strong>이 페이지에서</strong>
        <a href="#overview">개요</a>
        <a href="#steps">변경 방법</a>
        <a href="#troubleshooting">문제 해결</a>
        <button onClick={() => window.print()} type="button">
          문서 인쇄
        </button>
      </aside>
      <article className="customer-article">
        <span className="customer-breadcrumb">
          <Link to="/">모든 문서</Link> / 도움말
        </span>
        <header id="overview">
          <div>
            <h1>{data.title}</h1>
            <p>
              {data.summary ||
                'DeskSeed에서 필요한 설정을 안전하게 변경하는 방법을 안내합니다.'}
            </p>
            <small>업데이트 {formatDate(data.updatedAt)}</small>
          </div>
          <img alt="결제 정보 도움말 일러스트" src={articleImage} />
        </header>
        <section id="steps">
          <h2>변경 방법</h2>
          {data.blocks.length ? (
            data.blocks.map((block, index) => (
              <p key={index}>{block.text || ''}</p>
            ))
          ) : (
            <>
              <p>계정에 로그인한 뒤 설정 메뉴에서 필요한 정보를 선택하세요.</p>
              <ol>
                <li>오른쪽 위 프로필 메뉴를 엽니다.</li>
                <li>계정 설정에서 변경할 항목을 선택합니다.</li>
                <li>입력 내용을 확인하고 저장합니다.</li>
              </ol>
            </>
          )}
          <div className="customer-inline-info">
            <CustomerIcon name="info" />
            저장한 변경 사항은 다음 처리부터 적용됩니다.
          </div>
        </section>
        <section id="troubleshooting">
          <h2>문제가 계속되나요?</h2>
          <p>
            변경할 수 없거나 예상과 다른 결과가 보이면 지원팀에 문의해 주세요.
          </p>
          <Link className="customer-inline-cta" to="/requests/new">
            문의 접수
          </Link>
        </section>
      </article>
      <aside className="customer-aside customer-article-aside">
        <section>
          <h2>관련 문서</h2>
          {featured.slice(0, 4).map(([slug, title]) => (
            <Link key={slug} to={`/articles/${slug}`}>
              <CustomerIcon name="book" />
              {title}
            </Link>
          ))}
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

function formatDate(value?: string) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(
        new Date(value),
      )
    : '2026. 8. 27.'
}
