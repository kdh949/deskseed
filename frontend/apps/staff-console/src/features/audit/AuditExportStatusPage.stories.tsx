import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { AuditExportStatusPage } from './AuditExportStatusPage'

const jobId = '11111111-1111-4111-8111-111111111111'

const meta = {
  title: '07 Screens/Audit Export Status Page',
  component: AuditExportStatusPage,
  parameters: {
    msw: {
      handlers: [
        http.get(`/api/v1/audit/exports/${jobId}`, () =>
          HttpResponse.json({
            id: jobId,
            status: 'READY',
            createdAt: '2026-08-14T09:00:00Z',
            format: 'CSV',
            fields: ['occurredAt', 'action'],
            artifact: {
              state: 'READY',
              rowCount: 184,
              sizeBytes: 24576,
              checksumSha256:
                'ea3582c0eacf31ba0ad2157f7e8cc8b5c16d21a1c74b4740269f349da1c9d2d2',
              expiresAt: '2026-08-14T10:00:00Z',
              contentType: 'text/csv',
              failureCode: null,
            },
          }),
        ),
      ],
    },
  },
  render: () => (
    <StoryRoute
      path="/agent/audit/exports/:jobId"
      to={`/agent/audit/exports/${jobId}`}
    >
      <AuditExportStatusPage />
    </StoryRoute>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof AuditExportStatusPage>

export default meta
type Story = StoryObj<typeof meta>

export const ReadyForDownload: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('파일이 준비되었습니다.'),
    ).toBeVisible()
    await expect(canvas.getByRole('button', { name: '다운로드' })).toBeVisible()
  },
}
