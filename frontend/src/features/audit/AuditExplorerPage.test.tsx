import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeskseedThemeProvider } from '../../shared/ui/DeskseedThemeProvider'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { AuditExplorerPage } from './AuditExplorerPage'

const CHANGE_ID = 'a0000000-0000-0000-0000-000000000001'
const SEARCH_ID = 'a0000000-0000-0000-0000-000000000002'
const EVENT_ID = 'b0000000-0000-0000-0000-000000000001'

const actor = {
  id: 'c0000000-0000-4000-8000-000000000001',
  type: 'STAFF',
  displayName: '보안 감사자',
}

const change = {
  id: CHANGE_ID,
  ledger: 'TICKET_CHANGE',
  action: 'STATUS_CHANGED',
  actor,
  occurredAt: '2026-08-11T01:00:00Z',
  ticketNumber: 1042,
  groupId: 'd0000000-0000-4000-8000-000000000001',
  field: 'status',
  resourceType: 'TICKET',
  resourceId: 'e0000000-0000-4000-8000-000000000001',
  summary: 'status changed · ticket #1042',
  source: 'AGENT_UI',
  outcome: 'SUCCEEDED',
  requestId: 'request-change',
  correlationId: 'correlation-root',
  protectedContentAvailable: false,
  searchFingerprint: null,
}

const searchActivity = {
  ...change,
  id: SEARCH_ID,
  ledger: 'ACCESS_SEARCH',
  action: 'SEARCH_EXECUTED',
  ticketNumber: null,
  groupId: null,
  field: null,
  resourceType: 'SEARCH',
  resourceId: null,
  summary: 'search executed',
  protectedContentAvailable: true,
  searchFingerprint: 'fingerprint-v1',
}

function renderExplorer(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/audit/activity']}>
          <StaffSessionProvider>
            <Routes>
              <Route path="/audit/activity" element={<AuditExplorerPage />} />
            </Routes>
          </StaffSessionProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </DeskseedThemeProvider>,
  )
}

function auditFetch() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://deskseed.test').pathname
    if (path === '/api/v1/agent/me') {
      return json({
        id: actor.id,
        email: 'auditor@example.com',
        displayName: actor.displayName,
        role: 'SECURITY_AUDITOR',
        capabilities: [
          'audit:activity:read',
          'audit:search-query:reveal',
          'audit:export',
          'audit:projection:rebuild',
        ],
      })
    }
    if (path === '/api/v1/audit/activities' && (!init?.method || init.method === 'GET')) {
      expect(new Headers(init?.headers).get('X-Interaction-Id')).toMatch(
        /^[0-9a-f-]{36}$/,
      )
      return json({
        items: [change, searchActivity],
        nextCursor: null,
        snapshotAt: '2026-08-11T02:00:00Z',
        projection: {
          state: 'CURRENT',
          projectedCount: 2,
          lastRebuiltAt: '2026-08-11T00:00:00Z',
        },
      })
    }
    if (path === `/api/v1/audit/activities/${CHANGE_ID}`) {
      return json({
        ...change,
        canonicalEventId: EVENT_ID,
        canonicalParentId: 'b0000000-0000-0000-0000-000000000002',
        fieldChange: { field: 'status', before: 'OPEN', after: 'PENDING' },
        interactionId: null,
        sessionFingerprint: null,
        authType: null,
        ipAddress: null,
        userAgent: null,
        search: null,
        metadata: { visibility: 'PUBLIC' },
      })
    }
    if (path === `/api/v1/audit/activities/${SEARCH_ID}`) {
      return json({
        ...searchActivity,
        canonicalEventId: 'b0000000-0000-0000-0000-000000000003',
        canonicalParentId: null,
        fieldChange: null,
        interactionId: 'f0000000-0000-4000-8000-000000000001',
        sessionFingerprint: 'v1:session-fingerprint',
        authType: 'STAFF_SESSION',
        ipAddress: '192.0.2.4',
        userAgent: 'Deskseed test browser',
        search: {
          queryRedacted: 'a***@example.com',
          queryFingerprint: 'fingerprint-v1',
          filters: { status: 'OPEN' },
          sort: 'updatedAt:desc,ticketNumber:desc',
          resultCount: 1,
          originSearchActivityId: null,
          openedActivities: [],
        },
        metadata: { httpStatus: 200 },
      })
    }
    if (path === '/api/v1/agent/csrf') {
      return json({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' })
    }
    if (path === `/api/v1/audit/activities/${SEARCH_ID}/search-query-reveal`) {
      expect(init?.method).toBe('POST')
      expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe(
        'csrf-token-value',
      )
      expect(new Headers(init?.headers).get('X-Interaction-Id')).toMatch(
        /^[0-9a-f-]{36}$/,
      )
      expect(JSON.parse(String(init?.body))).toEqual({
        reason: 'incident 42 verification',
      })
      return json({
        activityId: SEARCH_ID,
        state: 'AVAILABLE',
        rawQuery: 'alice@example.com priority:urgent',
        keyVersion: 'local-v1',
        revealedAt: '2026-08-11T02:01:00Z',
      })
    }
    throw new Error(`Unexpected request: ${path}`)
  })
}

