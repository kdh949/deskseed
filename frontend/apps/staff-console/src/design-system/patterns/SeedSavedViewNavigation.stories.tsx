import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { SeedButton } from '../primitives/SeedCore'
import { SeedFeedbackState, SeedSkeletonRows } from '../components/SeedSurfaces'
import { SeedSavedViewNavigation } from './SeedWorkspace'

const meta = {
  title: '04 Patterns/SeedSavedViewNavigation',
  component: SeedSavedViewNavigation,
  parameters: {
    docs: {
      description: {
        component:
          '저장 보기 목록 탐색입니다. 조회 상태는 page가 소유하며 선택적인 feedback 슬롯으로 loading/error/denied/실제 empty를 표시합니다. feedback이 있으면 보기 이름 검색과 목록을 대체합니다. 정상 목록의 검색 결과 없음과 서버의 빈 목록을 구분하고, 정상 응답으로 복구되면 feedback을 해제합니다.',
      },
    },
  },
  args: {
    activeKey: 'my-open',
    sections: [
      {
        id: 'shared',
        label: '공유 보기',
        items: [
          {
            key: 'my-open',
            label: '내 처리 중 티켓',
            to: '/agent/views/my-open',
            count: 2,
          },
        ],
      },
    ],
    onCreate: fn(),
    onEdit: fn(),
  },
  tags: ['autodocs'],
} satisfies Meta<typeof SeedSavedViewNavigation>

export default meta
type Story = StoryObj<typeof meta>

export const Ready: Story = {
  play: async ({ canvas, userEvent, args }) => {
    await expect(
      canvas.getByRole('link', { name: /내 처리 중 티켓/ }),
    ).toBeVisible()
    await userEvent.type(
      canvas.getByRole('searchbox', { name: '보기 검색' }),
      '없는 보기',
    )
    await expect(canvas.getByText('일치하는 보기가 없습니다.')).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: '새 보기 만들기' }),
    )
    await expect(args.onCreate).toHaveBeenCalledOnce()
  },
}

export const Loading: Story = {
  args: {
    feedback: <SeedSkeletonRows label="보기 목록 불러오는 중" rows={3} />,
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByLabelText('보기 목록 불러오는 중')).toBeVisible()
    await expect(canvas.queryByRole('searchbox')).not.toBeInTheDocument()
  },
}

export const Unavailable: Story = {
  args: {
    feedback: (
      <SeedFeedbackState
        kind="error"
        title="보기 목록을 불러오지 못했습니다"
        description="요청 ID: example-request"
        action={<SeedButton>보기 목록 다시 시도</SeedButton>}
      />
    ),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('보기 목록을 불러오지 못했습니다'),
    ).toBeVisible()
    await expect(
      canvas.queryByText('일치하는 보기가 없습니다.'),
    ).not.toBeInTheDocument()
  },
}
