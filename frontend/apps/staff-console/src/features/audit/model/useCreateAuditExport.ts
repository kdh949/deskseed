import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { ApiError, createAuditExport } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'
import type { CreateAuditExportInput } from '../../../api/types'

export interface CreateAuditExportError {
  message: string
  requestId?: string
}

export function useCreateAuditExport() {
  const navigate = useNavigate()
  const mutation = useMutation({
    mutationFn: (input: CreateAuditExportInput) =>
      createAuditExport(input, createOpaqueUuid()),
    onSuccess: (job) => {
      navigate(`/agent/audit/exports/${job.id}`)
    },
  })

  const error: CreateAuditExportError | null = mutation.isError
    ? {
        message: describeError(mutation.error),
        requestId:
          mutation.error instanceof ApiError
            ? mutation.error.requestId
            : undefined,
      }
    : null

  return {
    submit: mutation.mutate,
    submitting: mutation.isPending,
    error,
    reset: mutation.reset,
  }
}

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '내보내기를 요청할 권한이 없습니다.'
    if (error.status === 422) return '선택한 필드나 조건이 올바르지 않습니다.'
    if (error.status === 429)
      return '내보내기 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.'
    if (error.status === 503)
      return '감사 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  return '내보내기 요청을 완료하지 못했습니다.'
}
