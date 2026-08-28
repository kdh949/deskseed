import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { CustomerRequestSuccessPage } from './CustomerRequestSuccessPage'

describe('CustomerRequestSuccessPage', () => {
  it('shows only contract-backed request facts and implemented next actions', () => {
    render(
      <MemoryRouter initialEntries={['/requests/submitted/1288']}>
        <Routes>
          <Route
            path="/requests/submitted/:ticketNumber"
            element={<CustomerRequestSuccessPage />}
          />
        </Routes>
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('heading', { name: '문의 접수가 완료되었습니다' }),
    ).toBeVisible()
    expect(screen.getAllByText('#DS-1288')[0]).toBeVisible()
    expect(
      screen.queryByText(/예상 첫 답변|4시간 이내/),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: '추천 문서' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '문의 보기' })).toHaveAttribute(
      'href',
      '/requests/1288',
    )
  })
})
