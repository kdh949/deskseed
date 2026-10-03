import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { DsButton } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsButton',
  component: DsButton,
  argTypes: {
    tone: { control: 'select', options: ['primary', 'secondary', 'ghost'] },
    type: {
      control: 'select',
      options: ['button', 'submit', 'reset'],
      description: '기본값 button. 폼 제출은 submit을 명시합니다.',
    },
    onClick: {
      description:
        '네이티브 버튼 클릭 핸들러. 폼 제출은 상위 form의 onSubmit에서 처리합니다.',
    },
  },
  parameters: {
    docs: {
      description: {
        component:
          '현재 view의 행동을 실행하는 기본 button이다. primary는 화면의 한 가지 주 행동에만 사용하고, 보조 행동은 secondary 또는 ghost를 선택한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsButton>

export default meta
type Story = StoryObj<typeof meta>

export const Primary: Story = {
  args: {
    children: '변경 사항 저장',
    tone: 'primary',
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '변경 사항 저장' }),
    ).toHaveAttribute('type', 'button')
  },
}

export const Secondary: Story = {
  args: {
    children: '취소',
    tone: 'secondary',
  },
}

export const Disabled: Story = {
  args: {
    children: '저장 중',
    disabled: true,
    tone: 'primary',
  },
}

export const Ghost: Story = {
  args: {
    children: '새로고침',
    tone: 'ghost',
  },
}

export const LongLabel: Story = {
  args: {
    children: '변경 사항을 저장하고 티켓으로 돌아가기',
    tone: 'primary',
  },
}

export const CssCheck: Story = {
  args: {
    children: '스타일 확인',
    tone: 'primary',
  },
  play: async ({ canvas }) => {
    const button = canvas.getByRole('button', { name: '스타일 확인' })
    await expect(getComputedStyle(button).backgroundColor).toBe(
      'rgb(14, 113, 117)',
    )
  },
}

/** 검색 실행처럼 폼을 제출할 때 type을 명시하고 보조 행동은 별도 클릭으로 처리합니다. */
export const FormActions: Story = {
  args: { onClick: fn() },
  render: (args) => (
    <form onSubmit={(event) => event.preventDefault()}>
      <DsButton type="submit" tone="primary">
        검색
      </DsButton>
      <DsButton type="button" tone="secondary" onClick={args.onClick}>
        초기화
      </DsButton>
    </form>
  ),
  play: async ({ canvas, args }) => {
    await expect(canvas.getByRole('button', { name: '검색' })).toHaveAttribute(
      'type',
      'submit',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초기화' }))
    await expect(args.onClick).toHaveBeenCalledOnce()
  },
}
