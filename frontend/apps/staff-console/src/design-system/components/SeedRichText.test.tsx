import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { plainTextDocument } from '../../api/types'
import { SeedRichTextEditor } from './SeedRichText'

describe('SeedRichTextEditor', () => {
  it('projects an attachment-only document to its required alternative text', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(
      <SeedRichTextEditor
        ariaLabel="답변 내용"
        onChange={onChange}
        onUploadImage={async (file) => ({
          attachmentId: '33333333-3333-4333-8333-333333333333',
          alt: file.name,
          previewUrl: '/attachment-preview.png',
        })}
        value={plainTextDocument('')}
      />,
    )
    await screen.findByRole('textbox', { name: '답변 내용' })

    await user.upload(
      screen.getByLabelText('첨부 이미지 파일'),
      new File(['image'], 'error-screen.png', { type: 'image/png' }),
    )

    await waitFor(() => {
      expect(onChange).toHaveBeenLastCalledWith(
        expect.objectContaining({
          content: expect.arrayContaining([
            expect.objectContaining({
              type: 'attachmentImage',
              attrs: expect.objectContaining({ alt: 'error-screen.png' }),
            }),
          ]),
        }),
        'error-screen.png',
      )
    })
  })
})
