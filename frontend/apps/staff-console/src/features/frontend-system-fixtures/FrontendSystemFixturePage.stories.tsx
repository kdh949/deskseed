import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import type { Decorator, Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { DeskseedIcon } from '../../design-system'
import { FrontendSystemFixturePage } from './FrontendSystemFixturePage'
import './FrontendSystemFixturePage.stories.css'

type FixtureArgs = { fixtureName?: string }

function StorybookAgentGlobalSearch({ children }: { children: ReactNode }) {
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [chrome, setChrome] = useState<HTMLElement | null>(null)
  const [query, setQuery] = useState('')

  useLayoutEffect(() => {
    setChrome(
      rootRef.current?.querySelector<HTMLElement>('.agent-top-chrome') ?? null,
    )
  }, [])

  useEffect(() => {
    const focusGlobalSearch = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        inputRef.current?.focus()
      }
    }

    window.addEventListener('keydown', focusGlobalSearch)
    return () => window.removeEventListener('keydown', focusGlobalSearch)
  }, [])

  return (
    <div className="storybook-agent-screen" ref={rootRef}>
      {children}
      {chrome
        ? createPortal(
            <label className="agent-queue-search-control storybook-agent-global-search">
              <DeskseedIcon name="search" size="md" />
              <input
                aria-label="전역 검색"
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search Deskseed"
                ref={inputRef}
                type="search"
                value={query}
              />
              <kbd
                aria-hidden="true"
                className="storybook-agent-global-search-shortcut"
              >
                ⌘ K
              </kbd>
            </label>,
            chrome,
          )
        : null}
    </div>
  )
}

const withGlobalSearch: Decorator<FixtureArgs> = (Story) => (
  <StorybookAgentGlobalSearch>
    <Story />
  </StorybookAgentGlobalSearch>
)

const meta = {
  title: '07 Screens/Agent Queue & Ticket Workspace',
  component: FrontendSystemFixturePage,
  decorators: [withGlobalSearch],
  parameters: {
    docs: {
      description: {
        component:
          '현재 shipped surface인 Agent Queue와 read-only Ticket Workspace를 production component와 deterministic fixture로 조합한다. 상단 전역 검색은 screen-level visual fixture이며 search API 제공을 의미하지 않는다. page-level visual baseline은 Playwright가 별도로 소유한다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<FixtureArgs>

export default meta
type Story = StoryObj<typeof meta>

export const Queue: Story = {
  args: { fixtureName: 'view-queue' },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('heading', { name: '내 티켓' })).toBeVisible()
    const globalSearch = canvas.getByRole('searchbox', { name: '전역 검색' })
    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'k', metaKey: true }),
    )
    await expect(globalSearch).toHaveFocus()
    await userEvent.type(globalSearch, '결제 오류')
    await expect(globalSearch).toHaveValue('결제 오류')
    await userEvent.clear(globalSearch)
    await userEvent.click(canvas.getByRole('button', { name: '필터 열기' }))
    await expect(
      canvas.getByRole('region', { name: '내 티켓 필터' }),
    ).toBeVisible()
  },
}

export const QueueLoading: Story = {
  args: { fixtureName: 'view-queue-loading' },
}

export const QueueEmpty: Story = {
  args: { fixtureName: 'view-queue-empty' },
}

export const QueueError: Story = {
  args: { fixtureName: 'view-queue-error' },
}

export const QueueDenied: Story = {
  args: { fixtureName: 'view-queue-denied' },
}

export const Workspace: Story = {
  args: { fixtureName: 'workspace' },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await userEvent.type(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
      '고객에게 보낼 공개 초안',
    )
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '내부 메모 내용' }),
    ).not.toHaveValue('고객에게 보낼 공개 초안')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toHaveValue('고객에게 보낼 공개 초안')
  },
}

export const WorkspaceConflict: Story = {
  args: { fixtureName: 'workspace-conflict' },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('region', { name: '담당자 저장 충돌' }),
    ).toBeVisible()
  },
}

export const WorkspaceLoading: Story = {
  args: { fixtureName: 'workspace-loading' },
}

export const WorkspaceEmpty: Story = {
  args: { fixtureName: 'workspace-empty' },
}

export const WorkspaceError: Story = {
  args: { fixtureName: 'workspace-error' },
}

export const WorkspaceDenied: Story = {
  args: { fixtureName: 'workspace-denied' },
}
