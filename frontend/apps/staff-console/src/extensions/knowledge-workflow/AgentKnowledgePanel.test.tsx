import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { AgentKnowledgePanel } from './AgentKnowledgePanel'
import { readKnowledge, searchKnowledge } from './api'
import type * as KnowledgeApi from './api'
import { article, revision } from './fixtures'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof KnowledgeApi>()),
  readKnowledge: vi.fn(),
  searchKnowledge: vi.fn(),
}))

const hits = Array.from({ length: 20 }, (_, index) => ({
  articleSlug: `article-${index + 1}`,
  title: `검색 문서 ${index + 1}`,
  excerpt: '선택할 수 있는 문서입니다.',
  audience: 'PUBLIC' as const,
  categoryTitle: '결제',
  sectionTitle: '환불',
}))
const published = {
  ...article,
  slug: 'article-20',
  lifecycle: 'PUBLISHED' as const,
  currentPublishedRevision: { ...revision, title: '검색 문서 20' },
}

describe('AgentKnowledgePanel reading navigation', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(searchKnowledge).mockResolvedValue({
      items: hits,
      nextCursor: 'page-2',
    })
    vi.mocked(readKnowledge).mockResolvedValue(published)
  })

  it('switches to reading while loading and restores the selected result, edited query, and applied cursor', async () => {
    let finishRead!: (value: typeof published) => void
    vi.mocked(readKnowledge).mockReturnValue(
      new Promise((resolve) => {
        finishRead = resolve
      }),
    )
    render(<AgentKnowledgePanel />)
    fireEvent.change(screen.getByLabelText(/지식 검색어/), {
      target: { value: '환불' },
    })
    fireEvent.click(screen.getByRole('button', { name: '지식 검색' }))
    const selected = await screen.findByRole('button', {
      name: '읽기: 검색 문서 20',
    })
    fireEvent.change(screen.getByLabelText(/지식 검색어/), {
      target: { value: '입력 중인 다음 검색어' },
    })
    fireEvent.click(selected)
    expect(
      screen.queryByRole('form', { name: '지식 문서 검색' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '읽기: 검색 문서 1' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(
      '문서를 확인하고 있습니다.',
    )
    await act(async () => {
      finishRead(published)
    })
    expect(screen.getByRole('heading', { name: '검색 문서 20' })).toHaveFocus()
    fireEvent.click(
      screen.getByRole('button', { name: '검색 결과로 돌아가기' }),
    )
    expect(
      screen.getByRole('button', { name: '읽기: 검색 문서 20' }),
    ).toHaveFocus()
    expect(screen.getByLabelText(/지식 검색어/)).toHaveValue(
      '입력 중인 다음 검색어',
    )
    expect(searchKnowledge).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '다음 검색 결과' }))
    await waitFor(() =>
      expect(searchKnowledge).toHaveBeenLastCalledWith('환불', 'page-2'),
    )
  })

  it('returns from a denied read without replacing or rerunning the search', async () => {
    vi.mocked(readKnowledge).mockRejectedValue(new ApiError('denied', 403))
    render(<AgentKnowledgePanel />)
    fireEvent.change(screen.getByLabelText(/지식 검색어/), {
      target: { value: '환불' },
    })
    fireEvent.click(screen.getByRole('button', { name: '지식 검색' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '읽기: 검색 문서 20' }),
    )
    expect(
      await screen.findByText('문서 접근 권한을 확인하세요.'),
    ).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: '검색 문서 20' }),
    ).not.toBeInTheDocument()
    fireEvent.click(
      screen.getByRole('button', { name: '검색 결과로 돌아가기' }),
    )
    expect(
      screen.getByRole('button', { name: '읽기: 검색 문서 20' }),
    ).toHaveFocus()
    expect(searchKnowledge).toHaveBeenCalledTimes(1)
  })

  it('opens a direct article link in reading mode and offers a focused search return', async () => {
    render(<AgentKnowledgePanel initialSlug="article-20" />)
    expect(
      await screen.findByRole('heading', { name: '검색 문서 20' }),
    ).toHaveFocus()
    fireEvent.click(
      screen.getByRole('button', { name: '검색 결과로 돌아가기' }),
    )
    expect(screen.getByRole('form', { name: '지식 문서 검색' })).toHaveFocus()
    expect(screen.getByLabelText(/지식 검색어/)).toHaveValue('')
    expect(searchKnowledge).not.toHaveBeenCalled()
  })
})
