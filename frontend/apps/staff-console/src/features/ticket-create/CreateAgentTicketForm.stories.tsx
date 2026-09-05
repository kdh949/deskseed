import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { CreateAgentTicketForm } from './CreateAgentTicketForm'
import type {
  CustomerSummary,
  TicketAssignmentGroupOption,
} from '../../api/types'
import type { useRequesterSearch } from './model/useRequesterSearch'

const meta = {
  title: '06 Domain & Workspace/CreateAgentTicketForm',
  component: CreateAgentTicketForm,
  parameters: {
    docs: {
      description: {
        component:
          'AGT-005 상담사 신규 티켓 생성 폼. 데이터 패칭은 상위 CreateAgentTicketPage 컨테이너가 소유하고, 이 컴포넌트는 assignment options/요청자 검색 상태/제출 상태를 props로 받아 렌더링만 한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CreateAgentTicketForm>

export default meta
type Story = StoryObj<typeof meta>

const groups: TicketAssignmentGroupOption[] = [
  {
    id: 'group-billing',
    name: '결제 지원팀',
    members: [
      { id: 'staff-1', displayName: '이서연' },
      { id: 'staff-2', displayName: '박도윤' },
    ],
  },
  {
    id: 'group-tech',
    name: '기술 지원팀',
    members: [{ id: 'staff-3', displayName: '최지우' }],
  },
]

const existingCustomer: CustomerSummary = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '김민아',
  email: 'mina.kim@example.test',
  verified: true,
}

function makeRequesterSearch(
  overrides: Partial<ReturnType<typeof useRequesterSearch>> = {},
): ReturnType<typeof useRequesterSearch> {
  return {
    tab: 'search',
    setTab: fn(),
    query: '',
    setQuery: fn(),
    results: [],
    searching: false,
    searchError: null,
    selectedCustomer: null,
    setSelectedCustomer: fn(),
    newName: '',
    setNewName: fn(),
    newEmail: '',
    setNewEmail: fn(),
    selection: null,
    ...overrides,
  }
}

const baseArgs = {
  assignmentOptions: { status: 'ready', groups } as const,
  onRetryOptions: fn(),
  submitting: false,
  error: null,
  warnings: [],
  onSubmit: fn(),
}

export const Empty: Story = {
  args: { ...baseArgs, requesterSearch: makeRequesterSearch() },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('heading', { name: '새 티켓' })).toBeVisible()
    await expect(canvas.getByLabelText('새 티켓 속성')).toBeVisible()
    await expect(canvas.getByLabelText('티켓 내용')).toBeVisible()
    await expect(canvas.queryByText('NEW REQUEST')).not.toBeInTheDocument()
    await expect(canvas.queryByText('티켓 생성 순서')).not.toBeInTheDocument()
    await expect(
      canvas.getByRole('tab', { name: '내부 메모' }),
    ).toHaveAttribute('aria-selected', 'true')
    await expect(canvas.getByLabelText('담당자')).toBeDisabled()
  },
}

export const RequesterSelectedWithGroup: Story = {
  args: {
    ...baseArgs,
    requesterSearch: makeRequesterSearch({
      selectedCustomer: existingCustomer,
      selection: { mode: 'existing', customer: existingCustomer },
    }),
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('김민아')).toBeVisible()
    await userEvent.selectOptions(
      canvas.getByLabelText('그룹'),
      'group-billing',
    )
    await expect(canvas.getByLabelText('담당자')).toBeEnabled()
    await expect(
      canvas.getByRole('option', { name: '이서연' }),
    ).toBeInTheDocument()
    await expect(
      canvas.queryByRole('option', { name: '최지우' }),
    ).not.toBeInTheDocument()
  },
}

export const NewCustomer: Story = {
  args: {
    ...baseArgs,
    requesterSearch: makeRequesterSearch({
      tab: 'new',
      newName: '박서준',
      newEmail: 'seojun@example.test',
      selection: {
        mode: 'new',
        name: '박서준',
        email: 'seojun@example.test',
      },
    }),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('tab', { name: '새 고객 등록' }),
    ).toHaveAttribute('aria-selected', 'true')
    await expect(canvas.getByLabelText('이름')).toHaveValue('박서준')
    await expect(canvas.getByLabelText('이메일')).toHaveValue(
      'seojun@example.test',
    )
  },
}

export const ValidationError: Story = {
  args: { ...baseArgs, requesterSearch: makeRequesterSearch() },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '티켓 생성' }))
    await expect(
      canvas.getByText('요청자를 검색해서 선택하거나 새로 등록해 주세요.'),
    ).toBeVisible()
  },
}

export const Submitting: Story = {
  args: {
    ...baseArgs,
    submitting: true,
    requesterSearch: makeRequesterSearch({
      selectedCustomer: existingCustomer,
      selection: { mode: 'existing', customer: existingCustomer },
    }),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '생성 중…' }),
    ).toBeDisabled()
  },
}

export const ServerError: Story = {
  args: {
    ...baseArgs,
    error: {
      message: '동일한 요청이 이미 처리되었습니다.',
      requestId: 'req-c7a1',
    },
    requesterSearch: makeRequesterSearch(),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText(/동일한 요청이 이미 처리되었습니다/),
    ).toBeVisible()
    await expect(canvas.getByText(/req-c7a1/)).toBeVisible()
  },
}

export const Warnings: Story = {
  args: {
    ...baseArgs,
    warnings: [
      {
        code: 'similar-open-tickets',
        message: '동일 고객의 열린 티켓이 2건 있습니다.',
        count: 2,
        relatedTicketNumbers: [1042, 1050],
      },
    ],
    requesterSearch: makeRequesterSearch(),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('동일 고객의 열린 티켓이 2건 있습니다.'),
    ).toBeVisible()
  },
}

export const OptionsLoading: Story = {
  args: {
    ...baseArgs,
    assignmentOptions: { status: 'loading' },
    requesterSearch: makeRequesterSearch(),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('새 티켓 양식을 준비하고 있습니다.'),
    ).toBeVisible()
  },
}

export const OptionsError: Story = {
  args: {
    ...baseArgs,
    assignmentOptions: { status: 'error' },
    requesterSearch: makeRequesterSearch(),
  },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '다시 시도' }))
    await expect(args.onRetryOptions).toHaveBeenCalled()
  },
}
