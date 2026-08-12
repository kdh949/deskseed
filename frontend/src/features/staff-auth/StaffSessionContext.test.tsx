import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  listAgentViews,
  STAFF_SESSION_ACTOR_MISMATCH_EVENT,
  STAFF_SESSION_INVALID_EVENT,
} from '../../api/client'
import type { CurrentStaff } from '../../api/types'
import {
  STAFF_DRAFT_SESSION_OWNER_KEY,
  TICKET_DRAFT_TTL_MS,
  ticketDraftStorageKey,
} from '../ticket-workspace/model/ticketEditorModel'
import { StaffSessionProvider, useStaffSession } from './StaffSessionContext'

const staffA: CurrentStaff = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'staff-a@example.com',
  displayName: '상담사 A',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}

const staffB: CurrentStaff = {
  ...staffA,
  id: '22222222-2222-4222-8222-222222222222',
  email: 'staff-b@example.com',
  displayName: '상담사 B',
}

const staffC: CurrentStaff = {
  ...staffA,
  id: '33333333-3333-4333-8333-333333333333',
  email: 'staff-c@example.com',
  displayName: '상담사 C',
}

function SessionProbe() {
  const session = useStaffSession()
  const [signInAttemptFinished, setSignInAttemptFinished] = useState(false)
  const [signOutAttemptFinished, setSignOutAttemptFinished] = useState(false)
  return (
    <>
      <output aria-label="session-status">{session.status}</output>
      <output aria-label="staff-id">{session.staff?.id ?? 'none'}</output>
      <output aria-label="sign-in-attempt-status">
        {signInAttemptFinished ? 'finished' : 'idle'}
      </output>
      <output aria-label="sign-out-attempt-status">
        {signOutAttemptFinished ? 'finished' : 'idle'}
      </output>
      <button type="button" onClick={() => void session.retry()}>
        Refresh session
      </button>
      <button
        type="button"
        onClick={() => {
          void session
            .signIn('wrong@example.com', 'wrong-password')
            .catch(() => undefined)
            .finally(() => setSignInAttemptFinished(true))
        }}
      >
        Attempt sign in
      </button>
      <button
        type="button"
        onClick={() => {
          void session
            .signOut()
            .catch(() => undefined)
            .finally(() => setSignOutAttemptFinished(true))
        }}
      >
        Sign out
      </button>
    </>
  )
}

function PageSessionProbe({ page }: { page: 'A' | 'B' }) {
  const session = useStaffSession()
  return (
    <section aria-label={`${page} page`}>
      <output aria-label={`${page}-session-status`}>{session.status}</output>
      <output aria-label={`${page}-staff-id`}>
        {session.staff?.id ?? 'none'}
      </output>
      {page === 'B' ? (
        <button
          type="button"
          onClick={() => {
            void session.signIn('staff-b@example.com', 'Correct horse 42')
          }}
        >
          Sign in B page
        </button>
      ) : null}
    </section>
  )
}

function renderSession() {
  return render(
    <StaffSessionProvider>
      <SessionProbe />
    </StaffSessionProvider>,
  )
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function staffFetch(currentStaff: () => Response | Promise<Response>) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://deskseed.test').pathname
    if (path === '/api/v1/agent/me') return currentStaff()
    if (path === '/api/v1/agent/csrf') {
      return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
    }
    if (
      path === '/api/v1/agent/session' &&
      String(init?.method).toUpperCase() === 'DELETE'
    ) {
      return new Response(null, { status: 204 })
    }
    throw new Error(`Unexpected request: ${path}`)
  })
}

function putDraft(staffId: string, ticketNumber: number) {
  putStoredDraft(staffId, ticketNumber, new Date().toISOString())
}

function putStoredDraft(
  staffId: string,
  ticketNumber: number,
  savedAt: string,
) {
  const fields = {
    status: 'OPEN',
    priority: 'NORMAL',
    groupId: null,
    assigneeId: null,
  }
  localStorage.setItem(
    ticketDraftStorageKey(staffId, ticketNumber),
    JSON.stringify({
      formatVersion: 1,
      savedAt,
      mode: 'PUBLIC',
      comments: { PUBLIC: 'customer reply', INTERNAL: 'staff note' },
      fields,
      serverFields: fields,
      baseVersion: 1,
    }),
  )
}

