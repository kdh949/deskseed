import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { CustomerFollowUpForm } from './CustomerFollowUpForm'

describe('CustomerFollowUpForm', () => {
  it('reuses the same client command ID for an ambiguous retry and does not render optimistic customer content', async () => {
    const user = userEvent.setup()
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(undefined)
    render(<CustomerFollowUpForm onSubmit={submit} />)

    await user.type(
      screen.getByLabelText('추가 답변'),
      '주문 번호는 ORD-1042입니다.',
    )
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    expect(
      await screen.findByText('답변 전송 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    expect(screen.getByText('주문 번호는 ORD-1042입니다.')).toBeVisible()
    expect(screen.queryByText('답변이 저장되었습니다.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1]?.[1]).toBe(submit.mock.calls[0]?.[1])
    expect(screen.getByLabelText('추가 답변')).toHaveValue('')
  })

  it('preserves the draft but rotates the command identity after a definite conflict', async () => {
    const user = userEvent.setup()
    const submit = vi
      .fn()
      .mockRejectedValueOnce(
        new ApiError('Request is solved.', 409, undefined, 'req-conflict'),
      )
      .mockResolvedValueOnce(undefined)
    const onConflict = vi.fn()
    render(<CustomerFollowUpForm onConflict={onConflict} onSubmit={submit} />)

    await user.type(screen.getByLabelText('추가 답변'), '추가 정보입니다.')
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    expect(await screen.findByText('문의 상태가 변경되었습니다.')).toBeVisible()
    expect(onConflict).toHaveBeenCalledOnce()
    expect(screen.getByLabelText('추가 답변')).toHaveValue('추가 정보입니다.')

    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1]?.[1]).not.toBe(submit.mock.calls[0]?.[1])
  })
})
