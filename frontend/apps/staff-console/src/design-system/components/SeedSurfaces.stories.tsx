import { useRef, useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import '../foundations/seed-story-helpers.css'
import { SeedButton, SeedTextField } from '../primitives/SeedCore'
import {
  SeedContextCard,
  SeedDrawer,
  SeedFeedbackState,
  SeedNotice,
  SeedSkeletonRows,
  SeedSlaMeter,
  SeedStatusBadge,
} from './SeedSurfaces'

function SurfaceCatalog() {
  return (
    <div className="seed-story-stack">
      <div className="seed-story-row">
        <SeedStatusBadge tone="positive">처리 중</SeedStatusBadge>
        <SeedStatusBadge tone="warning">고객 답변 대기</SeedStatusBadge>
        <SeedStatusBadge tone="danger">SLA 위반</SeedStatusBadge>
      </div>
      <SeedSlaMeter
        detail="2시간 14분 남음"
        label="최초 답변 SLA"
        percent={48}
      />
      <SeedNotice
        title="저장 충돌"
        tone="warning"
        action={<SeedButton>최신 내용 비교</SeedButton>}
      >
        다른 탭에서 같은 필드가 변경되었습니다.
      </SeedNotice>
      <SeedContextCard title="고객">
        <strong>Jennifer Ward</strong>
        <p>jennifer.ward@example.com</p>
      </SeedContextCard>
    </div>
  )
}

const meta = {
  title: '03 Components/Seed Surfaces',
  component: SurfaceCatalog,
  parameters: { layout: 'padded' },
  tags: ['autodocs'],
} satisfies Meta<typeof SurfaceCatalog>

export default meta
type Story = StoryObj<typeof meta>

export const StatusSlaNoticeContext: Story = {
  render: function SurfaceCatalog() {
    return (
      <div className="seed-story-stack">
        <div className="seed-story-row">
          <SeedStatusBadge tone="positive">처리 중</SeedStatusBadge>
          <SeedStatusBadge tone="warning">고객 답변 대기</SeedStatusBadge>
          <SeedStatusBadge tone="danger">SLA 위반</SeedStatusBadge>
        </div>
        <SeedSlaMeter
          detail="2시간 14분 남음"
          label="최초 답변 SLA"
          percent={48}
        />
        <SeedNotice
          title="저장 충돌"
          tone="warning"
          action={<SeedButton>최신 내용 비교</SeedButton>}
        >
          다른 탭에서 같은 필드가 변경되었습니다.
        </SeedNotice>
        <SeedContextCard title="고객">
          <strong>Jennifer Ward</strong>
          <p>jennifer.ward@example.com</p>
        </SeedContextCard>
      </div>
    )
  },
}
export const Loading: Story = { render: () => <SeedSkeletonRows /> }
export const Empty: Story = {
  render: () => (
    <SeedFeedbackState
      kind="empty"
      title="표시할 티켓이 없습니다."
      description="필터를 바꾸거나 다른 저장 보기를 선택하세요."
    />
  ),
}
export const Error: Story = {
  render: () => (
    <SeedFeedbackState
      action={<SeedButton>다시 시도</SeedButton>}
      kind="error"
      title="티켓을 불러오지 못했습니다."
    />
  ),
}
export const Denied: Story = {
  render: () => (
    <SeedFeedbackState kind="denied" title="이 화면에 접근할 수 없습니다." />
  ),
}
export const Conflict: Story = {
  render: () => (
    <SeedFeedbackState
      action={<SeedButton>최신 내용 비교</SeedButton>}
      kind="conflict"
      title="저장 충돌을 확인하세요."
    />
  ),
}
export const Drawer: Story = {
  render: function DrawerCatalog() {
    const [open, setOpen] = useState(false)
    const triggerRef = useRef<HTMLButtonElement>(null)
    return (
      <div className="seed-story-stack">
        <SeedButton onClick={() => setOpen(true)} ref={triggerRef}>
          컨텍스트 열기
        </SeedButton>
        <SeedDrawer
          description="고객, 관련 티켓과 외부 참조를 확인합니다."
          onClose={() => setOpen(false)}
          open={open}
          returnFocusRef={triggerRef}
          title="티켓 컨텍스트"
        >
          <SeedContextCard title="고객">
            <strong>Jennifer Ward</strong>
          </SeedContextCard>
        </SeedDrawer>
      </div>
    )
  },
  play: async ({ canvas }) => {
    const trigger = canvas.getByRole('button', { name: '컨텍스트 열기' })
    await userEvent.click(trigger)
    await expect(
      canvas.getByRole('dialog', { name: '티켓 컨텍스트' }),
    ).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await expect(trigger).toHaveFocus()
  },
}

export const DrawerPreservesInputFocus: Story = {
  render: function InputDrawer() {
    const [open, setOpen] = useState(false)
    const [value, setValue] = useState('')
    return (
      <>
        <SeedButton onClick={() => setOpen(true)}>편집 열기</SeedButton>
        <SeedDrawer
          open={open}
          onClose={() => setOpen(false)}
          title="입력 유지"
        >
          <SeedTextField
            label="이관 사유"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </SeedDrawer>
      </>
    )
  },
  play: async ({ canvas }) => {
    const trigger = canvas.getByRole('button', { name: '편집 열기' })
    await userEvent.click(trigger)
    const input = canvas.getByLabelText('이관 사유')
    await userEvent.type(input, '고객 요청으로 담당 변경')
    await expect(input).toHaveValue('고객 요청으로 담당 변경')
    await expect(input).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    await expect(trigger).toHaveFocus()
  },
}

export const NestedDrawerKeyboard: Story = {
  render: function NestedDrawer() {
    const [outer, setOuter] = useState(false)
    const [inner, setInner] = useState(false)
    return (
      <>
        <SeedButton onClick={() => setOuter(true)}>컨텍스트 보기</SeedButton>
        <SeedDrawer
          open={outer}
          onClose={() => setOuter(false)}
          title="티켓 컨텍스트"
        >
          <SeedButton onClick={() => setInner(true)}>이관 시작</SeedButton>
          <SeedDrawer
            open={inner}
            onClose={() => setInner(false)}
            title="이관 입력"
          >
            <SeedTextField label="사유" />
          </SeedDrawer>
        </SeedDrawer>
      </>
    )
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '컨텍스트 보기' }))
    const trigger = canvas.getByRole('button', { name: '이관 시작' })
    await userEvent.click(trigger)
    await userEvent.type(canvas.getByLabelText('사유'), '확인')
    await userEvent.keyboard('{Escape}')
    await expect(
      canvas.queryByRole('dialog', { name: '이관 입력' }),
    ).not.toBeInTheDocument()
    await expect(
      canvas.getByRole('dialog', { name: '티켓 컨텍스트' }),
    ).toBeVisible()
    await expect(trigger).toHaveFocus()
  },
}
