import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useParams } from 'react-router'
import { ApiError } from '../../api/client'
import {
  SeedButton,
  SeedContextCard,
  SeedDrawer,
  SeedNotice,
  SeedTextField,
} from '../../design-system/canonical'
import {
  AUDIENCES,
  articleLink,
  readKnowledge,
  searchKnowledge,
  suggestKnowledge,
  type Article,
  type SearchPage,
} from './api'
import { KnowledgeDocument } from './KnowledgeDocument'
import './knowledge.css'

type Props = {
  ticketNumber?: number
  mode?: 'PUBLIC' | 'INTERNAL'
  onInsert?: (title: string, url: string) => void
  disabled?: boolean
  initialSlug?: string
}
export function AgentKnowledgePanel({
  ticketNumber,
  mode = 'PUBLIC',
  onInsert,
  disabled,
  initialSlug,
}: Props) {
  const [query, setQuery] = useState('')
  const [searched, setSearched] = useState('')
  const [results, setResults] = useState<SearchPage | null>(null)
  const [article, setArticle] = useState<Article | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  useEffect(() => {
    if (!initialSlug) return
    let active = true
    setBusy(true)
    readKnowledge(initialSlug)
      .then((value) => {
        if (active) setArticle(value)
      })
      .catch((failure) => {
        if (active) setError(failure)
      })
      .finally(() => {
        if (active) setBusy(false)
      })
    return () => {
      active = false
    }
  }, [initialSlug])
  const search = async (event?: FormEvent, cursor?: string) => {
    event?.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const text = cursor ? searched : query.trim()
      const page = await searchKnowledge(text, cursor)
      setSearched(text)
      setResults(page)
      setArticle(null)
    } catch (failure) {
      setError(failure)
    } finally {
      setBusy(false)
    }
  }
  const suggest = async () => {
    if (!ticketNumber || busy) return
    setBusy(true)
    setError(null)
    try {
      setResults(await suggestKnowledge(ticketNumber))
      setSearched('')
      setArticle(null)
    } catch (failure) {
      setError(failure)
    } finally {
      setBusy(false)
    }
  }
  const open = async (slug: string) => {
    if (busy) return
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      setArticle(await readKnowledge(slug))
    } catch (failure) {
      setError(failure)
      setArticle(null)
    } finally {
      setBusy(false)
    }
  }
  const insert = async () => {
    if (!article || !onInsert || busy || disabled) return
    setBusy(true)
    setError(null)
    try {
      const latest = await readKnowledge(article.slug)
      setArticle(latest)
      const url = articleLink(latest, mode, window.location.origin)
      if (!url) {
        setMessage('직원 전용 문서는 내부 메모에만 삽입할 수 있습니다.')
        return
      }
      onInsert(latest.currentPublishedRevision!.title, url)
      setMessage(
        `${mode === 'PUBLIC' ? '공개 답변' : '내부 메모'} 초안에 문서 링크를 넣었습니다.`,
      )
    } catch (failure) {
      setError(failure)
    } finally {
      setBusy(false)
    }
  }
  const permitted =
    article && articleLink(article, mode, window.location.origin)
  return (
    <section className="knowledge-panel" aria-label="상담 지식 검색">
      <form onSubmit={(event) => void search(event)}>
        <SeedTextField
          label="지식 검색어"
          required
          maxLength={512}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="knowledge-actions">
          <SeedButton type="submit" disabled={busy || !query.trim()}>
            지식 검색
          </SeedButton>
          {ticketNumber && (
            <SeedButton disabled={busy} onClick={() => void suggest()}>
              이 티켓의 추천 문서
            </SeedButton>
          )}
        </div>
      </form>
      {Boolean(error) && (
        <SeedNotice
          title={
            error instanceof ApiError && [403, 404].includes(error.status)
              ? '문서 접근 권한을 확인하세요.'
              : '지식을 불러오지 못했습니다.'
          }
          tone="danger"
        >
          권한이 없거나 문서가 공개 중지되었을 수 있습니다. 다시 검색해 주세요.
        </SeedNotice>
      )}
      {busy && <p role="status">문서를 확인하고 있습니다.</p>}
      {message && <p role="status">{message}</p>}
      {results?.items.length === 0 && (
        <p>검색 결과가 없습니다. 다른 검색어로 찾아보세요.</p>
      )}
      <ul className="knowledge-list">
        {results?.items.map((hit) => (
          <li key={hit.articleSlug}>
            <div>
              <strong>{hit.title}</strong>
              <p>
                {AUDIENCES[hit.audience]} · {hit.categoryTitle} /{' '}
                {hit.sectionTitle}
              </p>
              <p>{hit.excerpt}</p>
            </div>
            <SeedButton
              disabled={busy}
              onClick={() => void open(hit.articleSlug)}
            >
              읽기: {hit.title}
            </SeedButton>
          </li>
        ))}
      </ul>
      {results?.nextCursor && searched && (
        <SeedButton
          disabled={busy}
          onClick={() => void search(undefined, results.nextCursor!)}
        >
          다음 검색 결과
        </SeedButton>
      )}
      {article?.currentPublishedRevision && (
        <article>
          <h2>{article.currentPublishedRevision.title}</h2>
          <p>
            {AUDIENCES[article.audience.type]} · 문서 버전{' '}
            {article.currentPublishedRevision.revisionNumber}
          </p>
          {article.audience.type === 'SIGNED_IN_CUSTOMER' && (
            <p>이 문서 링크는 고객 로그인이 필요합니다.</p>
          )}
          <KnowledgeDocument
            document={article.currentPublishedRevision.document}
          />
          {onInsert && (
            <>
              <SeedButton
                disabled={busy || disabled || !permitted}
                onClick={() => void insert()}
              >
                현재 답변에 링크 삽입
              </SeedButton>
              {!permitted && (
                <p>직원 전용 문서는 내부 메모에만 삽입할 수 있습니다.</p>
              )}
            </>
          )}
        </article>
      )}
    </section>
  )
}
export function TicketKnowledge({
  ticketNumber,
  mode,
  onInsert,
  disabled,
}: Props) {
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  return (
    <SeedContextCard title="지식 문서">
      <SeedButton ref={button} onClick={() => setOpen(true)}>
        문서 검색 열기
      </SeedButton>
      <SeedDrawer
        title="지식 문서 검색"
        description="문서를 확인하고 현재 답변 초안에 링크를 넣습니다."
        open={open}
        onClose={() => setOpen(false)}
        returnFocusRef={button}
      >
        {open && (
          <AgentKnowledgePanel
            ticketNumber={ticketNumber}
            mode={mode}
            onInsert={onInsert}
            disabled={disabled}
          />
        )}
      </SeedDrawer>
    </SeedContextCard>
  )
}
export function AgentKnowledgeArticlePage() {
  const { slug } = useParams()
  return (
    <div className="knowledge-page">
      <h1>지식 문서</h1>
      <AgentKnowledgePanel initialSlug={slug} />
    </div>
  )
}
