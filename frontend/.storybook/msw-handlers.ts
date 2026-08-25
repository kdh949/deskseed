import { http, HttpResponse, ws } from 'msw'

const collaborationSocket = ws.link('/ws/agent/collaboration')
const collaborationHandler = collaborationSocket.addEventListener(
  'connection',
  ({ client }) => client.close(),
)

export const mswHandlers = [
  http.get('/api/v1/agent/me', () =>
    HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
  ),
  http.get('/api/v1/agent/tickets/:ticketNumber/drafts/:channel', () =>
    HttpResponse.json({ code: 'NOT_FOUND' }, { status: 404 }),
  ),
  http.put(
    '/api/v1/agent/tickets/:ticketNumber/drafts/:channel',
    async ({ params, request }) => {
      const input = (await request.json()) as {
        body?: string
        attachmentIds?: string[]
        clientDeviceId?: string
        baseTicketVersion?: number
      }

      return HttpResponse.json({
        ticketNumber: Number(params.ticketNumber),
        channel: params.channel,
        body: input.body ?? '',
        attachmentIds: input.attachmentIds ?? [],
        clientDeviceId:
          input.clientDeviceId ?? '00000000-0000-4000-8000-000000000001',
        baseTicketVersion: input.baseTicketVersion ?? 0,
        draftVersion: 1,
        updatedAt: '2026-08-22T00:00:00Z',
        expiresAt: '2026-09-21T00:00:00Z',
      })
    },
  ),
  http.delete(
    '/api/v1/agent/tickets/:ticketNumber/drafts/:channel',
    () => new HttpResponse(null, { status: 204 }),
  ),
  collaborationHandler,
]
