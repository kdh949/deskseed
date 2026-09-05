import assert from 'node:assert/strict';
import test from 'node:test';
import { verifyMetrics } from '../../../scripts/load/verify-metrics.mjs';

test('live metric verifier distinguishes missing, down, and a collected zero-valued counter', async () => {
  const queries = [];
  const result = await verifyMetrics('http://prometheus.invalid', 'app-a', async url => {
    const query = url.searchParams.get('query');
    queries.push(query);
    return { ok: true, json: async () => ({ status: 'success', data: { resultType: 'vector', result:
      query.startsWith('up{') ? [{ metric: { job: 'deskseed-backend' }, value: [1, '0'] }]
        : query.includes('jvm_gc_pause') ? [] : [{ metric: {}, value: [1, '1'] }],
    } }) };
  });
  assert.equal(result.find(r => r.name === 'deskseed-backend').status, 'DOWN');
  assert.equal(result.find(r => r.name === 'deskseed-node').status, 'MISSING');
  assert.equal(result.find(r => r.name === 'jvm_gc_pause_seconds_bucket').status, 'MISSING');
  assert.equal(result.find(r => r.name === 'deskseed_customer_auth_limiter_decisions_total').status, 'PRESENT');
  assert.ok(queries.every(q => q.includes('host="app-a"') && q.includes('stack="deskseed"')));
});

test('live metric verifier fails on query errors instead of declaring an empty result healthy', async () => {
  await assert.rejects(verifyMetrics('http://prometheus.invalid', 'app-a', async () => ({
    ok: true, json: async () => ({ status: 'error', error: 'unavailable' }),
  })));
});
