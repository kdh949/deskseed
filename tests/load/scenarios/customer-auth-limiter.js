import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { authOptions, requestHeaders, requireConfirmedTarget, targetUrl } from '../lib/config.js';

const unexpectedStatus = new Rate('unexpected_status');
const expectedThrottles = new Counter('expected_throttles');
requireConfirmedTarget();
export const options = authOptions();

export default function () {
  const email = __ENV.CUSTOMER_EMAIL;
  const password = __ENV.CUSTOMER_PASSWORD;
  if (!email || !password) fail('CUSTOMER_EMAIL and CUSTOMER_PASSWORD are required');
  const response = http.post(
    `${targetUrl}/api/v1/customer/auth/password-sessions`,
    JSON.stringify({ email, password }),
    {
      headers: requestHeaders({ 'Content-Type': 'application/json' }),
      tags: { name: 'customer_password_session' },
    },
  );
  const expected = response.status === 200 || response.status === 401 || response.status === 429;
  if (response.status === 429) expectedThrottles.add(1);
  unexpectedStatus.add(!expected);
  check(response, {
    'auth response is bounded': () => expected,
    'rate limit has retry-after': (result) => result.status !== 429 || Number(result.headers['Retry-After']) >= 1,
  });
}