describe('StaffSessionProvider draft lifecycle', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('purges only the departing staff ticket drafts after explicit logout', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffA.id, 1043)
    putDraft(staffB.id, 1042)
    localStorage.setItem(
      `deskseed:agent:${staffA.id}:workspace-panels:v1`,
      'preference',
    )
    localStorage.setItem('unrelated-application-key', 'keep')

    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1043)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
    expect(
      localStorage.getItem(`deskseed:agent:${staffA.id}:workspace-panels:v1`),
    ).toBe('preference')
    expect(localStorage.getItem('unrelated-application-key')).toBe('keep')
  })

  it('does not let a stale logout completion clear a newer staff session', async () => {
    const user = userEvent.setup()
    let current = staffA
    let resolveCsrf!: (response: Response) => void
    const pendingCsrf = new Promise<Response>((resolve) => {
      resolveCsrf = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/me') return json(current)
        if (path === '/api/v1/agent/csrf') {
          return pendingCsrf
        }
        if (
          path === '/api/v1/agent/session' &&
          String(init?.method).toUpperCase() === 'DELETE'
        ) {
          return json({ status: 401 }, 401)
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/csrf',
        expect.any(Object),
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    current = staffB
    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() =>
      expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id),
    )
    putDraft(staffB.id, 1042)

    await act(async () => {
      resolveCsrf(json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
      await pendingCsrf
    })
    await waitFor(() =>
      expect(
        screen.getByLabelText('sign-out-attempt-status'),
      ).toHaveTextContent('finished'),
    )

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'authenticated',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id)
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('sweeps expired and malformed drafts when the staff session is established', async () => {
    const now = Date.now()
    const expiredKey = ticketDraftStorageKey(staffA.id, 1042)
    const malformedKey = ticketDraftStorageKey(staffA.id, 1043)
    const validKey = ticketDraftStorageKey(staffA.id, 1044)
    const otherStaffKey = ticketDraftStorageKey(staffB.id, 1042)
    putStoredDraft(
      staffA.id,
      1042,
      new Date(now - TICKET_DRAFT_TTL_MS - 1).toISOString(),
    )
    localStorage.setItem(malformedKey, '{not-json')
    putStoredDraft(staffA.id, 1044, new Date(now - 60_000).toISOString())
    putStoredDraft(
      staffB.id,
      1042,
      new Date(now - TICKET_DRAFT_TTL_MS - 1).toISOString(),
    )
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )

    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()

    expect(localStorage.getItem(expiredKey)).toBeNull()
    expect(localStorage.getItem(malformedKey)).toBeNull()
    expect(localStorage.getItem(validKey)).not.toBeNull()
    expect(localStorage.getItem(otherStaffKey)).not.toBeNull()
  })

  it('clears protected staff state when browser storage cleanup throws after logout', async () => {
    const user = userEvent.setup()
    const fetchMock = staffFetch(() => json(staffA))
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    vi.spyOn(localStorage, 'removeItem').mockImplementation(() => {
      throw new DOMException('Storage is unavailable', 'SecurityError')
    })

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent('none')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent/session',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('purges the authenticated staff namespace on global session invalidation', async () => {
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)
    localStorage.setItem('unrelated-application-key', 'keep')

    window.dispatchEvent(new Event(STAFF_SESSION_INVALID_EVENT))

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
    expect(localStorage.getItem('unrelated-application-key')).toBe('keep')
  })

  it('omits a malformed remembered owner from the first request and self-heals after verification', async () => {
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/me') return json(staffA)
        if (path === '/api/v1/agent/views') return json([])
        throw new Error(`Unexpected request: ${path} ${String(init?.method)}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, 'not-a-uuid')

    renderSession()

    expect(await screen.findByText(staffA.id)).toBeVisible()
    expect(
      new Headers(fetchMock.mock.calls[0]?.[1]?.headers).has(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(false)
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffA.id)

    await listAgentViews()
    expect(
      new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(staffA.id)
  })

  it('clears only the stale local actor on mismatch while preserving the shared owner marker', async () => {
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)

    window.dispatchEvent(new Event(STAFF_SESSION_ACTOR_MISMATCH_EVENT))

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent('none')
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('ignores a stale actor mismatch after a different staff actor is verified', async () => {
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffB)),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    renderSession()
    expect(await screen.findByText(staffB.id)).toBeVisible()
    putDraft(staffB.id, 1042)

    window.dispatchEvent(
      new CustomEvent(STAFF_SESSION_ACTOR_MISMATCH_EVENT, {
        detail: { generation: -1 },
      }),
    )

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'authenticated',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id)
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('retains the locally confirmed actor guard after another tab changes the shared owner', async () => {
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        void init
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/me') return json(staffA)
        if (path === '/api/v1/agent/views') return json([])
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    await listAgentViews()

    const viewsCall = fetchMock.mock.calls.find(([input]) =>
      String(input).endsWith('/api/v1/agent/views'),
    )
    expect(viewsCall).toBeDefined()
    expect(
      new Headers(viewsCall?.[1]?.headers).get('X-Deskseed-Expected-Staff-Id'),
    ).toBe(staffA.id)
  })

  it('lets a pending B verification finish after stale A handles a mismatch in its own page realm', async () => {
    const user = userEvent.setup()
    const pageAEvents = new EventTarget()
    const pageBEvents = new EventTarget()
    let meRequestCount = 0
    let resolveBVerification!: (response: Response) => void
    const pendingBVerification = new Promise<Response>((resolve) => {
      resolveBVerification = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') {
          meRequestCount += 1
          return meRequestCount <= 2 ? json(staffA) : pendingBVerification
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return new Response(null, { status: 204 })
        }
        if (path === '/api/v1/agent/views') return json([])
        throw new Error(`Unexpected request: ${method} ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    render(
      <StaffSessionProvider sessionEventTarget={pageAEvents}>
        <PageSessionProbe page="A" />
      </StaffSessionProvider>,
    )
    render(
      <StaffSessionProvider sessionEventTarget={pageBEvents}>
        <PageSessionProbe page="B" />
      </StaffSessionProvider>,
    )
    await waitFor(() => {
      expect(screen.getByLabelText('A-staff-id')).toHaveTextContent(staffA.id)
      expect(screen.getByLabelText('B-staff-id')).toHaveTextContent(staffA.id)
    })
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Sign in B page' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    await waitFor(() => expect(meRequestCount).toBe(3))

    pageAEvents.dispatchEvent(new Event(STAFF_SESSION_ACTOR_MISMATCH_EVENT))

    await waitFor(() =>
      expect(screen.getByLabelText('A-session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()

    await listAgentViews()
    const stalePageRetry = fetchMock.mock.calls.find(([input]) =>
      String(input).endsWith('/api/v1/agent/views'),
    )
    expect(
      new Headers(stalePageRetry?.[1]?.headers).get(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(staffA.id)

    await act(async () => {
      resolveBVerification(json(staffB))
      await pendingBVerification
    })

    await waitFor(() => {
      expect(screen.getByLabelText('B-session-status')).toHaveTextContent(
        'authenticated',
      )
      expect(screen.getByLabelText('B-staff-id')).toHaveTextContent(staffB.id)
    })
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()

    await listAgentViews()
    const viewCalls = fetchMock.mock.calls.filter(([input]) =>
      String(input).endsWith('/api/v1/agent/views'),
    )
    expect(
      new Headers(viewCalls[1]?.[1]?.headers).get(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(staffB.id)
  })

  it.each([401, 429])(
    'preserves another tab owner and drafts after definite login rejection %s',
    async (rejectionStatus) => {
      const user = userEvent.setup()
      const fetchMock = vi.fn(
        async (input: RequestInfo | URL, init?: RequestInit) => {
          const path = new URL(String(input), 'http://deskseed.test').pathname
          if (path === '/api/v1/agent/me') {
            return json({ status: 401 }, 401)
          }
          if (path === '/api/v1/agent/csrf') {
            return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
          }
          if (
            path === '/api/v1/agent/session' &&
            String(init?.method).toUpperCase() === 'POST'
          ) {
            return json({ status: rejectionStatus }, rejectionStatus)
          }
          throw new Error(`Unexpected request: ${path}`)
        },
      )
      vi.stubGlobal('fetch', fetchMock)
      renderSession()
      await waitFor(() =>
        expect(screen.getByLabelText('session-status')).toHaveTextContent(
          'anonymous',
        ),
      )

      localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
      putDraft(staffB.id, 1042)
      window.dispatchEvent(
        new StorageEvent('storage', {
          key: STAFF_DRAFT_SESSION_OWNER_KEY,
          oldValue: null,
          newValue: staffB.id,
        }),
      )

      await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/v1/agent/session',
          expect.objectContaining({ method: 'POST' }),
        ),
      )
      await waitFor(() =>
        expect(
          screen.getByLabelText('sign-in-attempt-status'),
        ).toHaveTextContent('finished'),
      )

      expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(
        staffB.id,
      )
      expect(
        localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
      ).not.toBeNull()
    },
  )

  it.each(['5xx', 'network'] as const)(
    'compensates and fails closed when the login POST has ambiguous %s failure',
    async (failureMode) => {
      const user = userEvent.setup()
      let resolveLogin!: (response: Response) => void
      let rejectLogin!: (reason?: unknown) => void
      const pendingLogin = new Promise<Response>((resolve, reject) => {
        resolveLogin = resolve
        rejectLogin = reject
      })
      const fetchMock = vi.fn(
        async (input: RequestInfo | URL, init?: RequestInit) => {
          const path = new URL(String(input), 'http://deskseed.test').pathname
          const method = String(init?.method ?? 'GET').toUpperCase()
          if (path === '/api/v1/agent/me') {
            return json({ status: 401 }, 401)
          }
          if (path === '/api/v1/agent/csrf') {
            return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
          }
          if (path === '/api/v1/agent/session' && method === 'POST') {
            return pendingLogin
          }
          if (path === '/api/v1/agent/session' && method === 'DELETE') {
            return new Response(null, { status: 204 })
          }
          throw new Error(`Unexpected request: ${path}`)
        },
      )
      vi.stubGlobal('fetch', fetchMock)
      renderSession()
      await waitFor(() =>
        expect(screen.getByLabelText('session-status')).toHaveTextContent(
          'anonymous',
        ),
      )

      await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/v1/agent/session',
          expect.objectContaining({ method: 'POST' }),
        ),
      )
      localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
      putDraft(staffB.id, 1042)
      window.dispatchEvent(
        new StorageEvent('storage', {
          key: STAFF_DRAFT_SESSION_OWNER_KEY,
          oldValue: null,
          newValue: staffB.id,
        }),
      )
      await act(async () => {
        if (failureMode === 'network') {
          rejectLogin(new TypeError('Login response was interrupted'))
        } else {
          resolveLogin(json({ status: 503 }, 503))
        }
        await pendingLogin.catch(() => undefined)
      })
      await waitFor(() =>
        expect(
          screen.getByLabelText('sign-in-attempt-status'),
        ).toHaveTextContent('finished'),
      )

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'DELETE' }),
      )
      expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
      expect(
        localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
      ).toBeNull()
    },
  )

  it('preserves another tab owner and drafts when the login CSRF request returns 401', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/me') {
          return json({ status: 401 }, 401)
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ status: 401 }, 401)
        }
        if (
          path === '/api/v1/agent/session' &&
          String(init?.method).toUpperCase() === 'POST'
        ) {
          throw new Error('Login must not continue without a CSRF token')
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('compensates and fails closed when post-login session verification returns 401', async () => {
    const user = userEvent.setup()
    let resolveLogin!: (response: Response) => void
    const pendingLogin = new Promise<Response>((resolve) => {
      resolveLogin = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') {
          return json({ status: 401 }, 401)
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return pendingLogin
        }
        if (path === '/api/v1/agent/session' && method === 'DELETE') {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveLogin(new Response(null, { status: 204 }))
      await pendingLogin
    })
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent/session',
      expect.objectContaining({ method: 'DELETE' }),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).toBeNull()
    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'anonymous',
    )
  })

  it('clears the departing owner when post-login verification returns 401 without a superseder', async () => {
    const user = userEvent.setup()
    let currentStaffRequestCount = 0
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') {
          currentStaffRequestCount += 1
          return currentStaffRequestCount === 1
            ? json(staffA)
            : json({ status: 401 }, 401)
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return new Response(null, { status: 204 })
        }
        if (path === '/api/v1/agent/session' && method === 'DELETE') {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent/session',
      expect.objectContaining({ method: 'DELETE' }),
    )
    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'anonymous',
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
  })

  it.each([
    ['5xx', '5xx', true],
    ['malformed', 'malformed', true],
    ['network', 'network', true],
    ['5xx before owner storage event delivery', '5xx', false],
  ] as const)(
    'compensates and fails closed when post-login verification has %s failure',
    async (_label, failureMode, dispatchOwnerEvent) => {
      const user = userEvent.setup()
      let currentStaffRequestCount = 0
      let resolveLogin!: (response: Response) => void
      const pendingLogin = new Promise<Response>((resolve) => {
        resolveLogin = resolve
      })
      const fetchMock = vi.fn(
        async (input: RequestInfo | URL, init?: RequestInit) => {
          const path = new URL(String(input), 'http://deskseed.test').pathname
          const method = String(init?.method ?? 'GET').toUpperCase()
          if (path === '/api/v1/agent/me') {
            currentStaffRequestCount += 1
            if (currentStaffRequestCount === 1) {
              return json({ status: 401 }, 401)
            }
            if (failureMode === 'network') {
              throw new TypeError('Verification response was interrupted')
            }
            return failureMode === 'malformed'
              ? json({ id: 'malformed' })
              : json({ status: 503 }, 503)
          }
          if (path === '/api/v1/agent/csrf') {
            return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
          }
          if (path === '/api/v1/agent/session' && method === 'POST') {
            return pendingLogin
          }
          if (path === '/api/v1/agent/session' && method === 'DELETE') {
            return new Response(null, { status: 204 })
          }
          throw new Error(`Unexpected request: ${path}`)
        },
      )
      vi.stubGlobal('fetch', fetchMock)
      renderSession()
      await waitFor(() =>
        expect(screen.getByLabelText('session-status')).toHaveTextContent(
          'anonymous',
        ),
      )

      await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/v1/agent/session',
          expect.objectContaining({ method: 'POST' }),
        ),
      )
      localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
      putDraft(staffB.id, 1042)
      if (dispatchOwnerEvent) {
        window.dispatchEvent(
          new StorageEvent('storage', {
            key: STAFF_DRAFT_SESSION_OWNER_KEY,
            oldValue: null,
            newValue: staffB.id,
          }),
        )
      }
      await act(async () => {
        resolveLogin(new Response(null, { status: 204 }))
        await pendingLogin
      })
      await waitFor(() =>
        expect(
          screen.getByLabelText('sign-in-attempt-status'),
        ).toHaveTextContent('finished'),
      )

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'DELETE' }),
      )
      expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
      expect(
        localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
      ).toBeNull()
    },
  )

  it('keeps global invalidation enabled for non-login staff API 401 responses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://deskseed.test').pathname
      if (path === '/api/v1/agent/me') return json(staffA)
      if (path === '/api/v1/agent/views') {
        return json({ status: 401 }, 401)
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await act(async () => {
      await listAgentViews().catch(() => undefined)
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
  })

  it('logs out and invalidates shared staff state after a successful cross-tab sign-in conflict', async () => {
    const user = userEvent.setup()
    let currentStaffRequestCount = 0
    let resolveLogin!: (response: Response) => void
    const pendingLogin = new Promise<Response>((resolve) => {
      resolveLogin = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') {
          currentStaffRequestCount += 1
          return currentStaffRequestCount === 1
            ? json({ status: 401 }, 401)
            : json(staffA)
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return pendingLogin
        }
        if (path === '/api/v1/agent/session' && method === 'DELETE') {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )

    await act(async () => {
      resolveLogin(new Response(null, { status: 204 }))
      await pendingLogin
    })
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent/session',
      expect.objectContaining({ method: 'DELETE' }),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).toBeNull()
    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'anonymous',
    )
  })

  it('compensates when a successful login is superseded before verification', async () => {
    const user = userEvent.setup()
    let resolveLogin!: (response: Response) => void
    const pendingLogin = new Promise<Response>((resolve) => {
      resolveLogin = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') return json(staffA)
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return pendingLogin
        }
        if (path === '/api/v1/agent/session' && method === 'DELETE') {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveLogin(new Response(null, { status: 204 }))
      await pendingLogin
    })
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent/session',
      expect.objectContaining({ method: 'DELETE' }),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).toBeNull()
  })

  it('does not remove a newer third-account owner while compensating a sign-in conflict', async () => {
    const user = userEvent.setup()
    let currentStaffRequestCount = 0
    let resolveLogin!: (response: Response) => void
    let resolveLogout!: (response: Response) => void
    const pendingLogin = new Promise<Response>((resolve) => {
      resolveLogin = resolve
    })
    const pendingLogout = new Promise<Response>((resolve) => {
      resolveLogout = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        const method = String(init?.method ?? 'GET').toUpperCase()
        if (path === '/api/v1/agent/me') {
          currentStaffRequestCount += 1
          return currentStaffRequestCount === 1
            ? json({ status: 401 }, 401)
            : json(staffA)
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (path === '/api/v1/agent/session' && method === 'POST') {
          return pendingLogin
        }
        if (path === '/api/v1/agent/session' && method === 'DELETE') {
          return pendingLogout
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    await user.click(screen.getByRole('button', { name: 'Attempt sign in' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveLogin(new Response(null, { status: 204 }))
      await pendingLogin
    })
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/session',
        expect.objectContaining({ method: 'DELETE' }),
      ),
    )

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffC.id)
    putDraft(staffC.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffB.id,
        newValue: staffC.id,
      }),
    )
    await act(async () => {
      resolveLogout(new Response(null, { status: 204 }))
      await pendingLogout
    })
    await waitFor(() =>
      expect(screen.getByLabelText('sign-in-attempt-status')).toHaveTextContent(
        'finished',
      ),
    )

    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffC.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffC.id, 1042)),
    ).not.toBeNull()
  })

  it('invalidates an open tab when another tab ends the same staff session', async () => {
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)
    localStorage.setItem('unrelated-application-key', 'keep')
    localStorage.removeItem(STAFF_DRAFT_SESSION_OWNER_KEY)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: null,
      }),
    )

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
    expect(localStorage.getItem('unrelated-application-key')).toBe('keep')
  })

  it('does not purge a superseding account after a stale 401 from this tab', async () => {
    vi.stubGlobal(
      'fetch',
      staffFetch(() => json(staffA)),
    )
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    window.dispatchEvent(new Event(STAFF_SESSION_INVALID_EVENT))

    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('ignores a stale API 401 after this tab establishes a different account', async () => {
    const user = userEvent.setup()
    let current = staffA
    let resolveStaleRequest!: (response: Response) => void
    const staleResponse = new Promise<Response>((resolve) => {
      resolveStaleRequest = resolve
    })
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/me') return json(current)
        if (path === '/api/v1/agent/views') return staleResponse
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        if (
          path === '/api/v1/agent/session' &&
          String(init?.method).toUpperCase() === 'DELETE'
        ) {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)

    const staleRequest = listAgentViews().catch((error: unknown) => error)
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/views',
        expect.any(Object),
      ),
    )
    current = staffB
    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() =>
      expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id),
    )

    await act(async () => {
      resolveStaleRequest(json({ status: 401 }, 401))
      await staleRequest
    })

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'authenticated',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id)
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('does not restore a staff session from a refresh that resolves after global invalidation', async () => {
    const user = userEvent.setup()
    let requestCount = 0
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => {
      requestCount += 1
      return requestCount === 1 ? json(staffA) : pendingRefresh
    })
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    window.dispatchEvent(new Event(STAFF_SESSION_INVALID_EVENT))
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )

    await act(async () => {
      resolveRefresh(json(staffA))
      await pendingRefresh
    })

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'anonymous',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent('none')
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
  })

  it('ignores an older refresh 401 after a newer same-account refresh succeeds', async () => {
    const user = userEvent.setup()
    let requestCount = 0
    let resolveOlderRefresh!: (response: Response) => void
    const olderRefresh = new Promise<Response>((resolve) => {
      resolveOlderRefresh = resolve
    })
    const fetchMock = staffFetch(() => {
      requestCount += 1
      if (requestCount === 1 || requestCount === 3) return json(staffA)
      return olderRefresh
    })
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'authenticated',
      ),
    )

    await act(async () => {
      resolveOlderRefresh(json({ status: 401 }, 401))
      await olderRefresh
    })

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'authenticated',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).not.toBeNull()
  })

  it('ignores a data-request 401 started before same-account refresh success', async () => {
    const user = userEvent.setup()
    let currentStaffRequestCount = 0
    let resolveRefresh!: (response: Response) => void
    let resolveDataRequest!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const pendingDataRequest = new Promise<Response>((resolve) => {
      resolveDataRequest = resolve
    })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://deskseed.test').pathname
      if (path === '/api/v1/agent/me') {
        currentStaffRequestCount += 1
        return currentStaffRequestCount === 1 ? json(staffA) : pendingRefresh
      }
      if (path === '/api/v1/agent/views') return pendingDataRequest
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)

    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() => expect(currentStaffRequestCount).toBe(2))
    const staleRequest = listAgentViews().catch((error: unknown) => error)
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent/views',
        expect.any(Object),
      ),
    )
    await act(async () => {
      resolveRefresh(json(staffA))
      await pendingRefresh
    })
    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'authenticated',
      ),
    )

    await act(async () => {
      resolveDataRequest(json({ status: 401 }, 401))
      await staleRequest
    })

    expect(screen.getByLabelText('session-status')).toHaveTextContent(
      'authenticated',
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).not.toBeNull()
  })

  it('keeps a pending refresh when another tab confirms the same staff identity', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffA.id,
      }),
    )
    await act(async () => {
      resolveRefresh(json(staffA))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'authenticated',
      ),
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffA.id)
  })

  it('preserves a superseding account when the initial stale refresh returns 401', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveRefresh(json({ status: 401 }, 401))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('preserves a new same-account draft after an A to B to A owner cycle', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffB.id,
        newValue: staffA.id,
      }),
    )
    putDraft(staffA.id, 1042)

    await act(async () => {
      resolveRefresh(json({ status: 401 }, 401))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).not.toBeNull()
  })

  it('preserves a new same-account draft when a stale refresh resolves as B', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffB.id,
        newValue: staffA.id,
      }),
    )
    putDraft(staffA.id, 1042)

    await act(async () => {
      resolveRefresh(json(staffB))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffA.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).not.toBeNull()
  })

  it('preserves a new account after an ownerless initial refresh returns stale 401', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    putDraft(staffB.id, 1042)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveRefresh(json({ status: 401 }, 401))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(localStorage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY)).toBe(staffB.id)
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('purges the previous account when the initial refresh resolves as the new owner', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetchMock = staffFetch(() => pendingRefresh)
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffA.id)
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)
    renderSession()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: staffA.id,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveRefresh(json(staffB))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id),
    )
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
  })

  it('rejects a pending refresh when another tab switches to a different staff identity', async () => {
    let resolveRefresh!: (response: Response) => void
    const pendingRefresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    vi.stubGlobal(
      'fetch',
      staffFetch(() => pendingRefresh),
    )
    renderSession()

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'loading',
      ),
    )
    localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffB.id)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: STAFF_DRAFT_SESSION_OWNER_KEY,
        oldValue: null,
        newValue: staffB.id,
      }),
    )
    await act(async () => {
      resolveRefresh(json(staffA))
      await pendingRefresh
    })

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(screen.getByLabelText('staff-id')).toHaveTextContent('none')
  })

  it('uses the remembered staff identity to purge drafts after a reload discovers a 401', async () => {
    let expired = false
    vi.stubGlobal(
      'fetch',
      staffFetch(() => (expired ? json({ status: 401 }, 401) : json(staffA))),
    )
    const firstRender = renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    localStorage.setItem('unrelated-application-key', 'keep')
    firstRender.unmount()

    expired = true
    renderSession()

    await waitFor(() =>
      expect(screen.getByLabelText('session-status')).toHaveTextContent(
        'anonymous',
      ),
    )
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(localStorage.getItem('unrelated-application-key')).toBe('keep')
  })

  it('keeps drafts for a same-account refresh and purges only the previous account on change', async () => {
    const user = userEvent.setup()
    let current = staffA
    const fetchMock = staffFetch(() => json(current))
    vi.stubGlobal('fetch', fetchMock)
    renderSession()
    expect(await screen.findByText(staffA.id)).toBeVisible()
    putDraft(staffA.id, 1042)
    putDraft(staffB.id, 1042)
    localStorage.setItem('unrelated-application-key', 'keep')

    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).not.toBeNull()

    current = staffB
    await user.click(screen.getByRole('button', { name: 'Refresh session' }))
    await waitFor(() =>
      expect(screen.getByLabelText('staff-id')).toHaveTextContent(staffB.id),
    )

    expect(
      localStorage.getItem(ticketDraftStorageKey(staffA.id, 1042)),
    ).toBeNull()
    expect(
      localStorage.getItem(ticketDraftStorageKey(staffB.id, 1042)),
    ).not.toBeNull()
    expect(localStorage.getItem('unrelated-application-key')).toBe('keep')
  })
})