describe('AuditExplorerPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows canonical before after in a focus restoring drawer without default protected content', async () => {
    const user = userEvent.setup()
    renderExplorer(auditFetch())

    const changeButton = await screen.findByRole('button', {
      name: 'STATUS_CHANGED',
    })
    expect(screen.queryByText('alice@example.com priority:urgent')).not.toBeInTheDocument()
    await user.click(changeButton)

    expect(await screen.findByRole('dialog', { name: '활동 상세' })).toBeVisible()
    expect(screen.getByText('OPEN')).toBeVisible()
    expect(screen.getByText('PENDING')).toBeVisible()
    expect(screen.queryByText('private comment body')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '닫기' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(changeButton).toHaveFocus()
  })

  it('requires a reveal reason and removes the exact raw query when the drawer closes', async () => {
    const user = userEvent.setup()
    renderExplorer(auditFetch())

    await user.click(
      await screen.findByRole('button', { name: 'SEARCH_EXECUTED' }),
    )
    expect(await screen.findByText('a***@example.com')).toBeVisible()
    const revealButton = screen.getByRole('button', {
      name: '이 event의 raw query 공개',
    })
    expect(revealButton).toBeDisabled()
    await user.type(
      screen.getByRole('textbox', { name: '공개 사유' }),
      'incident 42 verification',
    )
    await user.click(revealButton)

    expect(
      await screen.findByText('alice@example.com priority:urgent'),
    ).toBeVisible()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await waitFor(() =>
      expect(
        screen.queryByText('alice@example.com priority:urgent'),
      ).not.toBeInTheDocument(),
    )
  })

  it('does not expose cached detail before a new semantic navigation is audited', async () => {
    const user = userEvent.setup()
    const baseFetch = auditFetch()
    let detailCalls = 0
    let releaseSecondDetail: (() => void) | undefined
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://deskseed.test').pathname
      if (path === `/api/v1/audit/activities/${CHANGE_ID}`) {
        detailCalls += 1
        if (detailCalls === 2) {
          await new Promise<void>((resolve) => {
            releaseSecondDetail = resolve
          })
        }
      }
      return baseFetch(input, init)
    })
    renderExplorer(fetchMock)

    await user.click(
      await screen.findByRole('button', { name: 'STATUS_CHANGED' }),
    )
    expect(await screen.findByText('OPEN')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await user.click(screen.getByRole('button', { name: 'STATUS_CHANGED' }))

    await waitFor(() => expect(detailCalls).toBe(2))
    expect(screen.queryByText('OPEN')).not.toBeInTheDocument()
    const detailInteractions = fetchMock.mock.calls
      .filter(([input]) =>
        new URL(String(input), 'http://deskseed.test').pathname.endsWith(CHANGE_ID),
      )
      .map(([, init]) => new Headers(init?.headers).get('X-Interaction-Id'))
    expect(new Set(detailInteractions).size).toBe(2)

    releaseSecondDetail?.()
    expect(await screen.findByText('OPEN')).toBeVisible()
  })
})

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  })
}
