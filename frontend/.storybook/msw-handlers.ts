import { http, HttpResponse } from 'msw'

export const mswHandlers = [
  http.get('/api/v1/agent/me', () =>
    HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
  ),
]
