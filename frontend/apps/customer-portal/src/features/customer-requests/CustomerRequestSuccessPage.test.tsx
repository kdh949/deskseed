import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, it } from 'vitest'
import { CustomerRequestSuccessPage } from './CustomerRequestSuccessPage'
function show(number: string, submitted?: object) {
  return render(
    <MemoryRouter
      initialEntries={[
        { pathname: `/requests/submitted/${number}`, state: { submitted } },
      ]}
    >
      <Routes>
        <Route
          path="/requests/submitted/:ticketNumber"
          element={<CustomerRequestSuccessPage />}
        />
      </Routes>
    </MemoryRouter>,
  )
}
it.each(['not-a-number', '0', '-1', '9007199254740993'])(
  'never invents receipt success for invalid number %s',
  (number) => {
    show(number)
    expect(screen.getByText('문의 번호를 확인해 주세요.')).toBeVisible()
    expect(screen.queryByText(/문의 접수가 완료/)).not.toBeInTheDocument()
  },
)
it.each([
  undefined,
  { ticketNumber: 43, status: 'NEW', createdAt: '2026-09-01' },
  { ticketNumber: 42, status: 'NEW', createdAt: 'invalid' },
])(
  'requires a valid matching server receipt before claiming success',
  (submitted) => {
    show('42', submitted)
    expect(screen.getByText('접수 결과를 확인해 주세요.')).toBeVisible()
    expect(screen.queryByText('방금 전')).not.toBeInTheDocument()
  },
)
it('shows the server receipt status including a replay of an already solved request', () => {
  show('42', {
    ticketNumber: 42,
    status: 'SOLVED',
    createdAt: '2026-09-01T00:00:00Z',
  })
  expect(screen.getByText('문의 접수가 완료되었습니다')).toBeVisible()
  expect(screen.getAllByText('해결됨')).toHaveLength(2)
  expect(screen.queryByText('접수됨')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '문의 보기' })).toHaveAttribute(
    'href',
    '/requests/42',
  )
})
