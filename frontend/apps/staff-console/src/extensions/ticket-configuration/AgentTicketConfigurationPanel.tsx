import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import {
  SeedButton,
  SeedContextCard,
  SeedDrawer,
  SeedNotice,
  SeedCheckbox,
  SeedSelectField,
  SeedTextField,
  SeedTextAreaField,
} from '../../design-system/canonical'
import {
  getConfiguration,
  isDecimalFieldValue,
  projectConfiguration,
  saveConfiguration,
  type AgentConfiguration,
  type AgentField,
  type FieldValue,
  type ConfigurationCommand,
} from './runtime-api'
import './configuration.css'

export function AgentTicketConfigurationPanel({
  ticketNumber,
}: {
  ticketNumber: number
}) {
  const cache = useQueryClient()
  const trigger = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  const [base, setBase] = useState<AgentConfiguration | null>(null)
  const [shown, setShown] = useState<AgentConfiguration | null>(null)
  const [values, setValues] = useState<Record<string, FieldValue>>({})
  const [editedKeys, setEditedKeys] = useState<Set<string>>(new Set())
  const [tags, setTags] = useState<string[]>([])
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [projecting, setProjecting] = useState(false)
  const [error, setError] = useState('')
  const [projectionError, setProjectionError] = useState('')
  const [stale, setStale] = useState(false)
  const [attempt, setAttempt] = useState<{
    version: number
    body: ConfigurationCommand
  } | null>(null)
  const [saved, setSaved] = useState(false)
  const load = async (preserve = false) => {
    setBusy(true)
    setError('')
    try {
      const result = await getConfiguration(ticketNumber)
      setBase(result)
      setShown(result)
      setStale(false)
      if (!preserve) {
        setValues(result.fieldValues)
        setEditedKeys(new Set())
        setTags(result.tags.map((t) => t.id))
        setStatus('')
      } else {
        setValues((current) => {
          const next = { ...result.fieldValues }
          for (const key of editedKeys) {
            if (current[key]) next[key] = current[key]
            else delete next[key]
          }
          return next
        })
      }
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.status === 403
          ? '이 티켓 설정을 볼 권한이 없습니다.'
          : '설정을 불러오지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  useEffect(() => {
    if (!open || !base || attempt || stale) return
    let cancelled = false
    setProjecting(true)
    setProjectionError('')
    const timer = window.setTimeout(() => {
      const keys = new Set(base.form?.fields.map((f) => f.machineKey) ?? [])
      const candidates = Object.fromEntries(
        Object.entries(values).filter(
          ([key, value]) =>
            editedKeys.has(key) &&
            keys.has(key) &&
            value !== undefined &&
            (value.numberValue === undefined ||
              isDecimalFieldValue(value.numberValue)),
        ),
      )
      void projectConfiguration(ticketNumber, candidates, status || undefined)
        .then((result) => {
          if (!cancelled) {
            if (
              result.version !== base.version ||
              result.form?.formId !== base.form?.formId ||
              result.form?.formVersion !== base.form?.formVersion
            ) {
              setStale(true)
              setError(
                '티켓 또는 폼이 변경되었습니다. 입력을 유지한 채 최신 설정을 불러와 주세요.',
              )
            } else setShown(result)
          }
        })
        .catch(() => {
          if (!cancelled)
            setProjectionError(
              '추가 항목의 조건을 확인하지 못했습니다. 최신 설정을 다시 불러와 주세요.',
            )
        })
        .finally(() => {
          if (!cancelled) setProjecting(false)
        })
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [open, base, values, editedKeys, status, ticketNumber, attempt, stale])
  const submit = async () => {
    if (
      !base ||
      !shown ||
      busy ||
      stale ||
      (!attempt && (projecting || projectionError)) ||
      !base.writable
    )
      return
    const editable = new Set(
      shown.form?.fields
        .filter((f) => f.visible && f.editable)
        .map((f) => f.machineKey) ?? [],
    )
    const pending = attempt ?? {
      version: base.version,
      body: {
        ...(base.form
          ? { formId: base.form.formId, formVersion: base.form.formVersion }
          : {}),
        fieldValues: Object.fromEntries(
          Object.entries(values).filter(
            ([key, value]) =>
              editedKeys.has(key) && editable.has(key) && value !== undefined,
          ),
        ),
        addTagIds: tags.filter((id) => !base.tags.some((t) => t.id === id)),
        removeTagIds: base.tags
          .filter((t) => !tags.includes(t.id))
          .map((t) => t.id),
        ...(status ? { customStatusId: status } : {}),
        clientCommandId: createOpaqueUuid(),
      },
    }
    setAttempt(pending)
    setBusy(true)
    setError('')
    try {
      await saveConfiguration(ticketNumber, pending.version, pending.body)
      setAttempt(null)
      setBase(null)
      setShown(null)
      setOpen(false)
      setSaved(true)
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['agent-ticket', ticketNumber] }),
        cache.invalidateQueries({ queryKey: ['agent-views'] }),
        cache.invalidateQueries({ queryKey: ['agent-view'] }),
      ])
    } catch (cause) {
      const definite =
        cause instanceof ApiError && cause.status >= 400 && cause.status < 500
      if (definite) setAttempt(null)
      const conflict =
        cause instanceof ApiError &&
        (cause.status === 409 || cause.status === 412)
      setStale(conflict)
      setError(
        conflict
          ? '티켓 또는 폼이 변경되었습니다. 입력을 유지한 채 최신 설정을 불러와 주세요.'
          : definite
            ? '저장하지 못했습니다. 입력과 권한을 확인해 주세요.'
            : '저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 확인해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <SeedContextCard title="티켓 추가 정보">
      {saved ? <p role="status">추가 정보를 저장했습니다.</p> : null}
      <SeedButton
        ref={trigger}
        onClick={() => {
          setOpen(true)
          setSaved(false)
          if (!base) void load()
        }}
      >
        필드·태그·상태 편집
      </SeedButton>
      <SeedDrawer
        open={open}
        onClose={() => setOpen(false)}
        returnFocusRef={trigger}
        title="티켓 추가 정보 편집"
        description="문의 항목과 업무 분류를 함께 확인하고 저장합니다."
      >
        {error ? <SeedNotice title={error} tone="warning" /> : null}
        {busy && !base ? <p role="status">설정을 불러오는 중…</p> : null}
        {(!base || stale || projectionError) && !attempt ? (
          <SeedButton disabled={busy} onClick={() => void load(Boolean(base))}>
            최신 설정 불러오기
          </SeedButton>
        ) : null}
        {base && shown ? (
          <form
            className="configuration-editor"
            onSubmit={(event) => {
              event.preventDefault()
              void submit()
            }}
          >
            {!base.writable ? (
              <SeedNotice title="이 티켓은 읽기만 가능합니다." tone="warning" />
            ) : null}
            {projectionError ? (
              <SeedNotice title={projectionError} tone="warning" />
            ) : null}
            <fieldset
              className="configuration-controls"
              disabled={busy || Boolean(attempt) || !base.writable}
            >
              <legend>문의 항목</legend>
              {shown.form?.fields.some((f) => f.visible) ? (
                shown.form.fields
                  .filter((f) => f.visible)
                  .map((field) => (
                    <AgentConfigurationField
                      key={field.id}
                      field={field}
                      value={values[field.machineKey]}
                      change={(value) => {
                        setEditedKeys((current) =>
                          new Set(current).add(field.machineKey),
                        )
                        setValues((current) => {
                          const next = { ...current }
                          if (value) next[field.machineKey] = value
                          else delete next[field.machineKey]
                          return next
                        })
                      }}
                    />
                  ))
              ) : (
                <p>현재 설정된 추가 문의 항목이 없습니다.</p>
              )}
              <fieldset className="configuration-controls">
                <legend>태그</legend>
                {[
                  ...base.availableTags,
                  ...base.tags.filter(
                    (t) =>
                      !base.availableTags.some((choice) => choice.id === t.id),
                  ),
                ].map((tag) => (
                  <SeedCheckbox
                    key={tag.id}
                    label={tag.label}
                    checked={tags.includes(tag.id)}
                    onChange={(event) =>
                      setTags((current) =>
                        event.target.checked
                          ? [...current, tag.id]
                          : current.filter((id) => id !== tag.id),
                      )
                    }
                  />
                ))}
                {base.availableTags.length === 0 && base.tags.length === 0 ? (
                  <p>등록된 태그가 없습니다.</p>
                ) : null}
              </fieldset>
              <SeedSelectField
                label="업무 상태"
                value={status}
                onChange={(event) => setStatus(event.target.value)}
              >
                <option value="">
                  현재 상태 유지
                  {base.customStatus
                    ? ` · ${base.customStatus.agentLabel}`
                    : ''}
                </option>
                {shown.availableStatuses.map((choice) => (
                  <option key={choice.id} value={choice.id}>
                    {choice.label}
                  </option>
                ))}
              </SeedSelectField>
            </fieldset>
            {projecting && !attempt ? (
              <p role="status">항목 조건을 확인하는 중…</p>
            ) : null}
            <SeedButton
              type="submit"
              variant="primary"
              disabled={
                busy ||
                stale ||
                !base.writable ||
                (!attempt && (projecting || Boolean(projectionError)))
              }
            >
              {busy
                ? '저장 중…'
                : attempt
                  ? '같은 내용으로 저장 확인'
                  : '추가 정보 저장'}
            </SeedButton>
          </form>
        ) : null}
      </SeedDrawer>
    </SeedContextCard>
  )
}
function AgentConfigurationField({
  field,
  value,
  change,
}: {
  field: AgentField
  value?: FieldValue
  change: (value?: FieldValue) => void
}) {
  const label = `${field.label}${field.required ? ' (필수)' : ''}`
  const control = { label, disabled: !field.editable, required: field.required }
  if (field.type === 'CHECKBOX')
    return (
      <SeedSelectField
        {...control}
        value={
          value?.booleanValue === undefined ? '' : String(value.booleanValue)
        }
        onChange={(e) => change({ booleanValue: e.target.value === 'true' })}
      >
        <option value="" disabled>
          선택해 주세요
        </option>
        <option value="true">예</option>
        <option value="false">아니요</option>
      </SeedSelectField>
    )
  if (field.type === 'SINGLE_SELECT')
    return (
      <SeedSelectField
        {...control}
        value={value?.optionId ?? ''}
        onChange={(e) => change({ optionId: e.target.value })}
      >
        <option value="" disabled>
          선택해 주세요
        </option>
        {field.options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.label}
          </option>
        ))}
      </SeedSelectField>
    )
  if (field.type === 'NUMBER')
    return (
      <SeedTextField
        {...control}
        required={field.required || value?.numberValue !== undefined}
        value={value?.numberValue ?? ''}
        error={
          value?.numberValue !== undefined &&
          !isDecimalFieldValue(value.numberValue)
            ? '숫자와 소수점으로 입력해 주세요.'
            : undefined
        }
        onChange={(e) => {
          const raw = e.target.value
          e.target.setCustomValidity(
            raw !== '' && !isDecimalFieldValue(raw)
              ? '숫자와 소수점으로 입력해 주세요.'
              : '',
          )
          change(raw === '' ? undefined : { numberValue: raw })
        }}
      />
    )
  if (field.type === 'LONG_TEXT')
    return (
      <SeedTextAreaField
        {...control}
        value={value?.longTextValue ?? ''}
        onChange={(e) => change({ longTextValue: e.target.value })}
        maxLength={field.validation.maxLength ?? 10000}
      />
    )
  return (
    <SeedTextField
      {...control}
      value={value?.shortTextValue ?? ''}
      onChange={(e) => change({ shortTextValue: e.target.value })}
      maxLength={field.validation.maxLength ?? 1000}
    />
  )
}
