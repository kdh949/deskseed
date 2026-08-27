import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { TicketWorkspace } from './TicketWorkspace'

describe('TicketWorkspace', () => {
  const renderWorkspace = (initialState?: 'conflict') =>
    render(
      <MemoryRouter initialEntries={['/agent/tickets/1042']}>
        <TicketWorkspace initialState={initialState} />
      </MemoryRouter>,
    )

  it('keeps public and internal drafts separate while the agent switches composer modes', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await user.click(
      screen.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    const composer = screen.getByRole('textbox', { name: '공개 답변 내용' })
    await user.type(composer, '고객에게 보낼 답변입니다.')

    await user.click(
      screen.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    const internalComposer = screen.getByRole('textbox', {
      name: '내부 메모 내용',
    })
    await user.type(internalComposer, '팀 내부 확인이 필요합니다.')

    await user.click(
      screen.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    expect(screen.getByRole('textbox', { name: '공개 답변 내용' })).toHaveValue(
      '고객에게 보낼 답변입니다.',
    )

    await user.click(
      screen.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    expect(screen.getByRole('textbox', { name: '내부 메모 내용' })).toHaveValue(
      '카드사 승인 로그와 게이트웨이 응답 코드 확인 필요.\n현재 PG사(BluePay) 측 간헐적 오류 이력 있음.\n지연님께는 진행 상황 공유 예정.팀 내부 확인이 필요합니다.',
    )
  })

  it('leaves ticket-tab navigation to the canonical AgentShell', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await user.click(
      screen.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '1042번 티켓의 공개 답변 초안',
    )
    expect(
      screen.queryByRole('navigation', { name: '열린 티켓 탭' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '공개 답변 내용' })).toHaveValue(
      '1042번 티켓의 공개 답변 초안',
    )
  })

  it('explains and resolves a property conflict without discarding the composer', async () => {
    const user = userEvent.setup()
    renderWorkspace('conflict')

    expect(
      screen.getByRole('region', { name: '담당자 저장 충돌' }),
    ).toBeVisible()
    await user.click(screen.getByRole('button', { name: '서버 값 적용' }))
    expect(
      screen.queryByRole('region', { name: '담당자 저장 충돌' }),
    ).not.toBeInTheDocument()
  })

  it('supports roving keyboard focus and explicit tab panels', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    const internalTab = screen.getByRole('tab', {
      name: '내부 메모 작성 모드로 전환',
    })
    internalTab.focus()
    await user.keyboard('{ArrowRight}')

    const publicTab = screen.getByRole('tab', {
      name: '공개 답변 작성 모드로 전환',
    })
    expect(publicTab).toHaveFocus()
    expect(publicTab).toHaveAttribute('aria-selected', 'true')
    expect(
      screen.getByRole('tabpanel', { name: '공개 답변 작성 모드로 전환' }),
    ).toBeVisible()

    await user.keyboard('{End}')
    expect(publicTab).toHaveFocus()
    await user.keyboard('{Home}')
    expect(internalTab).toHaveFocus()
    expect(internalTab).toHaveAttribute('aria-selected', 'true')
  })
})
