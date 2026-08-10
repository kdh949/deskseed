import { useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from 'react'
import { submitRequest } from '../../api/client'
import type { SubmitRequestInput, SubmittedRequest } from '../../api/types'
import { useRequestAccess } from './RequestAccessContext'

interface RequestSubmissionValue {
  isSubmitting: boolean
  submitted: SubmittedRequest | null
  submit(input: SubmitRequestInput): Promise<void>
  reset(): void
}

const RequestSubmissionContext = createContext<RequestSubmissionValue | null>(
  null,
)

export function RequestSubmissionProvider({ children }: PropsWithChildren) {
  const requestAccess = useRequestAccess()
  const queryClient = useQueryClient()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState<SubmittedRequest | null>(null)
  const pendingRef = useRef(false)

  const submit = useCallback(
    async (input: SubmitRequestInput) => {
      if (pendingRef.current) return
      pendingRef.current = true
      setIsSubmitting(true)
      try {
        const result = await submitRequest(input)
        queryClient.removeQueries({
          queryKey: ['public-request', result.ticketNumber],
        })
        requestAccess.setAccessToken(result.ticketNumber, result.accessToken)
        setSubmitted(result)
      } finally {
        pendingRef.current = false
        setIsSubmitting(false)
      }
    },
    [queryClient, requestAccess],
  )

  const value = useMemo<RequestSubmissionValue>(
    () => ({
      isSubmitting,
      submitted,
      submit,
      reset: () => setSubmitted(null),
    }),
    [isSubmitting, submit, submitted],
  )

  return (
    <RequestSubmissionContext.Provider value={value}>
      {children}
    </RequestSubmissionContext.Provider>
  )
}

export function useRequestSubmission(): RequestSubmissionValue {
  const value = useContext(RequestSubmissionContext)
  if (!value) {
    throw new Error(
      'useRequestSubmission must be used within RequestSubmissionProvider',
    )
  }
  return value
}
