import { useRef, useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import '../foundations/seed-story-helpers.css'
import { SeedButton } from '../primitives/SeedCore'
import {
  SeedContextCard,
  SeedDrawer,
  SeedFeedbackState,
  SeedNotice,
  SeedSkeletonRows,
  SeedSlaMeter,
  SeedStatusBadge,
} from './SeedSurfaces'

function DrawerCatalog() {
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
}

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

export const StatusSlaNoticeContext: Story = {}
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
  render: () => <DrawerCatalog />,
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
