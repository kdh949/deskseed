import { http, HttpResponse } from 'msw'

export const mswHandlers = {
  customerSession: http.get(
    '/api/v1/customer/me',
    () => new HttpResponse(null, { status: 401 }),
  ),
  accessMode: http.get('/api/v1/customer/access-mode', () =>
    HttpResponse.json({ mode: 'ANONYMOUS_ALLOWED' }),
  ),
  consentPolicies: http.get('/api/v1/customer/consent-policies', () =>
    HttpResponse.json({ context: 'REGISTRATION', policies: [] }),
  ),
  helpCategories: http.get('/api/v1/help/categories', () =>
    HttpResponse.json([]),
  ),
}
