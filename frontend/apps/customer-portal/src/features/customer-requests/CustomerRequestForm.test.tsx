import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { CustomerRequestForm } from './CustomerRequestForm'
import { CustomerRequestCustomFields } from './CustomerRequestCustomFields'

const emptyConfiguration = async () => ({ form: null, policies: [] })

describe('CustomerRequestForm', () => {
  it('describes the request journey without exposing conversation visibility terms', () => {
    render(
      <CustomerRequestForm
        loadConfiguration={emptyConfiguration}
        onSubmitted={vi.fn()}
        submit={vi.fn()}
      />,
    )

    expect(
      screen.getByText(
        '문의 내용을 남겨 주시면 진행 상황과 답변을 알려 드립니다.',
      ),
    ).toBeVisible()
    expect(
      screen.getByText(
        '접수 후 이메일 링크 또는 문의 조회 화면에서 대화를 확인할 수 있습니다.',
      ),
    ).toBeVisible()
    expect(screen.queryByText(/공개 대화/)).not.toBeInTheDocument()
  })

  it('submits a valid anonymous request and hands its one-time result to the route owner', async () => {
    const user = userEvent.setup()
    const submit = vi.fn().mockResolvedValue({
      ticketNumber: 1042,
      status: 'NEW',
      accessToken: 'a'.repeat(43),
      createdAt: '2026-08-15T00:00:00Z',
    })
    const onSubmitted = vi.fn()
    render(
      <CustomerRequestForm
        loadConfiguration={emptyConfiguration}
        onSubmitted={onSubmitted}
        submit={submit}
      />,
    )

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
      clientCommandId: expect.any(String),
      requester: { name: '김민아', email: 'mina@example.test' },
      fieldValues: {},
      acceptedPolicies: [],
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
    render(
      <CustomerRequestForm
        loadConfiguration={emptyConfiguration}
        onSubmitted={vi.fn()}
        submit={submit}
      />,
    )

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

  it('rejects more than five initial attachments before submit', async () => {
    const user = userEvent.setup()
    const submit = vi.fn()
    render(
      <CustomerRequestForm
        loadConfiguration={emptyConfiguration}
        onSubmitted={vi.fn()}
        submit={submit}
      />,
    )

    await user.upload(
      screen.getByLabelText('첨부 파일'),
      Array.from({ length: 6 }, (_, index) => new File(['x'], `${index}.txt`)),
    )

    expect(screen.getByRole('alert')).toHaveTextContent('최대 5개')
    expect(screen.getByText('선택된 파일이 없습니다.')).toBeVisible()
    expect(submit).not.toHaveBeenCalled()
  })
})

it('does not silently round an over-precise number and permits correction', () => {
  const change = vi.fn()
  render(
    <CustomerRequestCustomFields
      values={{}}
      change={change}
      fields={[
        {
          field: {
            id: 'amount',
            machineKey: 'amount',
            type: 'NUMBER',
            label: '금액',
            description: null,
            validation: {},
          },
          visible: true,
          editable: true,
          required: false,
          options: [],
        },
      ]}
    />,
  )
  const amount = screen.getByRole('spinbutton', { name: '금액' })
  fireEvent.change(amount, { target: { value: '9007199254740993' } })
  expect(amount).toBeInvalid()
  expect(change).toHaveBeenLastCalledWith('amount', undefined)
  fireEvent.change(amount, { target: { value: '12.75' } })
  expect(amount).toBeValid()
  expect(change).toHaveBeenLastCalledWith('amount', { numberValue: 12.75 })
})
