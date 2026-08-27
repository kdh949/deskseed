import { http, HttpResponse } from 'msw'

export const mswHandlers = [
  http.get(
    '/api/v1/customer/me',
    () => new HttpResponse(null, { status: 401 }),
  ),
  http.get('/api/v1/customer/access-mode', () =>
    HttpResponse.json({ mode: 'ANONYMOUS_ALLOWED' }),
  ),
  http.get('/api/v1/customer/consent-policies', () =>
    HttpResponse.json({ context: 'REGISTRATION', policies: [] }),
  ),
  http.get('/api/v1/help/categories', () => HttpResponse.json([])),
]
