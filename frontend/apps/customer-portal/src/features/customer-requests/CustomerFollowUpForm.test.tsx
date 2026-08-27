import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { CustomerFollowUpForm } from './CustomerFollowUpForm'
import { createMemoryRouter, Link, RouterProvider } from 'react-router'
import type { AttachmentUpload } from '../../api/types'

function renderForm(element: React.ReactElement) {
  const router = createMemoryRouter([{ path: '/', element }])
  return render(<RouterProvider router={router} />)
}

describe('CustomerFollowUpForm', () => {
  it('reuses the same client command ID for an ambiguous retry and does not render optimistic customer content', async () => {
    const user = userEvent.setup()
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(undefined)
    renderForm(<CustomerFollowUpForm onSubmit={submit} />)

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
    renderForm(
      <CustomerFollowUpForm onConflict={onConflict} onSubmit={submit} />,
    )

    await user.type(screen.getByLabelText('추가 답변'), '추가 정보입니다.')
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    expect(await screen.findByText('문의 상태가 변경되었습니다.')).toBeVisible()
    expect(onConflict).toHaveBeenCalledOnce()
    expect(screen.getByLabelText('추가 답변')).toHaveValue('추가 정보입니다.')

    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1]?.[1]).not.toBe(submit.mock.calls[0]?.[1])
  })

  it('rotates the logical command when attachment IDs change after an ambiguous failure', async () => {
    const user = userEvent.setup()
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(undefined)
    const uploadAttachment = vi.fn(
      async (file: File): Promise<AttachmentUpload> => ({
        id:
          file.name === 'a.png'
            ? '11111111-1111-4111-8111-111111111111'
            : '22222222-2222-4222-8222-222222222222',
        fileName: file.name,
        sizeBytes: file.size,
        contentType: file.type,
        scanStatus: 'CLEAN',
        expiresAt: '2099-08-17T05:00:00Z',
      }),
    )
    renderForm(
      <CustomerFollowUpForm
        onSubmit={submit}
        uploadAttachment={uploadAttachment}
      />,
    )
    await user.type(screen.getByLabelText('추가 답변'), '첨부를 확인해 주세요.')
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['a'], 'a.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))
    await screen.findByText('답변 전송 결과를 확인할 수 없습니다.')
    const firstAttempt = submit.mock.calls[0]
    await user.click(screen.getByRole('button', { name: '초안에서 제거' }))
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['b'], 'b.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1]?.[1]).not.toBe(firstAttempt?.[1])
    expect(submit.mock.calls[1]?.[2]).toEqual([
      '22222222-2222-4222-8222-222222222222',
    ])
  })

  it.each([
    ['uploading', () => new Promise<AttachmentUpload>(() => {})],
    [
      'rejected',
      async () => {
        throw new ApiError('rejected', 422)
      },
    ],
  ])(
    'blocks internal navigation while an attachment is %s',
    async (_, uploadAttachment) => {
      const user = userEvent.setup()
      const router = createMemoryRouter([
        {
          path: '/',
          element: (
            <>
              <CustomerFollowUpForm
                onSubmit={vi.fn()}
                uploadAttachment={uploadAttachment}
              />
              <Link to="/next">다음 화면</Link>
            </>
          ),
        },
        { path: '/next', element: <p>다음 화면 도착</p> },
      ])
      render(<RouterProvider router={router} />)
      await user.upload(
        screen.getByLabelText('PUBLIC 첨부 파일'),
        new File(['x'], 'x.exe'),
      )
      if (String(_).includes('rejected'))
        await screen.findByText(/감염 또는 격리됨/)
      await user.click(screen.getByRole('link', { name: '다음 화면' }))
      expect(
        await screen.findByRole('alertdialog', { name: '첨부 파일 이동 경고' }),
      ).toBeVisible()
      expect(screen.queryByText('다음 화면 도착')).not.toBeInTheDocument()
    },
  )
})
