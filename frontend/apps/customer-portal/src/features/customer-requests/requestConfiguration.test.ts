import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  decodeForm,
  decodePolicies,
  loadRequestConfiguration,
} from './requestConfiguration'
import { submitRequestWithAttachments } from '../../api/client'

afterEach(() => vi.unstubAllGlobals())

describe('customer request configuration boundary', () => {
  it('rejects a malformed consent entry instead of silently dropping a required policy', () => {
    expect(() =>
      decodePolicies({
        context: 'REQUEST_SUBMISSION',
        policies: [
          {
            policyKey: 'required',
            version: 1,
            title: '필수 동의',
            required: true,
          },
        ],
      }),
    ).toThrow()
    expect(() =>
      decodePolicies({
        context: 'REQUEST_SUBMISSION',
        policies: [
          {
            policyKey: 'required',
            version: 1,
            title: '필수 동의',
            required: true,
            document: { blocks: [{ type: 'html', html: '<script>' }] },
          },
        ],
      }),
    ).toThrow()
  })
  it('requires typed safe form metadata before rendering or enabling submission', () => {
    expect(() =>
      decodeForm({
        formId: 'id',
        formVersion: 1,
        fields: [
          {
            field: { type: 'SHORT_TEXT', staffLabel: 'private' },
            visible: true,
          },
        ],
      }),
    ).toThrow()
  })
  it('uses core-only intake only for an explicit unavailable default form, not a failed configuration service', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          new Response(
            JSON.stringify(
              String(url).includes('consent-policies')
                ? { context: 'REQUEST_SUBMISSION', policies: [] }
                : { type: '/problems/customer-ticket-form-unavailable' },
            ),
            { status: String(url).includes('consent-policies') ? 200 : 404 },
          ),
        ),
      ),
    )
    await expect(loadRequestConfiguration()).resolves.toEqual({
      form: null,
      policies: [],
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('{}', { status: 503 }))),
    )
    await expect(loadRequestConfiguration()).rejects.toThrow()
  })
  it('sends one JSON multipart request part with the same form and command identity as JSON intake', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            status: 'NEW',
            accessToken: 'a'.repeat(43),
            createdAt: '2026-09-08T00:00:00Z',
            replayed: false,
          }),
          { status: 201 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    const input = {
      clientCommandId: '33333333-3333-4333-8333-333333333333',
      requester: { name: '고객', email: 'customer@example.test' },
      subject: '주문 확인',
      message: '확인 부탁드립니다.',
      fieldValues: {},
      acceptedPolicies: [],
    }
    await submitRequestWithAttachments(input, [
      new File(['file'], 'receipt.txt'),
    ])
    const body = fetchMock.mock.calls[0]![1].body as FormData
    expect(body.get('request')).toBeInstanceOf(Blob)
    expect((body.get('request') as Blob).type).toBe('application/json')
    expect(body.get('name')).toBeNull()
    expect(body.get('privacyConsent')).toBeNull()
    expect(body.getAll('attachments')).toHaveLength(1)
  })
})
