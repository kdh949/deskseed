import { useId, useState } from 'react'
import type { CustomerFieldValue } from '../../api/types'
import type {
  CustomerFormField,
  RequestConsentPolicy,
} from './requestConfiguration'

export function CustomerRequestCustomFields({
  fields,
  values,
  change,
}: {
  fields: CustomerFormField[]
  values: Record<string, CustomerFieldValue>
  change: (key: string, value: CustomerFieldValue | undefined) => void
}) {
  return (
    <section
      aria-label="문의 추가 항목"
      className="customer-request-additional"
    >
      {fields
        .filter(({ visible }) => visible)
        .map((field) => (
          <CustomerCustomField
            key={field.field.id}
            definition={field}
            value={values[field.field.machineKey]}
            change={change}
          />
        ))}
    </section>
  )
}
function CustomerCustomField({
  definition,
  value,
  change,
}: {
  definition: CustomerFormField
  value?: CustomerFieldValue
  change: (key: string, value: CustomerFieldValue | undefined) => void
}) {
  const id = useId()
  const { field, editable, required, options } = definition
  const [numberError, setNumberError] = useState(false)
  const control = {
    id,
    disabled: !editable,
    required,
    'aria-describedby': field.description ? `${id}-description` : undefined,
  }
  const update = (next?: CustomerFieldValue) => change(field.machineKey, next)
  return (
    <div className="customer-field">
      <label htmlFor={id}>
        {field.label}
        {required ? ' (필수)' : ''}
      </label>
      {field.type === 'CHECKBOX' ? (
        <select
          {...control}
          value={
            value?.booleanValue === undefined ? '' : String(value.booleanValue)
          }
          onChange={(event) =>
            update(
              event.target.value === ''
                ? undefined
                : { booleanValue: event.target.value === 'true' },
            )
          }
        >
          <option value="">선택해 주세요</option>
          <option value="true">예</option>
          <option value="false">아니요</option>
        </select>
      ) : field.type === 'SINGLE_SELECT' ? (
        <select
          {...control}
          value={value?.optionId ?? ''}
          onChange={(event) =>
            update(
              event.target.value ? { optionId: event.target.value } : undefined,
            )
          }
        >
          <option value="">선택해 주세요</option>
          {options.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      ) : field.type === 'NUMBER' ? (
        <input
          {...control}
          type="number"
          step="any"
          min={field.validation.minimum ?? undefined}
          max={field.validation.maximum ?? undefined}
          aria-invalid={numberError}
          defaultValue={value?.numberValue ?? ''}
          onChange={(event) => {
            const raw = event.target.value
            const next = Number(raw)
            const significand = raw.split(/[eE]/)[0] ?? ''
            const invalid =
              raw !== '' &&
              (!Number.isFinite(next) ||
                significand.replace(/[^0-9]/g, '').replace(/^0+/, '').length >
                  15 ||
                (next === 0 && /[1-9]/.test(significand)))
            setNumberError(invalid)
            event.target.setCustomValidity(
              invalid ? '입력한 숫자가 너무 크거나 정밀합니다.' : '',
            )
            update(raw === '' || invalid ? undefined : { numberValue: next })
          }}
        />
      ) : field.type === 'LONG_TEXT' ? (
        <textarea
          {...control}
          rows={4}
          minLength={field.validation.minLength ?? undefined}
          maxLength={field.validation.maxLength ?? 10000}
          value={value?.longTextValue ?? ''}
          onChange={(event) =>
            update(
              event.target.value
                ? { longTextValue: event.target.value }
                : undefined,
            )
          }
        />
      ) : (
        <input
          {...control}
          minLength={field.validation.minLength ?? undefined}
          maxLength={field.validation.maxLength ?? 1000}
          value={value?.shortTextValue ?? ''}
          onChange={(event) =>
            update(
              event.target.value
                ? { shortTextValue: event.target.value }
                : undefined,
            )
          }
        />
      )}
      {field.description ? (
        <small id={`${id}-description`}>{field.description}</small>
      ) : null}
      {numberError ? (
        <small role="alert">입력한 숫자가 너무 크거나 정밀합니다.</small>
      ) : null}
    </div>
  )
}

export function CustomerRequestConsents({
  policies,
  accepted,
  onChange,
}: {
  policies: RequestConsentPolicy[]
  accepted: string[]
  onChange: (keys: string[]) => void
}) {
  return policies.length ? (
    <section
      aria-label="문의 동의 항목"
      className="customer-request-additional"
    >
      {policies.map((policy) => {
        const key = `${policy.policyKey}:${policy.version}`
        return (
          <div className="customer-field" key={key}>
            <details>
              <summary>{policy.title} 내용 보기</summary>
              {policy.paragraphs.map((text, index) => (
                <p key={index}>{text}</p>
              ))}
            </details>
            <label className="customer-checkbox">
              <input
                type="checkbox"
                checked={accepted.includes(key)}
                required={policy.required}
                onChange={(event) =>
                  onChange(
                    event.target.checked
                      ? [...accepted, key]
                      : accepted.filter((value) => value !== key),
                  )
                }
              />
              <span>
                {policy.title}에 동의합니다. (
                {policy.required ? '필수' : '선택'})
              </span>
            </label>
          </div>
        )
      })}
    </section>
  ) : null
}
