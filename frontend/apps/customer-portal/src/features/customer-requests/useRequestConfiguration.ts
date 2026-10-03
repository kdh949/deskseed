import { useEffect, useRef, useState } from 'react'
import type { CustomerFieldValue } from '../../api/types'
import {
  loadRequestConfiguration,
  projectRequestConfiguration,
  type RequestConfiguration,
  type CustomerFormProjection,
} from './requestConfiguration'

export function useRequestConfiguration(
  load: typeof loadRequestConfiguration = loadRequestConfiguration,
) {
  const [configuration, setConfiguration] =
    useState<RequestConfiguration | null>(null)
  const [projection, setProjection] = useState<CustomerFormProjection | null>(
    null,
  )
  const [values, setValues] = useState<Record<string, CustomerFieldValue>>({})
  const [loading, setLoading] = useState(true)
  const [projecting, setProjecting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const generation = useRef(0)

  useEffect(() => {
    const active = ++generation.current
    setLoading(true)
    setConfiguration(null)
    setProjecting(false)
    setError(null)
    void load()
      .then((next) => {
        if (active !== generation.current) return
        setConfiguration(next)
        setProjection(next.form)
        setValues((current) =>
          Object.fromEntries(
            Object.entries(current).filter(([key]) =>
              next.form?.fields.some(({ field }) => field.machineKey === key),
            ),
          ),
        )
        setLoading(false)
      })
      .catch(() => {
        if (active !== generation.current) return
        setLoading(false)
        setError(
          '문의 양식과 동의 내용을 불러오지 못했습니다. 다시 확인해 주세요.',
        )
      })
    return () => {
      generation.current++
    }
  }, [load, revision])

  useEffect(() => {
    if (!configuration?.form || loading) return
    let active = true
    setProjecting(true)
    const timer = window.setTimeout(() => {
      void projectRequestConfiguration(configuration.form!, values)
        .then((next) => {
          if (!active) return
          setProjection(next)
          setError(null)
        })
        .catch(() => {
          if (active)
            setError(
              '추가 항목을 확인하지 못했습니다. 입력값을 확인하거나 양식을 다시 불러와 주세요.',
            )
        })
        .finally(() => {
          if (active) setProjecting(false)
        })
    }, 250)
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [configuration, loading, values])

  const change = (key: string, value: CustomerFieldValue | undefined) => {
    setProjecting(true)
    setValues((current) => {
      const next = { ...current }
      if (value === undefined) delete next[key]
      else next[key] = value
      return next
    })
  }
  const visibleValues = Object.fromEntries(
    Object.entries(values).filter(([key]) =>
      projection?.fields.some(
        ({ field, visible, editable }) =>
          field.machineKey === key && visible && editable,
      ),
    ),
  )
  const requiredReady =
    projection?.fields.every(
      ({ field, visible, editable, required }) =>
        !visible ||
        !editable ||
        !required ||
        values[field.machineKey] !== undefined,
    ) ?? true
  return {
    configuration,
    form: projection,
    values,
    visibleValues,
    change,
    loading,
    projecting,
    error,
    requiredReady,
    refresh: () => {
      setLoading(true)
      setRevision((current) => current + 1)
    },
  }
}
