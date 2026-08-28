import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { RequesterSearchField } from './RequesterSearchField'
import type { CustomerSummary } from '../../api/types'

const meta = {
  title: '06 Domain & Workspace/RequesterSearchField',
  component: RequesterSearchField,
  parameters: {
    docs: {
      description: {
        component:
          '상담사 신규 티켓 생성 화면에서 요청자를 기존 고객 검색 또는 새 고객 등록으로 지정하는 필드다. 검색/디바운스는 상위 useRequesterSearch 훅이 소유하고, 이 컴포넌트는 순수하게 props로 렌더링만 한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof RequesterSearchField>

export default meta
type Story = StoryObj<typeof meta>

const customers: CustomerSummary[] = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    name: '김민아',
    email: 'mina.kim@example.test',
    verified: false,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    name: '최민준',
    email: 'minjun.choi@example.test',
    verified: true,
  },
]

const baseArgs = {
  newEmail: '',
  newName: '',
  onNewEmailChange: fn(),
  onNewNameChange: fn(),
  onQueryChange: fn(),
  onSelectCustomer: fn(),
  onTabChange: fn(),
  query: '',
  results: [],
  searchError: null,
  searching: false,
  selectedCustomer: null,
  tab: 'search' as const,
}

export const SearchEmpty: Story = {
  args: baseArgs,
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('tab', { name: '기존 고객 검색' }),
    ).toHaveAttribute('aria-selected', 'true')
    await expect(canvas.getByLabelText('이름 또는 이메일로 검색')).toBeVisible()
  },
}

export const Searching: Story = {
  args: { ...baseArgs, query: '민', searching: true },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toHaveTextContent('검색 중')
  },
}

export const SearchResults: Story = {
  args: { ...baseArgs, query: '민', results: customers },
  play: async ({ args, canvas }) => {
    const options = canvas.getAllByRole('button', { name: /example\.test/ })
    await expect(options).toHaveLength(2)
    await expect(canvas.getByText('인증된 고객')).toBeVisible()
    await userEvent.click(options[0]!)
    await expect(args.onSelectCustomer).toHaveBeenCalledWith(customers[0])
  },
}

export const SearchNoMatches: Story = {
  args: { ...baseArgs, query: '존재하지않음' },
  play: async ({ canvas }) => {
    await expect(canvas.getByText(/일치하는 고객이 없습니다/)).toBeVisible()
  },
}

export const SearchError: Story = {
  args: {
    ...baseArgs,
    query: '민',
    searchError: '고객을 검색하지 못했습니다. 다시 시도해 주세요.',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent(
      '고객을 검색하지 못했습니다',
    )
  },
}

export const Selected: Story = {
  args: { ...baseArgs, selectedCustomer: customers[1]! },
  play: async ({ args, canvas }) => {
    await expect(canvas.getByText('최민준')).toBeVisible()
    await expect(canvas.getByText('인증된 고객')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '다시 검색' }))
    await expect(args.onSelectCustomer).toHaveBeenCalledWith(null)
  },
}

export const NewCustomer: Story = {
  args: { ...baseArgs, tab: 'new' },
  play: async ({ args, canvas }) => {
    await expect(
      canvas.getByRole('tab', { name: '새 고객 등록' }),
    ).toHaveAttribute('aria-selected', 'true')
    await userEvent.type(canvas.getByLabelText('이름'), '박서준')
    await expect(args.onNewNameChange).toHaveBeenCalled()
    await userEvent.type(canvas.getByLabelText('이메일'), 'seojun@example.test')
    await expect(args.onNewEmailChange).toHaveBeenCalled()
  },
}
