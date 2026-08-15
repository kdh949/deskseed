import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { RetryButton, ScreenState } from '../../design-system'
import {
  ApiError,
  listCustomerRequests,
  type CustomerRequestPage,
} from './api/customerPortalClient'
import { CustomerRequestList } from './CustomerRequestList'

export function CustomerRequestListPage() {
  const query = useInfiniteQuery<
    CustomerRequestPage,
    ApiError,
    InfiniteData<CustomerRequestPage>,
    readonly ['customer-request-list'],
    string | undefined
  >({
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    initialPageParam: undefined as string | undefined,
    queryKey: ['customer-request-list'] as const,
    queryFn: ({ pageParam }) => listCustomerRequests(undefined, pageParam),
    retry: false,
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []
  const nextCursor = query.hasNextPage
    ? (query.data?.pages.at(-1)?.nextCursor ?? null)
    : null

  if (query.isPending) {
    return (
      <CustomerListState kind="loading" title="내 문의를 불러오고 있습니다." />
    )
  }

  if (query.isError) {
    return (
      <CustomerListError
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return (
    <CustomerRequestList
      items={items}
      loadingMore={query.isFetchingNextPage}
      nextCursor={nextCursor}
      onLoadMore={() => void query.fetchNextPage()}
    />
  )
}

function CustomerListError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry: () => void
}) {
  const apiError = error instanceof ApiError ? error : null
  if (apiError?.status === 401 || apiError?.status === 403) {
    return (
      <CustomerListState
        description="내 문의를 보려면 고객 로그인이 필요합니다."
        kind="denied"
        requestId={apiError.requestId}
        title="내 문의 접근이 허용되지 않았습니다."
      />
    )
  }
  return (
    <CustomerListState
      action={<RetryButton onClick={onRetry} />}
      description="고객 문의 목록을 불러오지 못했습니다."
      kind="error"
      requestId={apiError?.requestId}
      title="내 문의를 불러올 수 없습니다."
    />
  )
}

function CustomerListState({
  action,
  description,
  kind,
  requestId,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  requestId?: string
  title: string
}) {
  return (
    <main className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        requestId={requestId}
        title={title}
      />
    </main>
  )
}
