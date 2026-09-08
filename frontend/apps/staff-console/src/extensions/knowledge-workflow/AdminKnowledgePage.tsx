import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, listGroups } from '../../api/client'
import {
  SeedButton,
  SeedCheckbox,
  SeedFeedbackState,
  SeedNotice,
  SeedSelectField,
  SeedSkeletonRows,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'
import {
  AUDIENCES,
  LIFECYCLES,
  getArticle,
  listArticles,
  listCategories,
  listRevisions,
  listSections,
  saveArticle,
  saveCategory,
  saveSection,
  transitionArticle,
  type Article,
  type ArticleDraft,
  type Category,
  type Revision,
  type Section,
} from './api'
import { KnowledgeBlockEditor, KnowledgeDocument } from './KnowledgeDocument'
import './knowledge.css'
const emptyDraft = (): ArticleDraft => ({
  sectionId: '',
  slug: '',
  title: '',
  summary: '',
  changeNote: '',
  audience: { type: 'PUBLIC', groupIds: [] },
  document: { schemaVersion: 1, blocks: [{ type: 'paragraph', text: '' }] },
})
const TRANSITIONS = {
  DRAFT: [['submit-review', '검토 요청']],
  IN_REVIEW: [
    ['publish', '발행'],
    ['return-to-draft', '수정으로 돌아가기'],
  ],
  PUBLISHED: [['unpublish', '공개 중지']],
  UNPUBLISHED: [['return-to-draft', '수정으로 돌아가기']],
  ARCHIVED: [],
} as const
export function AdminKnowledgePage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: listCategories,
    retry: false,
  })
  const sections = useQuery({
    queryKey: ['knowledge-sections'],
    queryFn: listSections,
    retry: false,
  })
  const [lifecycle, setLifecycle] = useState('')
  const [cursor, setCursor] = useState<string | undefined>()
  const articles = useQuery({
    queryKey: ['knowledge-articles', lifecycle, cursor],
    queryFn: () => listArticles(lifecycle, cursor),
    retry: false,
  })
  const [selected, setSelected] = useState<Article | 'new' | null>(null)
  const [revisions, setRevisions] = useState<Revision[]>([])
  const [draft, setDraft] = useState(emptyDraft)
  const [savedDraft, setSavedDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  const [mustRefresh, setMustRefresh] = useState(false)
  const [hierarchy, setHierarchy] = useState<{
    kind: 'category' | 'section'
    existing?: Category | Section
  } | null>(null)
  const [taxonomy, setTaxonomy] = useState({
    categoryId: '',
    slug: '',
    title: '',
    description: '',
    displayOrder: 0,
  })
  const [groupPage, setGroupPage] = useState(0)
  const groups = useQuery({
    queryKey: ['knowledge-audience-groups', groupPage],
    queryFn: () => listGroups(groupPage),
    enabled: !!selected && draft.audience.type === 'SELECTED_STAFF_GROUPS',
    retry: false,
  })
  const setFailure = (failure: unknown) => {
    setError(failure)
    setMustRefresh(
      !(failure instanceof ApiError) ||
        [409, 412].includes(failure.status) ||
        failure.status >= 500,
    )
  }
  const read = async (id: string, preserve = false) => {
    const [article, versions] = await Promise.all([
      getArticle(id),
      listRevisions(id),
    ])
    const revision = versions[0]
    if (!revision) throw new Error('문서 버전을 찾지 못했습니다.')
    setSelected(article)
    setRevisions(versions)
    if (!preserve) {
      const nextDraft = {
        sectionId: article.sectionId,
        slug: article.slug,
        title: revision.title,
        summary: revision.summary,
        changeNote: '',
        document: revision.document,
        audience: article.audience,
      }
      setDraft(nextDraft)
      setSavedDraft(JSON.stringify(nextDraft))
    }
  }
  const open = async (id: string) => {
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      await read(id)
      setMustRefresh(false)
    } catch (failure) {
      setFailure(failure)
    } finally {
      setBusy(false)
    }
  }
  const refresh = async () => {
    setBusy(true)
    try {
      const [currentCategories, currentSections, currentArticles] =
        await Promise.all([
          categories.refetch(),
          sections.refetch(),
          articles.refetch(),
        ])
      for (const result of [
        currentCategories,
        currentSections,
        currentArticles,
      ])
        if (result.error) throw result.error
      if (hierarchy?.existing) {
        const values =
          hierarchy.kind === 'category'
            ? currentCategories.data
            : currentSections.data
        const existing = values?.find(
          (value) => value.id === hierarchy.existing?.id,
        )
        if (existing) setHierarchy({ ...hierarchy, existing })
      }
      if (selected && selected !== 'new') await read(selected.id, true)
      setMustRefresh(false)
      setError(null)
    } catch (failure) {
      setFailure(failure)
    } finally {
      setBusy(false)
    }
  }
  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || mustRefresh) return
    setBusy(true)
    setError(null)
    try {
      const article = await saveArticle(
        draft,
        selected && selected !== 'new' ? selected : undefined,
      )
      await read(article.id)
      await articles.refetch()
      setMessage('초안을 저장했습니다. 내용을 확인한 뒤 검토를 요청하세요.')
    } catch (failure) {
      setFailure(failure)
    } finally {
      setBusy(false)
    }
  }
  const transition = async (action: string) => {
    if (!selected || selected === 'new' || busy || mustRefresh) return
    setBusy(true)
    setError(null)
    try {
      const article = await transitionArticle(selected, action)
      await read(article.id)
      await articles.refetch()
      setMessage(`문서 상태: ${LIFECYCLES[article.lifecycle]}`)
    } catch (failure) {
      setFailure(failure)
    } finally {
      setBusy(false)
    }
  }
  const editHierarchy = (
    kind: 'category' | 'section',
    existing?: Category | Section,
  ) => {
    setHierarchy({ kind, existing })
    setTaxonomy({
      categoryId:
        existing && 'categoryId' in existing ? existing.categoryId : '',
      slug: existing?.slug ?? '',
      title: existing?.title ?? '',
      description: existing?.description ?? '',
      displayOrder: existing?.displayOrder ?? 0,
    })
    setError(null)
    setMustRefresh(false)
  }
  const saveHierarchy = async (event: FormEvent) => {
    event.preventDefault()
    if (!hierarchy || busy || mustRefresh) return
    setBusy(true)
    try {
      const { categoryId, ...fields } = taxonomy
      if (hierarchy.kind === 'category')
        await saveCategory(fields, hierarchy.existing)
      else
        await saveSection(
          { ...fields, categoryId },
          hierarchy.existing as Section | undefined,
        )
      await Promise.all([categories.refetch(), sections.refetch()])
      setHierarchy(null)
      setMessage('분류를 저장했습니다.')
    } catch (failure) {
      setFailure(failure)
    } finally {
      setBusy(false)
    }
  }
  const failure = error || categories.error || sections.error || articles.error
  const editable = selected === 'new' || selected?.lifecycle === 'DRAFT'
  const dirty = editable && JSON.stringify(draft) !== savedDraft
  return (
    <section className="knowledge-page">
      <header>
        <h1>지식 문서</h1>
        <p>도움말을 분류하고 초안 작성·검토·발행을 관리합니다.</p>
      </header>
      <div className="knowledge-actions">
        <SeedButton
          disabled={busy || !sections.data}
          onClick={() => {
            setSelected('new')
            setDraft(emptyDraft())
            setRevisions([])
            setError(null)
            setMustRefresh(false)
          }}
        >
          문서 만들기
        </SeedButton>
        <SeedButton disabled={busy} onClick={() => void refresh()}>
          최신 내용 확인
        </SeedButton>
        <SeedButton disabled={busy} onClick={() => editHierarchy('category')}>
          카테고리 만들기
        </SeedButton>
        <SeedButton
          disabled={busy || !categories.data?.length}
          onClick={() => editHierarchy('section')}
        >
          섹션 만들기
        </SeedButton>
      </div>
      {Boolean(failure) && (
        <SeedNotice
          tone={mustRefresh ? 'warning' : 'danger'}
          title={
            failure instanceof ApiError && failure.status === 403
              ? '문서를 관리할 권한이 없습니다.'
              : mustRefresh
                ? '최신 내용을 확인하세요.'
                : '문서 요청을 완료하지 못했습니다.'
          }
        >
          {mustRefresh
            ? '입력은 유지됩니다. 최신 내용 확인 후 변경 사항을 비교하고 다시 저장하세요.'
            : '입력과 권한을 확인한 뒤 다시 시도하세요.'}
        </SeedNotice>
      )}
      {message && <p role="status">{message}</p>}
      <details>
        <summary>카테고리와 섹션 관리</summary>
        <ul className="knowledge-list">
          {categories.data?.map((c) => (
            <li key={c.id}>
              <strong>{c.title}</strong> · {c.active ? '활성' : '비활성'}
              <SeedButton
                disabled={busy}
                onClick={() => editHierarchy('category', c)}
              >
                편집: {c.title}
              </SeedButton>
              <ul>
                {sections.data
                  ?.filter((s) => s.categoryId === c.id)
                  .map((s) => (
                    <li key={s.id}>
                      {s.title}
                      <SeedButton
                        disabled={busy}
                        onClick={() => editHierarchy('section', s)}
                      >
                        편집: {s.title}
                      </SeedButton>
                    </li>
                  ))}
              </ul>
            </li>
          ))}
        </ul>
      </details>
      {hierarchy && (
        <form
          className="knowledge-editor"
          onSubmit={saveHierarchy}
          aria-label="지식 분류 편집"
        >
          <h2>{hierarchy.kind === 'category' ? '카테고리' : '섹션'} 편집</h2>
          <fieldset disabled={busy}>
            {hierarchy.kind === 'section' && (
              <SeedSelectField
                label="카테고리"
                required
                value={taxonomy.categoryId}
                onChange={(e) =>
                  setTaxonomy({ ...taxonomy, categoryId: e.target.value })
                }
              >
                <option value="">선택하세요</option>
                {categories.data
                  ?.filter((c) => c.active)
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.title}
                    </option>
                  ))}
              </SeedSelectField>
            )}
            <SeedTextField
              label="분류 이름"
              required
              value={taxonomy.title}
              maxLength={200}
              onChange={(e) =>
                setTaxonomy({ ...taxonomy, title: e.target.value })
              }
            />
            <SeedTextField
              label="분류 주소 식별자"
              pattern="[a-z0-9]+(-[a-z0-9]+)*"
              required
              value={taxonomy.slug}
              maxLength={120}
              onChange={(e) =>
                setTaxonomy({ ...taxonomy, slug: e.target.value })
              }
            />
            <SeedTextField
              label="분류 설명"
              value={taxonomy.description}
              maxLength={1000}
              onChange={(e) =>
                setTaxonomy({ ...taxonomy, description: e.target.value })
              }
            />
            <SeedTextField
              label="표시 순서"
              type="number"
              min={0}
              required
              value={taxonomy.displayOrder}
              onChange={(e) =>
                setTaxonomy({
                  ...taxonomy,
                  displayOrder: Number(e.target.value),
                })
              }
            />
            <div className="knowledge-actions">
              <SeedButton type="submit" disabled={mustRefresh}>
                분류 저장
              </SeedButton>
              <SeedButton onClick={() => setHierarchy(null)}>
                분류 편집 닫기
              </SeedButton>
            </div>
          </fieldset>
        </form>
      )}
      <SeedSelectField
        label="문서 상태 필터"
        value={lifecycle}
        onChange={(e) => {
          setLifecycle(e.target.value)
          setCursor(undefined)
        }}
      >
        <option value="">모든 상태</option>
        {Object.entries(LIFECYCLES).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </SeedSelectField>
      {articles.isPending ? (
        <SeedSkeletonRows />
      ) : (
        <ul className="knowledge-list">
          {articles.data?.items.map((a) => (
            <li key={a.id}>
              <div>
                <strong>{a.currentPublishedRevision?.title ?? a.slug}</strong>
                <p>
                  {LIFECYCLES[a.lifecycle]} · {AUDIENCES[a.audience.type]}
                </p>
              </div>
              <SeedButton disabled={busy} onClick={() => void open(a.id)}>
                열기: {a.currentPublishedRevision?.title ?? a.slug}
              </SeedButton>
            </li>
          ))}
        </ul>
      )}
      {articles.data?.items.length === 0 && (
        <SeedFeedbackState
          kind="empty"
          title="이 상태의 문서가 없습니다."
          description="문서를 만들거나 상태 필터를 변경하세요."
        />
      )}
      <div className="knowledge-actions">
        {cursor && (
          <SeedButton onClick={() => setCursor(undefined)}>첫 목록</SeedButton>
        )}
        {articles.data?.nextCursor && (
          <SeedButton onClick={() => setCursor(articles.data!.nextCursor!)}>
            다음 목록
          </SeedButton>
        )}
      </div>
      {selected && (
        <section className="knowledge-editor">
          <header>
            <h2>{selected === 'new' ? '새 문서' : draft.title}</h2>
            {selected !== 'new' && (
              <p>
                {LIFECYCLES[selected.lifecycle]} · 최신 본문 버전{' '}
                {revisions[0]?.revisionNumber}
              </p>
            )}
          </header>
          {dirty && selected !== 'new' && (
            <p>검토 요청 전에 변경한 초안을 저장하세요.</p>
          )}
          {selected !== 'new' && (
            <div className="knowledge-actions">
              {TRANSITIONS[selected.lifecycle].map(([action, label]) => (
                <SeedButton
                  key={action}
                  disabled={busy || mustRefresh || dirty}
                  onClick={() => void transition(action)}
                >
                  {label}
                </SeedButton>
              ))}
              {selected.lifecycle !== 'ARCHIVED' && (
                <SeedButton
                  disabled={busy || mustRefresh || dirty}
                  onClick={() => void transition('archive')}
                >
                  보관
                </SeedButton>
              )}
            </div>
          )}
          {selected !== 'new' && selected.lifecycle === 'PUBLISHED' && (
            <SeedNotice title="발행 문서 수정" tone="info">
              공개 중지 후 수정으로 돌아갈 수 있습니다. 다시 발행할 때까지
              고객이 이 문서를 열 수 없습니다.
            </SeedNotice>
          )}
          {editable ? (
            <form onSubmit={save} aria-label="지식 문서 편집">
              <fieldset disabled={busy}>
                <legend>문서 정보</legend>
                <SeedSelectField
                  label="문서 섹션"
                  required
                  value={draft.sectionId}
                  onChange={(e) =>
                    setDraft({ ...draft, sectionId: e.target.value })
                  }
                >
                  <option value="">선택하세요</option>
                  {sections.data
                    ?.filter((s) => s.active)
                    .map((s) => (
                      <option key={s.id} value={s.id}>
                        {
                          categories.data?.find((c) => c.id === s.categoryId)
                            ?.title
                        }{' '}
                        / {s.title}
                      </option>
                    ))}
                </SeedSelectField>
                <SeedTextField
                  label="문서 제목"
                  required
                  maxLength={300}
                  value={draft.title}
                  onChange={(e) =>
                    setDraft({ ...draft, title: e.target.value })
                  }
                />
                <SeedTextField
                  label="문서 주소 식별자"
                  required
                  maxLength={120}
                  pattern="[a-z0-9]+(-[a-z0-9]+)*"
                  value={draft.slug}
                  onChange={(e) => setDraft({ ...draft, slug: e.target.value })}
                />
                <SeedTextAreaField
                  label="문서 요약"
                  value={draft.summary}
                  maxLength={1000}
                  onChange={(e) =>
                    setDraft({ ...draft, summary: e.target.value })
                  }
                />
                <SeedSelectField
                  label="문서 공개 범위"
                  value={draft.audience.type}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      audience: {
                        type: e.target
                          .value as ArticleDraft['audience']['type'],
                        groupIds: [],
                      },
                    })
                  }
                >
                  {Object.entries(AUDIENCES).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </SeedSelectField>
                {draft.audience.type === 'SELECTED_STAFF_GROUPS' && (
                  <fieldset>
                    <legend>
                      열람 가능한 직원 그룹 ({draft.audience.groupIds.length}개
                      선택)
                    </legend>
                    {groups.error && (
                      <p role="alert">그룹을 불러오지 못했습니다.</p>
                    )}
                    {groups.data?.items
                      .filter((g) => g.status === 'ACTIVE')
                      .map((g) => (
                        <SeedCheckbox
                          key={g.id}
                          label={g.name}
                          checked={draft.audience.groupIds.includes(g.id)}
                          onChange={(e) =>
                            setDraft({
                              ...draft,
                              audience: {
                                ...draft.audience,
                                groupIds: e.target.checked
                                  ? [...draft.audience.groupIds, g.id]
                                  : draft.audience.groupIds.filter(
                                      (id) => id !== g.id,
                                    ),
                              },
                            })
                          }
                        />
                      ))}
                    <div className="knowledge-actions">
                      <SeedButton
                        disabled={groupPage === 0}
                        onClick={() => setGroupPage(groupPage - 1)}
                      >
                        이전 그룹
                      </SeedButton>
                      <SeedButton
                        disabled={
                          !groups.data ||
                          groupPage + 1 >= groups.data.totalPages
                        }
                        onClick={() => setGroupPage(groupPage + 1)}
                      >
                        다음 그룹
                      </SeedButton>
                    </div>
                  </fieldset>
                )}
                <KnowledgeBlockEditor
                  document={draft.document}
                  onChange={(document) => setDraft({ ...draft, document })}
                />
                <SeedTextField
                  label="변경 메모"
                  value={draft.changeNote}
                  maxLength={1000}
                  onChange={(e) =>
                    setDraft({ ...draft, changeNote: e.target.value })
                  }
                />
                <SeedButton
                  type="submit"
                  variant="primary"
                  disabled={
                    mustRefresh ||
                    (draft.audience.type === 'SELECTED_STAFF_GROUPS' &&
                      !draft.audience.groupIds.length)
                  }
                >
                  초안 저장
                </SeedButton>
              </fieldset>
            </form>
          ) : (
            <KnowledgeDocument document={draft.document} />
          )}
          {editable && (
            <details>
              <summary>문서 미리보기</summary>
              <KnowledgeDocument document={draft.document} />
            </details>
          )}
          {revisions[0] && editable && (
            <details>
              <summary>서버의 최신 저장 내용 비교</summary>
              <h3>{revisions[0].title}</h3>
              <KnowledgeDocument document={revisions[0].document} />
            </details>
          )}
          {revisions.length > 0 && (
            <details>
              <summary>버전 이력</summary>
              <ul>
                {revisions.map((v) => (
                  <li key={v.id}>
                    버전 {v.revisionNumber} · {v.title} ·{' '}
                    {new Date(v.createdAt).toLocaleString('ko-KR')}{' '}
                    {v.changeNote}
                  </li>
                ))}
              </ul>
            </details>
          )}
          <SeedButton disabled={busy} onClick={() => setSelected(null)}>
            문서 닫기
          </SeedButton>
        </section>
      )}
    </section>
  )
}
