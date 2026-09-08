import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import {
  SeedButton,
  SeedCheckbox,
  SeedDrawer,
  SeedNotice,
  SeedSelectField,
  SeedTextField,
  SeedTextAreaField,
} from '../../design-system/canonical'
import { listForms } from './api'
import {
  listTags,
  listStatuses,
  saveTag,
  saveStatus,
  type Tag,
  type CustomStatus,
} from './labels-api'
import './configuration.css'

const CATEGORIES = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  HOLD: '보류',
  SOLVED: '해결',
}
export function AdminTicketLabelsPage({ kind }: { kind: 'tags' | 'statuses' }) {
  const title = kind === 'tags' ? '티켓 태그' : '업무 상태'
  const tagQuery = useQuery({
    queryKey: ['admin-ticket-tags'],
    queryFn: listTags,
    enabled: kind === 'tags',
    retry: false,
  })
  const statusQuery = useQuery({
    queryKey: ['admin-ticket-statuses'],
    queryFn: listStatuses,
    enabled: kind === 'statuses',
    retry: false,
  })
  const forms = useQuery({
    queryKey: ['admin-ticket-forms'],
    queryFn: listForms,
    enabled: kind === 'statuses',
    retry: false,
  })
  const query = kind === 'tags' ? tagQuery : statusQuery
  const trigger = useRef<HTMLButtonElement>(null)
  const [editor, setEditor] = useState<{
    tag?: Tag
    status?: CustomStatus
  } | null>(null)
  const [key, setKey] = useState('')
  const [label, setLabel] = useState('')
  const [customerLabel, setCustomerLabel] = useState('')
  const [category, setCategory] = useState('OPEN')
  const [active, setActive] = useState(true)
  const [isDefault, setIsDefault] = useState(false)
  const [allowedForms, setAllowedForms] = useState<string[]>([])
  const [description, setDescription] = useState('')
  const [order, setOrder] = useState(0)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [conflict, setConflict] = useState(false)
  const edit = (next: { tag?: Tag; status?: CustomStatus }) => {
    setEditor(next)
    setKey(next.tag?.value ?? next.status?.machineKey ?? '')
    setLabel(next.tag?.label ?? next.status?.agentLabel ?? '')
    setCustomerLabel(next.status?.customerLabel ?? '')
    setCategory(next.status?.statusCategory ?? 'OPEN')
    setActive(next.tag?.active ?? next.status?.active ?? true)
    setIsDefault(next.status?.defaultForCategory ?? false)
    setAllowedForms(next.status?.allowedFormIds ?? [])
    setDescription(next.status?.description ?? '')
    setOrder(next.status?.order ?? statusQuery.data?.length ?? 0)
    setError('')
    setUncertain(false)
    setConflict(false)
  }
  const save = async () => {
    if (!editor || busy || uncertain || conflict) return
    setBusy(true)
    setError('')
    try {
      if (kind === 'tags')
        await saveTag(
          { value: key.trim(), label: label.trim(), active },
          editor.tag,
        )
      else
        await saveStatus(
          {
            machineKey: key.trim(),
            agentLabel: label.trim(),
            customerLabel: customerLabel.trim() || null,
            statusCategory: category,
            active,
            order,
            defaultForCategory: active && isDefault,
            allowedFormIds: allowedForms,
            description: description.trim() || null,
          },
          editor.status,
        )
      setEditor(null)
      await query.refetch()
    } catch (cause) {
      const definite =
        cause instanceof ApiError && cause.status >= 400 && cause.status < 500
      const stale =
        cause instanceof ApiError &&
        (cause.status === 409 || cause.status === 412)
      setConflict(stale)
      if (!definite && !editor.tag && !editor.status) setUncertain(true)
      setError(
        stale
          ? '다른 변경과 충돌했습니다. 입력을 유지한 채 최신 목록을 확인해 주세요.'
          : !definite
            ? '저장 결과를 확인하지 못했습니다. 목록을 확인한 후 다시 편집해 주세요.'
            : '저장하지 못했습니다. 입력과 권한을 확인해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  const reload = async () => {
    const result = await query.refetch()
    if (result.error) return
    if (uncertain) {
      setEditor(null)
      return
    }
    if (editor?.tag) {
      const latest = (result.data as Tag[]).find(
        (item) => item.id === editor.tag?.id,
      )
      if (latest) {
        setEditor({ tag: latest })
        setConflict(false)
      }
    }
    if (editor?.status) {
      const latest = (result.data as CustomStatus[]).find(
        (item) => item.id === editor.status?.id,
      )
      if (latest) {
        setEditor({ status: latest })
        setConflict(false)
      }
    }
  }
  return (
    <section className="configuration-page">
      <header className="configuration-header">
        <div>
          <h1>{title}</h1>
          <p>
            {kind === 'tags'
              ? '반복되는 문의와 업무를 태그로 분류합니다.'
              : '기본 처리 단계에 맞춰 팀의 업무 상태를 관리합니다.'}
          </p>
        </div>
        <SeedButton ref={trigger} variant="primary" onClick={() => edit({})}>
          새 {kind === 'tags' ? '태그' : '상태'}
        </SeedButton>
      </header>
      {query.isPending ? <p role="status">목록을 불러오는 중…</p> : null}
      {query.error ? (
        <SeedNotice
          title={
            query.error instanceof ApiError && query.error.status === 403
              ? '관리 권한이 없습니다.'
              : '목록을 불러오지 못했습니다.'
          }
          tone="warning"
          action={
            <SeedButton onClick={() => void query.refetch()}>
              다시 시도
            </SeedButton>
          }
        />
      ) : null}
      {query.data?.length === 0 ? <p>등록된 {title}가 없습니다.</p> : null}
      <div className="configuration-list">
        {kind === 'tags'
          ? tagQuery.data?.map((tag) => (
              <article className="configuration-list-item" key={tag.id}>
                <div>
                  <h2>{tag.label}</h2>
                  <p>
                    {tag.value} · {tag.active ? '사용 중' : '사용 중지'}
                  </p>
                  {tag.highCardinalityWarning ? (
                    <p>많은 티켓에 사용되는 태그입니다.</p>
                  ) : null}
                </div>
                <SeedButton onClick={() => edit({ tag })}>
                  {tag.label} 편집
                </SeedButton>
              </article>
            ))
          : statusQuery.data?.map((status) => (
              <article className="configuration-list-item" key={status.id}>
                <div>
                  <h2>{status.agentLabel}</h2>
                  <p>
                    {
                      CATEGORIES[
                        status.statusCategory as keyof typeof CATEGORIES
                      ]
                    }{' '}
                    · {status.active ? '사용 중' : '사용 중지'}
                    {status.defaultForCategory ? ' · 단계 기본 상태' : ''}
                  </p>
                </div>
                <SeedButton onClick={() => edit({ status })}>
                  {status.agentLabel} 편집
                </SeedButton>
              </article>
            ))}
      </div>
      <SeedDrawer
        open={Boolean(editor)}
        onClose={() => setEditor(null)}
        returnFocusRef={trigger}
        title={`${title} ${editor?.tag || editor?.status ? '편집' : '추가'}`}
      >
        {error ? <SeedNotice title={error} tone="warning" /> : null}
        {conflict || uncertain ? (
          <SeedButton onClick={() => void reload()}>최신 목록 확인</SeedButton>
        ) : null}
        <form
          className="configuration-editor"
          onSubmit={(event) => {
            event.preventDefault()
            void save()
          }}
        >
          <fieldset
            className="configuration-controls"
            disabled={busy || uncertain}
          >
            <SeedTextField
              label="식별 이름"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              required
              maxLength={80}
              disabled={Boolean(editor?.tag || editor?.status)}
              hint="저장 후 변경할 수 없는 영문 식별 이름입니다."
            />
            <SeedTextField
              label="상담사 표시 이름"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              required
              maxLength={120}
            />
            <SeedCheckbox
              label="사용"
              checked={active}
              onChange={(e) => {
                setActive(e.target.checked)
                if (!e.target.checked) setIsDefault(false)
              }}
            />
            {kind === 'statuses' ? (
              <>
                <SeedSelectField
                  label="기본 처리 단계"
                  value={category}
                  disabled={Boolean(editor?.status)}
                  onChange={(e) => setCategory(e.target.value)}
                >
                  {Object.entries(CATEGORIES).map(([value, text]) => (
                    <option key={value} value={value}>
                      {text}
                    </option>
                  ))}
                </SeedSelectField>
                <SeedTextField
                  label="고객 표시 이름"
                  value={customerLabel}
                  onChange={(e) => setCustomerLabel(e.target.value)}
                  maxLength={120}
                />
                <SeedCheckbox
                  label="이 처리 단계의 기본 상태"
                  checked={isDefault}
                  disabled={!active}
                  onChange={(e) => setIsDefault(e.target.checked)}
                />
                <SeedTextAreaField
                  label="설명"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  maxLength={500}
                />
                <fieldset className="configuration-controls">
                  <legend>사용할 폼</legend>
                  <p>선택하지 않으면 모든 폼에서 사용할 수 있습니다.</p>
                  {forms.isPending ? (
                    <p role="status">폼을 불러오는 중…</p>
                  ) : forms.error ? (
                    <SeedNotice
                      title="폼 목록을 불러오지 못했습니다."
                      tone="warning"
                      action={
                        <SeedButton onClick={() => void forms.refetch()}>
                          폼 다시 불러오기
                        </SeedButton>
                      }
                    />
                  ) : (
                    forms.data?.map((form) => (
                      <SeedCheckbox
                        key={form.id}
                        label={form.name}
                        checked={allowedForms.includes(form.id)}
                        onChange={(e) =>
                          setAllowedForms((current) =>
                            e.target.checked
                              ? [...current, form.id]
                              : current.filter((id) => id !== form.id),
                          )
                        }
                      />
                    ))
                  )}
                </fieldset>
              </>
            ) : null}
          </fieldset>
          <SeedButton
            type="submit"
            variant="primary"
            disabled={
              busy ||
              uncertain ||
              conflict ||
              (kind === 'statuses' && (forms.isPending || Boolean(forms.error)))
            }
          >
            {busy ? '저장 중…' : '설정 저장'}
          </SeedButton>
        </form>
      </SeedDrawer>
    </section>
  )
}
