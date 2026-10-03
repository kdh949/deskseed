import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AgentOpenTicketsProvider,
  readOpenTickets,
  useAgentOpenTickets,
  useRememberAgentTicket,
} from './AgentOpenTickets'

function Ticket({
  loaded = false,
  denied = false,
}: {
  loaded?: boolean
  denied?: boolean
}) {
  const tickets = useAgentOpenTickets()
  useRememberAgentTicket(1042, loaded, denied)
  return (
    <>
      <output aria-label="열린 번호">{tickets?.numbers.join(',')}</output>
      <button onClick={() => tickets?.remember(1043)}>두 번째 열기</button>
      <button onClick={() => tickets?.forget(1042)}>첫 번째 닫기</button>
    </>
  )
}

describe('staff-owned open tickets', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.restoreAllMocks())
  it('stores only validated unique ticket numbers and tolerates invalid storage', () => {
    sessionStorage.setItem(
      'deskseed:open-tickets:v1:a',
      '[1042,1042,"customer@example.test",-1,1.2,null]',
    )
    expect(readOpenTickets('a')).toEqual([1042])
    sessionStorage.setItem('deskseed:open-tickets:v1:a', '{broken')
    expect(readOpenTickets('a')).toEqual([])
  })
  it('registers successful reads, closes explicit tabs, and removes denied tickets', () => {
    const ui = render(
      <AgentOpenTicketsProvider staffId="a">
        <Ticket loaded />
      </AgentOpenTicketsProvider>,
    )
    expect(screen.getByLabelText('열린 번호')).toHaveTextContent('1042')
    fireEvent.click(screen.getByRole('button', { name: '첫 번째 닫기' }))
    expect(screen.getByLabelText('열린 번호')).toHaveTextContent('')
    fireEvent.click(screen.getByRole('button', { name: '두 번째 열기' }))
    ui.rerender(
      <AgentOpenTicketsProvider staffId="a">
        <Ticket denied />
      </AgentOpenTicketsProvider>,
    )
    expect(readOpenTickets('a')).toEqual([1043])
  })
  it('keeps account state isolated even when the provider is reused', () => {
    sessionStorage.setItem('deskseed:open-tickets:v1:b', '[1099]')
    const ui = render(
      <AgentOpenTicketsProvider staffId="a">
        <Ticket loaded />
      </AgentOpenTicketsProvider>,
    )
    ui.rerender(
      <AgentOpenTicketsProvider staffId="b">
        <Ticket />
      </AgentOpenTicketsProvider>,
    )
    expect(screen.getByLabelText('열린 번호')).toHaveTextContent('1099')
    expect(readOpenTickets('a')).toEqual([1042])
    fireEvent.click(screen.getByRole('button', { name: '두 번째 열기' }))
    expect(readOpenTickets('b')).toEqual([1099, 1043])
  })
  it('keeps navigation usable when browser storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    render(
      <AgentOpenTicketsProvider staffId="a">
        <Ticket loaded />
      </AgentOpenTicketsProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: '두 번째 열기' }))
    expect(screen.getByLabelText('열린 번호')).toHaveTextContent('1042,1043')
  })
})
