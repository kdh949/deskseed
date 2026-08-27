import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type {
  AgentTicketBatchCommand,
  AgentTicketBatchResult,
} from '../../api/types'
import { BulkTicketActionPanel } from './BulkTicketActionPanel'

const ticket = {
  ticketNumber: 1042,
  subject: '결제 승인 오류',
  status: 'OPEN' as const,
  priority: 'HIGH' as const,
  requester: { id: null, type: 'CUSTOMER' as const, displayName: '김민수' },
  group: null,
  assignee: null,
  updatedAt: '2026-08-17T03:00:00Z',
  version: 7,
  isChild: false,
  openChildCount: 0,
  sla: null,
}

function result(
  command: AgentTicketBatchCommand,
  outcome: 'SUCCEEDED' | 'CONFLICT' = 'SUCCEEDED',
): AgentTicketBatchResult {
  return {
    correlationId: crypto.randomUUID(),
    results: command.items.map((item) => ({
      ticketNumber: item.ticketNumber,
      clientCommandId: item.clientCommandId,
      outcome,
      replayed: false,
      resultVersion: outcome === 'SUCCEEDED' ? item.expectedVersion + 1 : null,
      auditId: outcome === 'SUCCEEDED' ? crypto.randomUUID() : null,
      code: outcome === 'CONFLICT' ? 'VERSION_PRECONDITION_FAILED' : null,
    })),
  }
}

function renderPanel(
  execute: (
    command: AgentTicketBatchCommand,
  ) => Promise<AgentTicketBatchResult>,
) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <BulkTicketActionPanel
        execute={execute}
        options={{ groups: [] }}
        tickets={[ticket]}
      />
    </QueryClientProvider>,
  )
}

async function confirm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '실행 전 확인' }))
  await user.click(screen.getByRole('button', { name: '확인하고 실행' }))
}

describe('BulkTicketActionPanel', () => {
  it('reuses an exact ambiguous attempt but rotates IDs for the next successful command', async () => {
    const user = userEvent.setup()
    const execute = vi.fn(async (command: AgentTicketBatchCommand) => {
      if (execute.mock.calls.length === 1) throw new Error('network')
      return result(command)
    })
    renderPanel(execute)
    await user.click(screen.getByRole('button', { name: '일괄 작업' }))
    await confirm(user)
    await screen.findByText('일괄 작업 요청을 완료하지 못했습니다.')
    await user.click(screen.getByRole('button', { name: '확인하고 실행' }))
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(2))
    expect(execute.mock.calls[1]?.[0]).toEqual(execute.mock.calls[0]?.[0])

    await user.selectOptions(
      screen.getByLabelText('일괄 작업 종류'),
      'PRIORITY',
    )
    await confirm(user)
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(3))
    expect(execute.mock.calls[2]?.[0].items[0]?.clientCommandId).not.toBe(
      execute.mock.calls[1]?.[0].items[0]?.clientCommandId,
    )
  })

  it('retries failed items from the submitted snapshot after the form changes', async () => {
    const user = userEvent.setup()
    const execute = vi.fn(async (command: AgentTicketBatchCommand) =>
      execute.mock.calls.length === 1
        ? result(command, 'CONFLICT')
        : result(command),
    )
    renderPanel(execute)
    await user.click(screen.getByRole('button', { name: '일괄 작업' }))
    await confirm(user)
    await screen.findByText('충돌')
    const submitted = structuredClone(execute.mock.calls[0]?.[0])
    await user.selectOptions(screen.getByLabelText('변경할 상태'), 'SOLVED')
    await user.click(
      screen.getByRole('button', { name: '실패한 1개 다시 시도' }),
    )
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(2))
    expect(execute.mock.calls[1]?.[0]).toEqual(submitted)
  })
})
