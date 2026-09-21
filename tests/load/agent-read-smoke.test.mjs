import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const corpus = { version: 1, datasetId: 'mock-only', groups: [
  ['ticket-number', 10], ['requester', 10], ['phrase', 20], ['topic', 20], ['common', 15], ['short', 10], ['internal', 10], ['absent', 5],
].map(([queryClass, weight]) => ({ queryClass, weight, queries: Array.from({ length: 20 }, (_, i) => `${queryClass}-input-${i}`) })) };

test('real k6: repeated sessions, diverse requests, warmup, empty responses, fail-closed script metrics', { timeout: 90000 }, async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'deskseed-k6-test-'));
  const corpusPath = path.join(directory, 'search-corpus.json');
  const accountsPath = path.join(directory, 'staff-accounts.json');
  await writeFile(corpusPath, JSON.stringify(corpus), { mode: 0o600 });
  await writeFile(accountsPath, JSON.stringify(Array.from({ length: 5 }, (_, i) => ({ email: `agent${i}@mock.invalid`, password: 'mock-only-password' }))), { mode: 0o600 });
  let state;
  const reset = (failure = null) => { state = { failure, logins: 0, queries: [], sorts: [], interactionIds: new Set(), requestIds: new Set(), duplicateRequest: false, missingHeader: false, accountEmails: new Set() }; };
  reset();
  const server = http.createServer(async (request, response) => {
    let text = ''; for await (const chunk of request) text += chunk;
    const id = request.headers['x-request-id'];
    if (state.requestIds.has(id)) state.duplicateRequest = true;
    state.requestIds.add(id);
    const cookie = request.headers.cookie || '';
    const authenticated = cookie.includes('JSESSIONID=authenticated-');
    const send = (status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(body === undefined ? '' : JSON.stringify(body)); };
    if (request.url === '/api/v1/agent/csrf') {
      if (!authenticated) response.setHeader('Set-Cookie', 'JSESSIONID=anonymous; Path=/; HttpOnly');
      return send(200, { headerName: 'X-CSRF-TOKEN', token: authenticated ? 'after-login' : 'before-login' });
    }
    if (request.url === '/api/v1/agent/session') {
      const body = JSON.parse(text);
      if (!cookie.includes('JSESSIONID=anonymous') || request.headers['x-csrf-token'] !== 'before-login') return send(403, {});
      state.logins++; state.accountEmails.add(body.email);
      response.setHeader('Set-Cookie', `JSESSIONID=authenticated-${state.logins}; Path=/; HttpOnly`);
      return send(204);
    }
    if (!authenticated) return send(401, {});
    if (request.url === '/api/v1/agent/me') return send(200, { id: '00000000-0000-4000-8000-000000000001' });
    if (request.url.startsWith('/api/v1/agent/views/')) {
      if (state.failure === 'queue') return send(500, {});
      return send(200, { items: [{ ticketNumber: 1000001 }, { ticketNumber: 1000002 }] });
    }
    if (request.url.startsWith('/api/v1/agent/tickets/')) {
      if (!request.headers['x-interaction-id'] || request.headers['x-deskseed-read-intent'] !== 'NAVIGATION') state.missingHeader = true;
      return send(200, { ticket: {} });
    }
    if (request.url === '/api/v1/agent/search') {
      const interaction = request.headers['x-interaction-id'];
      if (!interaction || state.interactionIds.has(interaction) || request.headers['x-csrf-token'] !== 'after-login') state.missingHeader = true;
      if (!['ticket-number', 'requester', 'phrase', 'topic', 'common', 'short', 'internal', 'absent'].includes(request.headers['x-deskseed-search-class']) ||
          !request.headers['x-deskseed-test-run-id'] || !/^\d{1,4}$/.test(request.headers['x-deskseed-search-case'] || '')) state.missingHeader = true;
      state.interactionIds.add(interaction);
      const body = JSON.parse(text);
      state.queries.push(body.query);
      state.sorts.push(body.sort);
      if (state.failure === 'json') { response.end('invalid-json'); return; }
      if (request.headers['x-deskseed-search-class'] === 'short') {
        return send(422, { type: '/problems/agent-search-too-broad', status: 422 });
      }
      // All successful queries deliberately return empty results: the script must not grade relevance.
      return send(200, { items: [], resultCount: { value: 0, relation: 'EXACT' }, nextCursor: null });
    }
    return send(404, {});
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const dockerImage = process.env.K6_DOCKER_IMAGE;
  const host = dockerImage ? 'host.docker.internal' : '127.0.0.1';
  const target = `http://${host}:${port}`;
  const base = { TARGET_URL: target, CONFIRM_DESKSEED_LOAD_TARGET: `${host}:${port}`, STAFF_SEARCH_CORPUS: corpusPath, STAFF_ACCOUNTS_FILE: accountsPath,
    K6_NO_USAGE_REPORT: 'true', TEST_ENVIRONMENT: 'local', TEST_RUN_ID: 'mock-test', LOAD_PROFILE: 'smoke', SMOKE_MAX_DURATION: '20s' };
  async function run(name, overrides = {}) {
    const results = path.join(directory, name); await mkdir(results);
    const variables = { ...base, RESULTS_DIRECTORY: results, ...overrides };
    const envArgs = Object.entries(variables).flatMap(([key, value]) => ['-e', `${key}=${value}`]);
    const command = dockerImage ? 'docker' : (process.env.K6_BIN || 'k6');
    const args = dockerImage ? ['run', '--rm', '-v', `${root}:${root}:ro`, '-v', `${directory}:${directory}`, dockerImage, 'run', '--quiet', ...envArgs, `${root}/tests/load/scenarios/agent-read.js`]
      : ['run', '--quiet', ...envArgs, `${root}/tests/load/scenarios/agent-read.js`];
    const child = spawn(command, args, { env: { ...process.env, K6_NO_USAGE_REPORT: 'true' } });
    let output = ''; child.stdout.on('data', (data) => { output += data; }); child.stderr.on('data', (data) => { output += data; });
    const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
    assert(!output.includes('mock-only-password'));
    let summaryText;
    try { summaryText = await readFile(path.join(results, 'agent-read-summary.json'), 'utf8'); }
    catch (error) { assert.equal(code, 0, output); throw error; }
    const summary = JSON.parse(summaryText);
    return { code, output, summary, results };
  }
  try {
    const smoke = await run('smoke', { SMOKE_ITERATIONS: '100' });
    assert.equal(smoke.code, 0, smoke.output);
    assert.equal(state.logins, 1); assert.equal(state.queries.length, 100);
    assert(new Set(state.queries).size >= 80);
    assert.deepEqual(new Set(state.sorts), new Set(['updatedAt:desc,ticketNumber:desc']));
    assert.equal(state.missingHeader, false); assert.equal(state.duplicateRequest, false);
    assert.equal(smoke.summary.metrics.agent_search_empty_results.values.rate, 1);
    assert.equal(smoke.summary.metrics.agent_search_refine_required.values.rate, 0.1);
    assert.equal(smoke.summary.metrics['agent_search_outcomes{phase:measurement,search_outcome:page}'].values.count, 90);
    assert.equal(smoke.summary.metrics['agent_search_outcomes{phase:measurement,search_outcome:refine}'].values.count, 10);
    assert.equal(smoke.summary.metrics['agent_search_outcomes{phase:measurement,search_outcome:invalid}'].values.count, 0);
    assert.equal(smoke.summary.metrics.unexpected_status.values.rate, 0);
    const manifest = await readFile(path.join(smoke.results, 'agent-read-manifest.json'), 'utf8');
    for (const value of [state.queries[0], 'mock-only-password', 'agent0@mock.invalid']) assert(!manifest.includes(value));
    reset();
    const arrival = await run('arrival', { LOAD_PROFILE: 'baseline', TARGET_ITERATIONS_PER_SECOND: '100', PREALLOCATED_VUS: '5', MAX_VUS: '5', WARMUP_DURATION: '1s', LOAD_DURATION: '2s', AGENT_READ_MODE: 'search-only' });
    assert.equal(arrival.code, 0, arrival.output);
    assert(state.accountEmails.size > 1); assert.equal(state.accountEmails.size, state.logins);
    assert.equal(state.missingHeader, false); assert.equal(state.duplicateRequest, false);
    const started = arrival.summary.metrics['agent_journeys_started{phase:measurement}'].values.count;
    assert(started > 100 && started < arrival.summary.metrics.agent_journeys_started.values.count);
    reset('queue');
    const queue = await run('queue-failure', { SMOKE_ITERATIONS: '2' });
    assert.notEqual(queue.code, 0); assert.equal(state.queries.length, 0);
    assert.equal(queue.summary.metrics.agent_search_reached.values.rate, 0);
    reset('json');
    const json = await run('json-failure', { SMOKE_ITERATIONS: '2' });
    assert.notEqual(json.code, 0); assert.equal(json.summary.metrics.agent_journeys_completed.values.rate, 0);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
