import { http, HttpResponse } from 'msw'

export const mswHandlers = [
  http.post('/api/v1/requests', () =>
    HttpResponse.json(
      {
        accessToken: 'storybook-request-access-token-000001',
        createdAt: '2024-04-01T12:00:00Z',
        status: 'NEW',
        ticketNumber: 101,
      },
      { status: 201 },
    ),
  ),
]
