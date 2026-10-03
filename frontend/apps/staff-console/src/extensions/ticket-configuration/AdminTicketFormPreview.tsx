import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
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
import { previewForm, type FormPreviewRequest, type TicketForm } from './api'
import { listStatuses } from './labels-api'
import { isDecimalFieldValue, type FieldValue } from './runtime-api'

const INITIAL: FormPreviewRequest = {
  actorKind: 'CUSTOMER',
  ticketKind: 'CUSTOMER_REQUEST',
  statusCategory: 'NEW',
  fieldValues: {},
}
const STATUS = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
  CLOSED: '종료',
}
const KIND = {
  CUSTOMER_REQUEST: '고객 문의',
  INTERNAL_CHILD: '내부 하위 티켓',
  AGENT_CREATED: '상담사 생성 티켓',
  INTERNAL_WORK_ITEM: '내부 업무',
}

/** Server evaluates conditions; this view only intersects global audience capabilities. */
export function AdminTicketFormPreview({
  form,
  onClose,
}: {
  form: TicketForm
  onClose: () => void
}) {
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    heading.current?.focus()
  }, [])
  const [candidate, setCandidate] = useState<FormPreviewRequest>(INITIAL)
  const [applied, setApplied] = useState<FormPreviewRequest>(INITIAL)
  const statuses = useQuery({
    queryKey: ['admin-ticket-statuses'],
    queryFn: listStatuses,
    retry: false,
  })
  const preview = useQuery({
    queryKey: ['admin-ticket-form-preview', form.id, form.version, applied],
    queryFn: () => previewForm(form.id, applied),
    retry: false,
    refetchOnWindowFocus: false,
  })
  const changed = JSON.stringify(candidate) !== JSON.stringify(applied)
  const invalidNumber = Object.values(candidate.fieldValues).some(
    (value) =>
      value.numberValue !== undefined &&
      !isDecimalFieldValue(value.numberValue),
  )
  const customer = applied.actorKind === 'CUSTOMER'
  const fields =
    preview.data?.fields.filter(
      ({ field, visible }) =>
        field.active &&
        visible &&
        (customer ? field.customerVisible : field.agentVisible),
    ) ?? []
  const changeValue = (key: string, value: FieldValue | undefined) =>
    setCandidate((current) => {
      const fieldValues = { ...current.fieldValues }
      if (value) fieldValues[key] = value
      else delete fieldValues[key]
      return { ...current, fieldValues }
    })
  return (
    <section className="configuration-preview" aria-label="저장된 폼 미리보기">
      <header>
        <h2 ref={heading} tabIndex={-1}>
          {form.name} 저장본 미리보기
        </h2>
        <p>
          저장된 필드 순서·표시·필수 여부를 확인합니다. 저장하지 않은 편집
          내용은 먼저 저장해 주세요. 시험 값은 실제 문의에 저장되지 않습니다.
        </p>
      </header>
      <SeedButton onClick={onClose}>목록으로 돌아가기</SeedButton>
      <form
        className="configuration-editor"
        aria-label="미리보기 조건"
        onSubmit={(event) => {
          event.preventDefault()
          if (!invalidNumber) {
            if (changed) setApplied(candidate)
            else void preview.refetch()
          }
        }}
      >
        <div className="configuration-toolbar">
          <SeedSelectField
            label="사용자 관점"
            value={candidate.actorKind}
            onChange={(event) => {
              const next = {
                ...candidate,
                actorKind: event.target
                  .value as FormPreviewRequest['actorKind'],
                fieldValues: {},
              }
              setCandidate(next)
              setApplied(next)
            }}
          >
            <option value="CUSTOMER">고객</option>
            <option value="AGENT">상담사</option>
          </SeedSelectField>
          <SeedSelectField
            label="티켓 종류"
            value={candidate.ticketKind}
            onChange={(event) =>
              setCandidate({
                ...candidate,
                ticketKind: event.target
                  .value as FormPreviewRequest['ticketKind'],
              })
            }
          >
            {Object.entries(KIND).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </SeedSelectField>
          <SeedSelectField
            label="처리 단계"
            value={candidate.statusCategory}
            onChange={(event) =>
              setCandidate({
                ...candidate,
                statusCategory: event.target
                  .value as FormPreviewRequest['statusCategory'],
                customStatusId: undefined,
              })
            }
          >
            {Object.entries(STATUS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </SeedSelectField>
          <SeedSelectField
            label="업무 상태"
            value={candidate.customStatusId ?? ''}
            onChange={(event) =>
              setCandidate({
                ...candidate,
                customStatusId: event.target.value || undefined,
              })
            }
          >
            <option value="">지정하지 않음</option>
            {statuses.data
              ?.filter(
                (status) =>
                  status.active &&
                  status.statusCategory === candidate.statusCategory &&
                  (!status.allowedFormIds.length ||
                    status.allowedFormIds.includes(form.id)),
              )
              .map((status) => (
                <option key={status.id} value={status.id}>
                  {status.agentLabel}
                </option>
              ))}
          </SeedSelectField>
        </div>
        {statuses.error && (
          <SeedNotice
            title="업무 상태 목록을 불러오지 못했습니다."
            tone="warning"
            action={
              <SeedButton onClick={() => void statuses.refetch()}>
                상태 다시 불러오기
              </SeedButton>
            }
          >
            업무 상태를 지정하지 않은 조건은 미리 볼 수 있습니다.
          </SeedNotice>
        )}
        {invalidNumber && (
          <SeedNotice title="숫자 입력을 확인해 주세요." tone="warning">
            숫자는 소수점과 앞의 음수 부호만 사용할 수 있습니다.
          </SeedNotice>
        )}
        <SeedButton
          type="submit"
          disabled={preview.isFetching || invalidNumber}
        >
          {preview.isFetching ? '평가 중…' : '조건 적용'}
        </SeedButton>
        {changed && (
          <SeedNotice title="조건이 변경되었습니다." tone="warning">
            아래 결과는 마지막으로 적용한 조건입니다. 시험 값을 바꾼 뒤 조건
            적용을 눌러 주세요.
          </SeedNotice>
        )}
        {preview.isPending ? (
          <SeedSkeletonRows />
        ) : preview.error ? (
          <SeedFeedbackState
            kind={
              preview.error instanceof ApiError && preview.error.status === 403
                ? 'denied'
                : 'error'
            }
            title="미리보기를 불러오지 못했습니다."
            description={`권한 또는 저장된 설정을 확인한 뒤 조건 적용으로 다시 시도해 주세요.${preview.error instanceof ApiError && preview.error.requestId ? ` 요청 ID: ${preview.error.requestId}` : ''}`}
          />
        ) : (
          <>
            <p role="status">
              저장된 설정 버전 {preview.data.formVersion} ·{' '}
              {customer ? '고객' : '상담사'} 관점 · 표시 필드 {fields.length}개
            </p>
            {preview.data.formVersion !== form.version && (
              <SeedNotice
                title="목록 이후 설정 버전이 바뀌었습니다."
                tone="warning"
              >
                서버에서 반환한 최신 저장본을 표시합니다. 편집 전 목록을
                새로고침해 주세요.
              </SeedNotice>
            )}
            {!fields.length && (
              <SeedFeedbackState
                kind="empty"
                title="이 조건에서 표시할 필드가 없습니다."
                description="사용자 관점과 조건 또는 저장된 필드 표시 설정을 확인하세요."
              />
            )}
            {fields.map(({ field, editable, required, options }) => {
              const label = `${customer ? field.customerLabel || '고객 표시 이름 없음' : field.staffLabel}${required ? ' (필수)' : ''}`
              const value = candidate.fieldValues[field.machineKey]
              const disabled =
                !editable ||
                !(customer ? field.customerEditable : field.agentEditable)
              const text =
                value?.shortTextValue ??
                value?.longTextValue ??
                value?.numberValue ??
                ''
              return (
                <div key={field.id} className="configuration-preview-field">
                  {field.type === 'CHECKBOX' ? (
                    <SeedCheckbox
                      label={label}
                      checked={value?.booleanValue ?? false}
                      disabled={disabled}
                      onChange={(event) =>
                        changeValue(field.machineKey, {
                          booleanValue: event.target.checked,
                        })
                      }
                    />
                  ) : field.type === 'SINGLE_SELECT' ? (
                    <SeedSelectField
                      label={label}
                      value={value?.optionId ?? ''}
                      disabled={disabled}
                      onChange={(event) =>
                        changeValue(
                          field.machineKey,
                          event.target.value
                            ? { optionId: event.target.value }
                            : undefined,
                        )
                      }
                    >
                      <option value="">선택 안 함</option>
                      {options
                        .filter((option) => option.active)
                        .map((option) => (
                          <option key={option.id} value={option.id}>
                            {customer
                              ? option.customerLabel || '고객 선택지 이름 없음'
                              : option.staffLabel}
                          </option>
                        ))}
                    </SeedSelectField>
                  ) : field.type === 'LONG_TEXT' ? (
                    <SeedTextAreaField
                      label={label}
                      value={text}
                      disabled={disabled}
                      onChange={(event) =>
                        changeValue(
                          field.machineKey,
                          event.target.value
                            ? { longTextValue: event.target.value }
                            : undefined,
                        )
                      }
                    />
                  ) : (
                    <SeedTextField
                      label={label}
                      value={text}
                      disabled={disabled}
                      onChange={(event) =>
                        changeValue(
                          field.machineKey,
                          event.target.value
                            ? {
                                [field.type === 'NUMBER'
                                  ? 'numberValue'
                                  : 'shortTextValue']: event.target.value,
                              }
                            : undefined,
                        )
                      }
                    />
                  )}
                  <p>
                    {disabled ? '읽기 전용' : '입력 가능'}
                    {required ? ' · 제출할 때 필수' : ' · 선택 입력'}
                  </p>
                </div>
              )
            })}
          </>
        )}
      </form>
    </section>
  )
}
