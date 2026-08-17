import { useCallback, useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import type { AttachmentUpload } from '../../api/types'
import {
  AttachmentUploadField,
  type AttachmentDraftState,
} from './AttachmentUploadField'

describe('AttachmentUploadField', () => {
  it('blocks submission until the server returns a CLEAN handle', async () => {
    const user = userEvent.setup()
    let resolveUpload!: (upload: AttachmentUpload) => void
    const upload = vi.fn(
      () =>
        new Promise<AttachmentUpload>((resolve) => {
          resolveUpload = resolve
        }),
    )
    render(<Harness upload={upload} />)

    await user.upload(
      screen.getByLabelText('첨부 파일'),
      new File(['safe'], 'safe.png', { type: 'image/png' }),
    )
    expect(screen.getByText(/업로드 및 악성 파일 검사 중/)).toBeVisible()
    expect(screen.getByTestId('blocked-state')).toHaveTextContent('blocked')

    resolveUpload(cleanUpload)
    await waitFor(() =>
      expect(screen.getByTestId('blocked-state')).toHaveTextContent(
        cleanUpload.id,
      ),
    )
    expect(screen.getByText(/CLEAN/)).toBeVisible()
  })

  it('keeps an infected or quarantined response rejected', async () => {
    const user = userEvent.setup()
    render(
      <Harness
        upload={async () => {
          throw new ApiError('attachment-rejected', 422)
        }}
      />,
    )
    await user.upload(
      screen.getByLabelText('첨부 파일'),
      new File(['unsafe'], 'unsafe.exe'),
    )
    expect(await screen.findByText(/감염 또는 격리됨/)).toBeVisible()
    expect(screen.getByTestId('blocked-state')).toHaveTextContent('blocked')
  })
})

function Harness({
  upload,
}: {
  upload: (file: File) => Promise<AttachmentUpload>
}) {
  const [state, setState] = useState<AttachmentDraftState>({
    blocked: false,
    ids: [],
  })
  const onStateChange = useCallback(
    (nextState: AttachmentDraftState) => setState(nextState),
    [],
  )
  return (
    <>
      <AttachmentUploadField onStateChange={onStateChange} upload={upload} />
      <output data-testid="blocked-state">
        {state.blocked ? 'blocked' : state.ids.join(',')}
      </output>
    </>
  )
}

const cleanUpload: AttachmentUpload = {
  id: '11111111-1111-4111-8111-111111111111',
  fileName: 'safe.png',
  sizeBytes: 4,
  contentType: 'image/png',
  scanStatus: 'CLEAN',
  expiresAt: '2099-08-17T05:00:00Z',
}
