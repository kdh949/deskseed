import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { ScreenState } from './Feedback'

const meta = {
  title: '03 Components/ScreenState',
  component: ScreenState,
  argTypes: {
    kind: {
      control: 'select',
      options: [
        'loading',
        'empty',
        'error',
        'denied',
        'not-found',
        'conflict',
        'stale',
      ],
    },
  },
  parameters: {
    docs: {
      description: {
        component:
          '화면 또는 큰 workspace region이 정상 콘텐츠를 표시할 수 없을 때 사용한다. loading, empty, error, denied, not-found, conflict, stale를 서로 다른 복구 의미로 전달한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ScreenState>

export default meta
type Story = StoryObj<typeof meta>

export const Empty: Story = {
  args: {
    description: '현재 조건에 맞는 티켓이 없습니다.',
    kind: 'empty',
    title: '표시할 티켓이 없습니다',
  },
}

export const Loading: Story = {
  args: {
    description: '대화와 속성 정보를 준비하고 있습니다.',
    kind: 'loading',
    title: '티켓을 불러오는 중',
  },
}

export const Error: Story = {
  args: {
    description: '잠시 후 다시 시도해 주세요.',
    kind: 'error',
    requestId: 'req_storybook_101',
    title: '티켓을 불러오지 못했습니다',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('alert')).toHaveAttribute(
      'aria-label',
      '티켓을 불러오지 못했습니다',
    )
  },
}

export const Conflict: Story = {
  args: {
    description: '다른 상담사의 변경 사항을 확인한 뒤 다시 저장해 주세요.',
    kind: 'conflict',
    title: '최신 변경 사항이 있습니다',
  },
}

export const Denied: Story = {
  args: {
    description: '현재 역할에는 이 티켓을 볼 권한이 없습니다.',
    kind: 'denied',
    title: '이 티켓에 접근할 수 없습니다',
  },
}

export const NotFound: Story = {
  args: {
    description: '요청한 프론트엔드 화면은 현재 제공되지 않습니다.',
    kind: 'not-found',
    title: '페이지를 찾을 수 없습니다',
  },
}

export const Stale: Story = {
  args: {
    description: '서버에서 최신 티켓 정보를 다시 확인해 주세요.',
    kind: 'stale',
    title: '표시 중인 정보가 오래되었습니다',
  },
}
