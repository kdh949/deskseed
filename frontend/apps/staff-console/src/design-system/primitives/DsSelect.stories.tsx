import type { Meta, StoryObj } from '@storybook/react-vite'
import { DsSelect } from './DeskseedControls'

const meta = {
  title: '02 Primitives/DsSelect',
  component: DsSelect,
  parameters: {
    docs: {
      description: {
        component:
          '작은 고정 option set에서 하나를 선택할 때 사용하는 native select다. visible label은 DsPropertyField 또는 명시적 label로 제공하고, searchable combobox가 필요한 경우 이 contract를 억지로 확장하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsSelect>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    'aria-label': '티켓 상태',
    children: (
      <>
        <option>신규</option>
        <option>처리 중</option>
        <option>고객 답변 대기</option>
        <option>해결</option>
      </>
    ),
    defaultValue: '처리 중',
  },
}

export const Disabled: Story = {
  args: {
    'aria-label': '읽기 전용 티켓 상태',
    children: <option>처리 중</option>,
    disabled: true,
  },
}

export const LongOption: Story = {
  args: {
    'aria-label': '긴 그룹 이름',
    children: <option>글로벌 엔터프라이즈 결제 및 정산 운영 지원 그룹</option>,
  },
}
