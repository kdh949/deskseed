import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { DsButton } from './DeskseedControls'

const meta = {
  component: DsButton,
  tags: ['ai-generated'],
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
