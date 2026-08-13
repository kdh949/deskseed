import type { Meta, StoryObj } from '@storybook/react-vite'
import { Notification } from './Feedback'

const meta = {
  title: '03 Components/Notification',
  component: Notification,
  argTypes: {
    tone: {
      control: 'select',
      options: ['info', 'success', 'warning', 'danger', 'conflict'],
    },
  },
  parameters: {
    docs: {
      description: {
        component:
          '현재 화면의 콘텐츠를 유지하면서 중요한 결과나 경고를 알린다. danger와 conflict는 alert로 즉시 전달하고, 나머지 tone은 status로 방해 없이 알린다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof Notification>

export default meta
type Story = StoryObj<typeof meta>

export const Info: Story = {
  args: {
    children: <p>최근 업데이트를 확인했습니다.</p>,
    title: '티켓 정보',
    tone: 'info',
  },
}

export const Success: Story = {
  args: {
    children: <p>서버 값으로 저장되었습니다.</p>,
    title: '변경 사항 저장 완료',
    tone: 'success',
  },
}

export const Warning: Story = {
  args: {
    children: <p>열린 내부 작업 2건을 확인해 주세요.</p>,
    title: '해결 전 확인 사항',
    tone: 'warning',
  },
}

export const Danger: Story = {
  args: {
    children: <p>초안은 보존되었습니다. 다시 시도해 주세요.</p>,
    title: '답변을 보내지 못했습니다',
    tone: 'danger',
  },
}

export const Conflict: Story = {
  args: {
    children: <p>서버 값과 현재 초안을 비교한 뒤 다시 저장해 주세요.</p>,
    title: '최신 변경 사항이 있습니다',
    tone: 'conflict',
  },
}
