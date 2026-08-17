import { useCallback, useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import type { AttachmentUpload } from '../../api/types'
import {
  AttachmentUploadField,
  type AttachmentDraftState,
} from './AttachmentUploadField'

const cleanUpload: AttachmentUpload = {
  id: '11111111-1111-4111-8111-111111111111',
  fileName: 'payment-error.png',
  sizeBytes: 1280,
  contentType: 'image/png',
  scanStatus: 'CLEAN',
  expiresAt: '2099-08-17T05:00:00Z',
}

const meta = {
  title: '06 Domain & Workspace/AttachmentUploadField',
  component: AttachmentUploadField,
  tags: ['autodocs'],
} satisfies Meta<typeof AttachmentUploadField>

export default meta
type Story = StoryObj<typeof meta>

export const UploadAndScanClean: Story = {
  args: {
    onStateChange: () => undefined,
    upload: async () => cleanUpload,
  },
  render: () => <UploadExample />,
  play: async ({ canvas, userEvent }) => {
    const file = new File(['safe-image'], 'payment-error.png', {
      type: 'image/png',
    })
    await userEvent.upload(canvas.getByLabelText('첨부 파일'), file)
    await expect(await canvas.findByText(/^CLEAN/)).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '초안에서 제거' }),
    ).toBeEnabled()
  },
}

function UploadExample() {
  const [state, setState] = useState<AttachmentDraftState>({
    blocked: false,
    ids: [],
    needsNavigationWarning: false,
  })
  const onStateChange = useCallback(
    (nextState: AttachmentDraftState) => setState(nextState),
    [],
  )
  return (
    <div>
      <AttachmentUploadField
        onStateChange={onStateChange}
        upload={async () => cleanUpload}
      />
      <p aria-live="polite">연결 가능한 CLEAN 파일 {state.ids.length}개</p>
    </div>
  )
}
