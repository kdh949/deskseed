import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import type { CustomerAccessMode } from '../../api/types'
import { DsButton, Notification, ScreenState } from '../../design-system'
import {
  listArticles,
  listCategories,
  listSections,
} from '../../extensions/knowledge-workflow/api'
import { listConsentPolicies } from './customerConsentApi'

/** Explicit, read-only snapshot. It does not certify email delivery or registration. */
export function AdminCustomerReadinessPanel({
  mode,
}: {
  mode: CustomerAccessMode
}) {
  const [started, setStarted] = useState(false)
  const options = {
    enabled: started,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  }
  const consent = useQuery({
    ...options,
    queryKey: ['admin-customer-readiness', 'consent'],
    queryFn: () => listConsentPolicies('REGISTRATION', 'PUBLISHED', 0),
  })
  const categories = useQuery({
    ...options,
    queryKey: ['admin-customer-readiness', 'categories'],
    queryFn: listCategories,
  })
  const sections = useQuery({
    ...options,
    queryKey: ['admin-customer-readiness', 'sections'],
    queryFn: listSections,
  })
  const section = sections.data?.find((item) => item.slug === 'announcements')
  const parent = categories.data?.find(
    (item) => item.id === section?.categoryId,
  )
  const articles = useQuery({
    ...options,
    enabled:
      started &&
      categories.isSuccess &&
      sections.isSuccess &&
      !!section?.active &&
      !!parent?.active,
    queryKey: ['admin-customer-readiness', 'articles', section?.id],
    queryFn: () =>
      listArticles('PUBLISHED', undefined, {
        sectionId: section!.id,
        audience: 'PUBLIC',
      }),
  })
  const busy = [consent, categories, sections, articles].some(
    (query) => query.isFetching,
  )
  const knowledgeError = categories.error ?? sections.error ?? articles.error
  const knowledgeLoading =
    categories.isPending ||
    sections.isPending ||
    (!!section?.active && !!parent?.active && articles.isPending)
  const notice = !section
    ? '공지 섹션이 없습니다.'
    : !parent?.active
      ? '공지의 상위 주제가 비활성 상태입니다.'
      : !section.active
        ? '공지 섹션이 비활성 상태입니다.'
        : !articles.data?.items.some(
              (item) =>
                item.sectionId === section.id &&
                item.lifecycle === 'PUBLISHED' &&
                item.audience.type === 'PUBLIC' &&
                item.currentPublishedRevision,
            )
          ? '누구나 읽을 수 있는 발행 공지가 없습니다.'
          : null

  return (
    <section
      className="admin-surface"
      aria-labelledby="customer-readiness-heading"
    >
      <h2 id="customer-readiness-heading">고객 포털 준비 확인</h2>
      <p>
        저장된 접근 모드에 필요한 가입 약관과 공개 공지를 확인합니다. 실제
        가입과 이메일 전달은 별도로 확인해 주세요.
      </p>
      <DsButton
        disabled={busy}
        onClick={() => {
          if (!started) setStarted(true)
          else {
            void consent.refetch()
            void categories.refetch()
            void sections.refetch()
            if (section?.active && parent?.active) void articles.refetch()
          }
        }}
      >
        {busy
          ? '준비 상태 확인 중…'
          : started
            ? '준비 상태 다시 확인'
            : '준비 상태 확인'}
      </DsButton>
      {started && (
        <>
          <h3>가입 약관</h3>
          {consent.isFetching || consent.isPending ? (
            <ScreenState
              compact
              kind="loading"
              title="가입 약관을 확인하고 있습니다."
            />
          ) : consent.isError ? (
            <ScreenState
              compact
              kind={
                consent.error instanceof ApiError &&
                consent.error.status === 403
                  ? 'denied'
                  : 'error'
              }
              title="가입 약관 준비 상태를 확인할 수 없습니다."
              description="권한과 연결 상태를 확인한 뒤 다시 확인해 주세요."
            />
          ) : (
            <Notification
              tone={consent.data.totalCount ? 'success' : 'warning'}
              title={
                consent.data.totalCount
                  ? `가입 정책 ${consent.data.totalCount}개 발행됨`
                  : '가입 정책 발행이 필요합니다.'
              }
            >
              <p>
                {consent.data.totalCount
                  ? '고객은 회원가입 시 현재 발행된 정책을 확인합니다.'
                  : mode === 'ANONYMOUS_ALLOWED'
                    ? '익명 접수는 가능하지만 회원가입을 제공하려면 정책을 작성하고 발행해야 합니다.'
                    : '현재 접근 모드는 회원가입을 제공합니다. 정책을 작성하고 발행한 뒤 가입 동선을 확인해 주세요.'}
              </p>
            </Notification>
          )}
          <p>
            <Link to="/admin/customer-consent-policies">
              고객 동의 정책 관리
            </Link>
          </p>
          <h3>공개 공지</h3>
          {categories.isFetching ||
          sections.isFetching ||
          articles.isFetching ? (
            <ScreenState
              compact
              kind="loading"
              title="공지를 확인하고 있습니다."
            />
          ) : knowledgeError ? (
            <ScreenState
              compact
              kind={
                knowledgeError instanceof ApiError &&
                knowledgeError.status === 403
                  ? 'denied'
                  : 'error'
              }
              title="공지 준비 상태를 확인할 수 없습니다."
              description="조회에 실패한 항목을 준비 완료로 판단하지 않습니다. 다시 확인해 주세요."
            />
          ) : knowledgeLoading ? (
            <ScreenState
              compact
              kind="loading"
              title="공지를 확인하고 있습니다."
            />
          ) : (
            <Notification
              tone={notice ? 'warning' : 'success'}
              title={notice ?? '공개 공지가 발행되어 있습니다.'}
            >
              <p>
                {notice
                  ? '지식 문서에서 활성 주제 아래 announcements 섹션을 활성화하고, 전체 공개 문서를 발행해 주세요.'
                  : '활성 주제와 공지 섹션에 전체 공개로 발행된 문서가 있습니다.'}
              </p>
            </Notification>
          )}
          <p>
            <Link to="/admin/knowledge">지식 문서 관리</Link>
          </p>
        </>
      )}
    </section>
  )
}
