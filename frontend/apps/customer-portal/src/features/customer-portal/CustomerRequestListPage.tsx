import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { RetryButton, ScreenState } from '../../design-system'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'
import {
  ApiError,
  listCustomerRequests,
  type CustomerRequestPage,
} from './api/customerPortalClient'
import { CustomerRequestList } from './CustomerRequestList'
import { customerRequestQueryKeys } from './customerRequestQueryKeys'

export function CustomerRequestListPage() {
  const session = useCustomerSession()
  const customerId = session.customer?.id ?? 'anonymous'
  const query = useInfiniteQuery<
    CustomerRequestPage,
    ApiError,
    InfiniteData<CustomerRequestPage>,
    readonly ['customer-request-list', string],
    string | undefined
  >({
    enabled: session.customer !== null,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    initialPageParam: undefined as string | undefined,
    queryKey: customerRequestQueryKeys.list(customerId),
    queryFn: ({ pageParam }) => listCustomerRequests(undefined, pageParam),
    retry: false,
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []
  const nextCursor = query.hasNextPage
    ? (query.data?.pages.at(-1)?.nextCursor ?? null)
    : null

  if (session.status === 'loading') {
    return (
      <CustomerListState kind="loading" title="내 문의를 불러오고 있습니다." />
    )
  }

  if (session.customer === null) {
    return (
      <CustomerListState
        action={<Link to="/customer/sign-in">고객 로그인</Link>}
        description="로그인한 뒤 다시 시도해 주세요."
        kind="denied"
        title="내 문의를 열 수 없습니다."
      />
    )
  }

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
        action={<Link to="/customer/sign-in">고객 로그인</Link>}
        description="로그인한 뒤 다시 시도해 주세요."
        kind="denied"
        requestId={apiError.requestId}
        title="내 문의를 열 수 없습니다."
      />
    )
  }
  return (
    <CustomerListState
      action={<RetryButton onClick={onRetry} />}
      description="잠시 후 다시 시도해 주세요."
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
    <div className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        requestId={requestId}
        title={title}
      />
    </div>
  )
}
