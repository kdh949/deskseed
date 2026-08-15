import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { CustomerRequestForm } from './CustomerRequestForm'

describe('CustomerRequestForm', () => {
  it('submits a valid anonymous request and hands its one-time result to the route owner', async () => {
    const user = userEvent.setup()
    const submit = vi.fn().mockResolvedValue({
      ticketNumber: 1042,
      status: 'NEW',
      accessToken: 'a'.repeat(43),
      createdAt: '2026-08-15T00:00:00Z',
    })
    const onSubmitted = vi.fn()
    render(<CustomerRequestForm onSubmitted={onSubmitted} submit={submit} />)

    expect(screen.getByRole('button', { name: '문의 접수' })).toBeDisabled()
    await user.type(screen.getByLabelText('이름'), '김민아')
    await user.type(screen.getByLabelText('이메일'), 'mina@example.test')
    await user.type(screen.getByLabelText('제목'), '결제 확인 요청')
    await user.type(
      screen.getByLabelText('문의 내용'),
      '결제 승인 내역을 확인해 주세요.',
    )
    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    expect(submit).toHaveBeenCalledWith({
      name: '김민아',
      email: 'mina@example.test',
      subject: '결제 확인 요청',
      message: '결제 승인 내역을 확인해 주세요.',
    })
    expect(onSubmitted).toHaveBeenCalledWith(
      expect.objectContaining({ ticketNumber: 1042 }),
    )
  })

  it('preserves customer input and presents an explicit rate-limit recovery message', async () => {
    const user = userEvent.setup()
    const submit = vi
      .fn()
      .mockRejectedValue(
        new ApiError('요청이 많습니다.', 429, undefined, 'req-rate-1', '60'),
      )
    render(<CustomerRequestForm onSubmitted={vi.fn()} submit={submit} />)

    await user.type(screen.getByLabelText('이름'), '김민아')
    await user.type(screen.getByLabelText('이메일'), 'mina@example.test')
    await user.type(screen.getByLabelText('제목'), '결제 확인 요청')
    await user.type(
      screen.getByLabelText('문의 내용'),
      '결제 승인 내역을 확인해 주세요.',
    )
    await user.click(screen.getByRole('button', { name: '문의 접수' }))

    expect(await screen.findByText(/60초 후 다시 시도/)).toBeVisible()
    expect(screen.getByLabelText('문의 내용')).toHaveValue(
      '결제 승인 내역을 확인해 주세요.',
    )
  })
})
