import http from 'k6/http';
import { check, fail } from 'k6';
import { requestHeaders, targetUrl } from './config.js';

let session;

export function staffSession() {
  if (session) return session;
  const email = requiredSecret('STAFF_EMAIL');
  const password = requiredSecret('STAFF_PASSWORD');
  const csrfResponse = http.get(`${targetUrl}/api/v1/agent/csrf`, {
    headers: requestHeaders(),
    tags: { name: 'staff_csrf' },
  });
  if (!check(csrfResponse, { 'staff CSRF is available': (response) => response.status === 200 })) fail('staff CSRF failed');
  const csrf = csrfResponse.json();
  const login = http.post(
    `${targetUrl}/api/v1/agent/session`,
    JSON.stringify({ email, password }),
    {
      headers: requestHeaders({ 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }),
      tags: { name: 'staff_login' },
    },
  );
  if (!check(login, { 'staff login succeeds': (response) => response.status === 204 })) fail('staff login failed');
  const me = http.get(`${targetUrl}/api/v1/agent/me`, {
    headers: requestHeaders(),
    tags: { name: 'staff_me' },
  });
  if (!check(me, { 'staff identity is available': (response) => response.status === 200 })) fail('staff identity failed');
  session = { csrf, staffId: me.json('id') };
  return session;
}

export function staffHeaders(extra = {}) {
  const current = staffSession();
  return requestHeaders({
    [current.csrf.headerName]: current.csrf.token,
    'X-Deskseed-Expected-Staff-Id': current.staffId,
    ...extra,
  });
}

function requiredSecret(name) {
  const value = __ENV[name];
  if (!value) fail(`${name} is required`);
  return value;
}
