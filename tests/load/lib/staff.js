import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { requestHeaders, targetUrl } from './config.js';

let session;
const accounts = loadAccounts();

export function staffSessionReady() { return Boolean(session); }
export function staffAccountCount() { return accounts?.length || 0; }

export function staffSession() {
  if (session) return session;
  const account = accounts ? accounts[exec.vu.idInTest - 1] : {
    email: requiredSecret('STAFF_EMAIL'), password: requiredSecret('STAFF_PASSWORD'),
  };
  if (!account) fail('STAFF_ACCOUNTS_FILE needs one account per allocated VU');
  const csrf = fetchCsrf();
  const login = http.post(
    `${targetUrl}/api/v1/agent/session`,
    JSON.stringify({ email: account.email, password: account.password }),
    {
      headers: requestHeaders({ 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }),
      tags: { name: 'staff_login', phase: 'authentication' },
      timeout: __ENV.HTTP_TIMEOUT || '15s',
      redirects: 0,
    },
  );
  if (!check(login, { 'staff login succeeds': (response) => response.status === 204 })) fail('staff login failed');
  // Refresh after authentication so this also works when CSRF rotates with login.
  const authenticatedCsrf = fetchCsrf();
  const me = http.get(`${targetUrl}/api/v1/agent/me`, {
    headers: requestHeaders(),
    tags: { name: 'staff_me', phase: 'authentication' },
    timeout: __ENV.HTTP_TIMEOUT || '15s',
    redirects: 0,
  });
  const identity = safeJson(me);
  if (!check(me, { 'staff identity is available': () => me.status === 200 && typeof identity?.id === 'string' })) fail('staff identity failed');
  session = { csrf: authenticatedCsrf, staffId: identity.id };
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

function fetchCsrf() {
  const response = http.get(`${targetUrl}/api/v1/agent/csrf`, {
    headers: requestHeaders(),
    tags: { name: 'staff_csrf', phase: 'authentication' },
    timeout: __ENV.HTTP_TIMEOUT || '15s',
    redirects: 0,
  });
  const csrf = safeJson(response);
  if (!check(response, { 'staff CSRF is available': () => response.status === 200 && typeof csrf?.headerName === 'string' && typeof csrf?.token === 'string' })) fail('staff CSRF failed');
  return csrf;
}

function safeJson(response) {
  try { return response.json(); } catch (_) { return null; }
}

function loadAccounts() {
  if (!__ENV.STAFF_ACCOUNTS_FILE) return null;
  let value;
  try { value = JSON.parse(open(__ENV.STAFF_ACCOUNTS_FILE)); } catch (_) { fail('Unable to read staff accounts JSON'); }
  if (!Array.isArray(value) || !value.length || value.some((account) =>
    typeof account?.email !== 'string' || !account.email || typeof account?.password !== 'string' || !account.password)) {
    fail('Staff accounts must be a nonempty array of email/password objects');
  }
  if (new Set(value.map((account) => account.email.toLowerCase())).size !== value.length) fail('Staff account emails must be distinct');
  return value;
}

function requiredSecret(name) {
  const value = __ENV[name];
  if (!value) fail(`${name} is required`);
  return value;
}
