import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import '../foundations/seed-story-helpers.css'
import {
  SeedAvatar,
  SeedBrandLockup,
  SeedButton,
  SeedCheckbox,
  SeedChoiceField,
  SeedIcon,
  SeedIconButton,
  SeedReadOnlyField,
  SeedSelectField,
  SeedTabs,
  SeedTextAreaField,
  SeedTextField,
  type SeedIconName,
} from './SeedCore'

function PrimitiveCatalog() {
  const icons: SeedIconName[] = [
    'home',
    'ticket',
    'search',
    'plus',
    'filter',
    'bookmark',
    'clock',
    'users',
    'mail',
    'lock',
    'alert',
    'priority',
    'copy',
    'settings',
  ]
  return (
    <div className="seed-story-stack">
      <SeedBrandLockup />
      <div className="seed-story-row">
        <SeedAvatar initials="JW" label="Jennifer Ward" />
        <SeedButton variant="primary">저장</SeedButton>
        <SeedButton>필터</SeedButton>
        <SeedButton variant="quiet">취소</SeedButton>
        <SeedIconButton icon="more" label="추가 옵션" />
      </div>
      <div className="seed-story-row">
        {icons.map((icon) => (
          <span
            aria-label={icon}
            className="seed-story-icon"
            key={icon}
            role="img"
          >
            <SeedIcon name={icon} />
          </span>
        ))}
      </div>
    </div>
  )
}

function FormCatalog() {
  const [status, setStatus] = useState('OPEN')
  const [checked, setChecked] = useState(false)
  return (
    <form className="seed-story-form">
      <SeedTextField label="제목" placeholder="문의 제목" required />
      <SeedTextField
        error="이메일을 확인해 주세요."
        label="이메일"
        leadingIcon="mail"
        value="invalid"
        readOnly
      />
      <SeedSelectField
        label="상태"
        onChange={(event) => setStatus(event.target.value)}
        value={status}
      >
        <option value="OPEN">처리 중</option>
        <option value="PENDING">고객 답변 대기</option>
      </SeedSelectField>
      <SeedTextAreaField
        hint="고객에게 공개되는 첫 댓글입니다."
        label="문의 내용"
      />
      <SeedCheckbox
        checked={checked}
        label="로그인 상태 유지"
        onChange={(event) => setChecked(event.target.checked)}
      />
    </form>
  )
}

function TabsCatalog() {
  const [active, setActive] = useState<'PUBLIC' | 'INTERNAL'>('PUBLIC')
  return (
    <SeedTabs
      active={active}
      ariaLabel="답변 모드"
      items={[
        { id: 'PUBLIC', label: 'PUBLIC 답변' },
        { id: 'INTERNAL', label: 'INTERNAL 메모' },
      ]}
      onChange={setActive}
    />
  )
}

function WorkspaceFieldCatalog() {
  const [status, setStatus] = useState<'OPEN' | 'PENDING'>('OPEN')
  const [group, setGroup] = useState<'support' | 'billing' | null>('support')
  const [assignee, setAssignee] = useState<'alex' | 'sam' | null>('alex')
  return (
    <div className="seed-story-form">
      <SeedChoiceField
        label="상태"
        onChange={setStatus}
        options={[
          {
            value: 'OPEN',
            label: '처리 중',
            startAdornment: (
              <span
                aria-hidden="true"
                className="seed-status-dot seed-status-dot--positive"
              />
            ),
          },
          {
            value: 'PENDING',
            label: '고객 답변 대기',
            startAdornment: (
              <span
                aria-hidden="true"
                className="seed-status-dot seed-status-dot--warning"
              />
            ),
          },
        ]}
        value={status}
      />
      <SeedChoiceField
        clearLabel="그룹 배정 해제"
        label="그룹"
        onChange={setGroup}
        onClear={() => setGroup(null)}
        options={[
          {
            value: 'support',
            label: '고객 지원',
            startAdornment: <SeedIcon name="users" size="small" />,
          },
          {
            value: 'billing',
            label: '결제 지원',
            startAdornment: <SeedIcon name="users" size="small" />,
          },
        ]}
        value={group}
      />
      <SeedChoiceField
        clearLabel="담당자 배정 해제"
        label="담당자"
        onChange={setAssignee}
        onClear={() => setAssignee(null)}
        options={[
          {
            value: 'alex',
            label: 'Alex Rivera',
            startAdornment: (
              <SeedAvatar initials="AR" label="Alex Rivera" size="small" />
            ),
          },
          {
            value: 'sam',
            label: 'Sam Lee',
            startAdornment: (
              <SeedAvatar initials="SL" label="Sam Lee" size="small" />
            ),
          },
        ]}
        value={assignee}
      />
      <SeedReadOnlyField
        label="생성"
        leadingIcon="calendar"
        value="2026. 8. 29. 오전 9:41"
      />
      <div className="seed-story-row">
        <SeedButton size="compact">최신본 적용</SeedButton>
        <SeedButton size="compact">비교</SeedButton>
        <SeedButton size="compact" variant="primary">
          내 초안 유지
        </SeedButton>
      </div>
    </div>
  )
}

const meta = {
  title: '02 Primitives/Seed Core',
  component: PrimitiveCatalog,
  parameters: { layout: 'padded' },
  tags: ['autodocs'],
} satisfies Meta<typeof PrimitiveCatalog>

export default meta
type Story = StoryObj<typeof meta>

export const ActionsIconsBrand: Story = {}

export const FormControls: Story = { render: () => <FormCatalog /> }

export const WorkspaceChoiceAndDateFields: Story = {
  render: () => <WorkspaceFieldCatalog />,
  play: async ({ canvas }) => {
    const status = canvas.getByRole('combobox', { name: '상태' })
    status.focus()
    await userEvent.keyboard('{Enter}{ArrowDown}{Enter}')
    await expect(status).toHaveTextContent('고객 답변 대기')
    await userEvent.click(
      canvas.getByRole('button', { name: '그룹 배정 해제' }),
    )
    await expect(
      canvas.getByRole('combobox', { name: '그룹' }),
    ).toHaveTextContent('선택')
  },
}

export const KeyboardTabs: Story = {
  render: () => <TabsCatalog />,
  play: async ({ canvas }) => {
    const publicTab = canvas.getByRole('tab', { name: 'PUBLIC 답변' })
    publicTab.focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(
      canvas.getByRole('tab', { name: 'INTERNAL 메모' }),
    ).toHaveAttribute('aria-selected', 'true')
  },
}
