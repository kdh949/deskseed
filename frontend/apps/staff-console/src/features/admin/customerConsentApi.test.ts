import { afterEach, describe, expect, it, vi } from 'vitest'
import { setConfirmedStaffActor } from '../../api/client'
import {
  createConsentPolicy,
  decodeConsentDocument,
  decodeConsentPolicy,
  getConsentPolicy,
  listConsentPolicies,
  transitionConsentPolicy,
  updateConsentPolicy,
  validConsentDraft,
  type ConsentPolicy,
} from './customerConsentApi'

const policy: ConsentPolicy = {
  id: '11111111-1111-4111-8111-111111111111',
  policyKey: 'synthetic-terms',
  context: 'REGISTRATION',
  lifecycle: 'DRAFT',
  aggregateVersion: 4,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  draft: {
    title: '합성 검증 정책',
    required: true,
    displayOrder: 1,
    document: {
      schemaVersion: 1,
      blocks: [{ type: 'paragraph', text: '합성 테스트 문서' }],
    },
    draftVersion: 1,
    updatedAt: '2026-09-01T00:00:00Z',
  },
  publishedVersion: null,
  versions: [],
}
const draft = {
  title: policy.draft.title,
  required: true,
  displayOrder: 1,
  document: policy.draft.document,
}
afterEach(() => {
  vi.unstubAllGlobals()
  setConfirmedStaffActor(null)
})
describe('customer consent client', () => {
  it('uses the confirmed actor, CSRF, create precondition and aggregate version on every command', async () => {
    const actor = '22222222-2222-4222-8222-222222222222'
    setConfirmedStaffActor(actor)
    const requests: { path: string; init?: RequestInit }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, init?: RequestInit) => {
        requests.push({ path, init })
        if (path === '/api/v1/agent/csrf')
          return Response.json({
            token: 'synthetic-csrf',
            headerName: 'X-CSRF-TOKEN',
          })
        return Response.json(policy)
      }),
    )
    await getConsentPolicy(policy.id)
    await createConsentPolicy(draft, policy.policyKey, policy.context)
    await updateConsentPolicy(policy, draft)
    await transitionConsentPolicy(policy, 'publish')
    await transitionConsentPolicy(policy, 'archive')
    const commands = requests.filter(
      (request) => request.init?.method && request.init.method !== 'GET',
    )
    expect(commands).toHaveLength(4)
    for (const { init } of requests) {
      expect(
        new Headers(init?.headers).get('X-Deskseed-Expected-Staff-Id'),
      ).toBe(actor)
      expect(init?.cache).toBe('no-store')
    }
    for (const { init } of commands)
      expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe(
        'synthetic-csrf',
      )
    expect(new Headers(commands[0]!.init?.headers).get('If-None-Match')).toBe(
      '*',
    )
    expect(JSON.parse(String(commands[0]!.init?.body))).toEqual({
      ...draft,
      policyKey: policy.policyKey,
      context: policy.context,
    })
    for (const { init } of commands.slice(1))
      expect(new Headers(init?.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(commands[1]!.init?.body))).toEqual(draft)
    expect(commands[2]!.path).toMatch(/\/publish$/)
    expect(commands[3]!.path).toMatch(/\/archive$/)
  })
  it('preserves response failures and rejects a malformed policy document', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json(
          { status: 412, currentVersion: 8, requestId: 'req-conflict' },
          { status: 412 },
        ),
      ),
    )
    await expect(getConsentPolicy(policy.id)).rejects.toMatchObject({
      status: 412,
      requestId: 'req-conflict',
    })
    expect(decodeConsentPolicy(policy)).toEqual(policy)
    expect(
      decodeConsentPolicy({
        ...policy,
        draft: {
          ...draft,
          document: {
            schemaVersion: 1,
            blocks: [{ type: 'script', text: 'unsafe' }],
          },
        },
      }),
    ).toBeUndefined()
    expect(
      decodeConsentDocument({
        schemaVersion: 1,
        blocks: [{ type: 'link', text: 'link', url: 'javascript:alert(1)' }],
      }),
    ).toBeUndefined()
    expect(
      decodeConsentDocument({
        schemaVersion: 1,
        blocks: [{ type: 'heading', level: '2', text: 'heading' }],
      }),
    ).toBeUndefined()
    expect(validConsentDraft({ ...draft, title: '<script>' })).toBe(false)
    expect(validConsentDraft({ ...draft, title: 'invalid\u0080title' })).toBe(
      false,
    )
    expect(
      decodeConsentDocument({
        schemaVersion: 1,
        blocks: [
          {
            type: 'link',
            text: 'link',
            url: 'https://user:pass@example.test/policy',
          },
        ],
      }),
    ).toBeUndefined()
    const oversized = {
      ...policy,
      draft: {
        ...policy.draft,
        document: {
          schemaVersion: 1,
          blocks: Array.from({ length: 6 }, () => ({
            type: 'paragraph',
            text: 'a'.repeat(10000),
          })),
        },
      },
    }
    expect(decodeConsentPolicy(oversized)).toEqual(oversized)
    expect(validConsentDraft(oversized.draft)).toBe(false)
    expect(
      validConsentDraft({
        ...draft,
        document: {
          schemaVersion: 1,
          blocks: Array.from({ length: 6 }, () => ({
            type: 'paragraph',
            text: 'a'.repeat(10000),
          })),
        },
      }),
    ).toBe(false)
  })
  it('passes filters and bounded pagination to the existing list contract', async () => {
    const fetch = vi.fn<(path: string) => Promise<Response>>(async () =>
      Response.json({
        items: [],
        page: 2,
        size: 20,
        totalCount: 0,
        totalPages: 0,
      }),
    )
    vi.stubGlobal('fetch', fetch)
    await listConsentPolicies('REGISTRATION', 'PUBLISHED', 2)
    expect(fetch.mock.calls[0]?.[0]).toBe(
      '/api/v1/admin/customer-consent-policies?page=2&size=20&context=REGISTRATION&lifecycle=PUBLISHED',
    )
  })
})
