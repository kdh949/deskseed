import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import type { IntegrationClient } from '../api/types'
import { IntegrationClientsPage } from './IntegrationClientsPage'

const apiMocks = vi.hoisted(() => ({
  listIntegrationClients: vi.fn(),
  createIntegrationClient: vi.fn(),
  disableIntegrationClient: vi.fn(),
  revokeIntegrationClient: vi.fn(),
  rotateIntegrationClientCredential: vi.fn(),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../api/client')
  return { ...actual, ...apiMocks }
})

const credential = {
  id: '22222222-2222-4222-8222-222222222222',
  sequence: 1,
  publicKeyId: 'publicKeyId12345',
  status: 'ACTIVE' as const,
  expiresAt: '2026-12-31T00:00:00Z',
  overlapExpiresAt: null,
  createdAt: '2026-08-12T00:00:00Z',
  revokedAt: null,
  lastUsedAt: null,
  lastUsedIp: null,
}

const client: IntegrationClient = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '주문 시스템',
  description: '주문 운영 연동',
  status: 'ACTIVE',
  scopes: ['tickets:read'],
  resourceConstraints: {
    allowedGroupIds: null,
    allowedTicketKinds: ['CUSTOMER_REQUEST'],
    allowedFields: null,
    ipAllowlist: ['10.0.0.0/8'],
  },
  credentials: [credential],
  expiresAt: credential.expiresAt,
  lastUsedAt: null,
  lastUsedIp: null,
  createdAt: credential.createdAt,
}

describe('IntegrationClientsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listIntegrationClients.mockResolvedValue({
      items: [client],
      page: 0,
      size: 20,
      totalCount: 1,
      totalPages: 1,
    })
  })

  it('creates a constrained client and keeps the issued secret only in component state', async () => {
    const user = userEvent.setup()
    const apiKey = `dsk_live_publicKeyId12345.${'A'.repeat(43)}`
    apiMocks.createIntegrationClient.mockResolvedValue({
      client,
      credential,
      apiKey,
    })

    render(<IntegrationClientsPage />)
    await screen.findByText('주문 시스템')
    await user.type(screen.getByLabelText('이름'), '결제 시스템')
    await user.type(screen.getByLabelText('설명'), '결제 운영 연동')
    await user.click(screen.getByLabelText(/티켓 필드 수정/))
    await user.type(screen.getByLabelText(/IP\/CIDR allowlist/), '10.20.0.0/16')
    await user.click(
      screen.getByRole('button', { name: '클라이언트와 key 발급' }),
    )

    await waitFor(() =>
      expect(apiMocks.createIntegrationClient).toHaveBeenCalledTimes(1),
    )
    expect(apiMocks.createIntegrationClient.mock.calls[0]?.[0]).toMatchObject({
      name: '결제 시스템',
      scopes: ['tickets:read', 'tickets:update'],
      resourceConstraints: { ipAllowlist: ['10.20.0.0/16'] },
    })
    expect(await screen.findByLabelText('발급된 API key')).toHaveValue(apiKey)
    expect(localStorage.getItem('apiKey')).toBeNull()
    expect(sessionStorage.getItem('apiKey')).toBeNull()

    await user.click(screen.getByRole('button', { name: '복사 완료 · 닫기' }))
    expect(screen.queryByText(apiKey)).not.toBeInTheDocument()
  })

  it('rotates with bounded overlap then exposes the new key once', async () => {
    const user = userEvent.setup()
    const rotated = {
      ...credential,
      id: '33333333-3333-4333-8333-333333333333',
      sequence: 2,
      publicKeyId: 'rotatedKeyId1234',
    }
    apiMocks.rotateIntegrationClientCredential.mockResolvedValue({
      client: {
        ...client,
        credentials: [rotated, { ...credential, status: 'RETIRING' }],
      },
      credential: rotated,
      apiKey: `dsk_live_rotatedKeyId1234.${'B'.repeat(43)}`,
    })

    render(<IntegrationClientsPage />)
    await user.click(await screen.findByRole('button', { name: 'Key 회전' }))
    const heading = await screen.findByRole('heading', { name: /Key 회전/ })
    expect(heading).toHaveFocus()
    await user.clear(screen.getByLabelText('기존 key overlap 시간'))
    await user.type(screen.getByLabelText('기존 key overlap 시간'), '24')
    await user.click(
      screen.getByRole('button', { name: '회전하고 새 key 보기' }),
    )

    await waitFor(() =>
      expect(apiMocks.rotateIntegrationClientCredential).toHaveBeenCalledWith(
        client.id,
        expect.objectContaining({ overlapSeconds: 86_400 }),
      ),
    )
    expect(await screen.findByLabelText('발급된 API key')).toHaveValue(
      `dsk_live_rotatedKeyId1234.${'B'.repeat(43)}`,
    )
  })
})
