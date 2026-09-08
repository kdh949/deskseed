import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  listAgentNotifications,
  setConfirmedStaffActor,
} from '../../api/client'
import { decodeTrigger } from './api'
afterEach(() => {
  vi.unstubAllGlobals()
  setConfirmedStaffActor(null)
})
describe('trigger and notification contracts', () => {
  it('normalizes omitted assignment to an explicit clear action and preserves the selected event', () => {
    const rule = {
      id: '11111111-1111-4111-8111-111111111111',
      name: '고객 재답변',
      position: 1,
      currentVersion: 1,
      aggregateVersion: 1,
      createdAt: '2026-09-08T00:00:00Z',
      updatedAt: '2026-09-08T00:00:00Z',
      conditions: [
        {
          group: 'ALL',
          field: 'EVENT',
          operator: 'IS',
          value: 'CUSTOMER_REPLIED',
        },
      ],
      actions: [{ type: 'SET_ASSIGNEE' }],
    }
    expect(decodeTrigger(rule)?.actions).toEqual([
      { type: 'SET_ASSIGNEE', assigneeId: null },
    ])
    expect(
      decodeTrigger({ ...rule, actions: [{ type: 'EXECUTE_SCRIPT' }] }),
    ).toBeUndefined()
  })
  it('accepts a trigger alert without a collaboration note and rejects staff impersonation', async () => {
    const notification = {
      id: '11111111-1111-4111-8111-111111111111',
      type: 'UNASSIGNED_TICKET_ALERT',
      ticketNumber: 1042,
      noteId: null,
      actor: {
        id: '22222222-2222-4222-8222-222222222222',
        type: 'TRIGGER',
        displayName: '미배정 알림',
      },
      createdAt: '2026-09-08T00:00:00Z',
      readAt: null,
    }
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [notification],
            unreadCount: 1,
            nextCursor: null,
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [
              {
                ...notification,
                actor: { ...notification.actor, type: 'STAFF' },
              },
            ],
            unreadCount: 1,
            nextCursor: null,
          }),
          { status: 200 },
        ),
      )
    vi.stubGlobal('fetch', fetch)
    expect((await listAgentNotifications()).items[0]?.actor.type).toBe(
      'TRIGGER',
    )
    await expect(listAgentNotifications()).rejects.toThrow()
  })
})
