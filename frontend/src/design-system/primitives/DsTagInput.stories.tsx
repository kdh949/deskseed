import type { Meta, StoryObj } from '@storybook/react-vite'
import { DsTagInput } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsTagInput',
  component: DsTagInput,
  parameters: {
    docs: {
      description: {
        component:
          '현재 ticket tag 목록을 compact field 안에 표시하는 read-oriented contract다. tag 제거 callback이나 free-text 입력을 지원하지 않으므로 interactive editor로 해석하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsTagInput>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    label: 'Tags',
    tags: ['결제 오류', '카드 결제'],
  },
}

export const ManyTags: Story = {
  args: {
    label: 'Tags',
    tags: ['결제 오류', '카드 결제', '긴급', 'Chrome', 'Windows 11', '구독'],
  },
}

export const LongTag: Story = {
  args: {
    label: 'Tags',
    tags: ['엔터프라이즈 연간 구독 결제 승인 오류'],
  },
}
