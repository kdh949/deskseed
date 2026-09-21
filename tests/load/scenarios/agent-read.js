import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { environment, loadProfile, randomUuid, requireConfirmedTarget, runId, standardOptions, targetUrl } from '../lib/config.js';
import { staffAccountCount, staffHeaders, staffSession, staffSessionReady } from '../lib/staff.js';
import { createWorkload, durationSeconds } from '../lib/search-workload.js';

const unexpectedStatus = new Rate('unexpected_status');
const journeysStarted = new Counter('agent_journeys_started');
const journeysCompleted = new Rate('agent_journeys_completed');
const searchReached = new Rate('agent_search_reached');
const searchRequests = new Counter('agent_search_requests');
const emptyResults = new Rate('agent_search_empty_results');
const refineRequired = new Rate('agent_search_refine_required');
const searchOutcomes = new Counter('agent_search_outcomes');
const operationDuration = new Trend('agent_operation_duration', true);
const journeyDuration = new Trend('agent_journey_duration', true);
const lateAuthentications = new Counter('agent_late_authentications');
requireConfirmedTarget();

const mode = __ENV.AGENT_READ_MODE || 'composite';
if (!['composite', 'search-only'].includes(mode)) fail('AGENT_READ_MODE must be composite or search-only');
const workload = loadWorkload();
const viewKeys = (__ENV.STAFF_VIEW_KEYS || __ENV.STAFF_VIEW_KEY || 'pending').split(',').map((value) => value.trim());
if (viewKeys.some((value) => !/^[A-Za-z0-9_-]{1,100}$/.test(value))) fail('Invalid STAFF_VIEW_KEYS');
const warmupSeconds = loadProfile === 'smoke' || !__ENV.WARMUP_DURATION ? 0 : durationSeconds(__ENV.WARMUP_DURATION);
export const options = buildOptions();

export default function () {
  if (!workload) fail('Set STAFF_SEARCH_CORPUS, or STAFF_SEARCH_QUERY for a single-query smoke');
  const phase = (Date.now() - exec.scenario.startTime) / 1000 < warmupSeconds ? 'warmup' : 'measurement';
  const tags = { phase, mode };
  const input = workload.select(exec.scenario.iterationInTest);
  journeysStarted.add(1, tags);
  let reached = false;
  let completed = false;
  let startedAt;
  try {
    const needsLogin = !staffSessionReady();
    staffSession();
    if (needsLogin && phase === 'measurement' && warmupSeconds > 0) lateAuthentications.add(1, tags);
    startedAt = Date.now(); // Exclude login from business-journey latency.
    if (mode === 'composite') {
      const viewKey = viewKeys[exec.scenario.iterationInTest % viewKeys.length];
      const queue = http.get(`${targetUrl}/api/v1/agent/views/${viewKey}/tickets?limit=50`, params('agent_view_tickets', tags));
      record(queue, 'agent_view_tickets', tags);
      const queueBody = safeJson(queue);
      if (!check(queue, { 'agent queue has usable items': () => queue.status === 200 && Array.isArray(queueBody?.items) && queueBody.items.length > 0 }, tags)) return;
      const ticket = queueBody.items[exec.scenario.iterationInTest % queueBody.items.length];
      if (!check(ticket, { 'agent queue ticket number is usable': (value) => Number.isSafeInteger(value?.ticketNumber) && value.ticketNumber > 0 }, tags)) return;
      const detail = http.get(`${targetUrl}/api/v1/agent/tickets/${ticket.ticketNumber}`, params('agent_ticket_detail', tags, {
        'X-Interaction-Id': randomUuid(), 'X-Deskseed-Read-Intent': 'NAVIGATION',
      }));
      record(detail, 'agent_ticket_detail', tags);
      if (!check(detail, { 'agent ticket detail succeeds': (response) => response.status === 200 }, tags)) return;
    }
    reached = true;
    const searchTags = { ...tags, query_class: input.queryClass };
    searchRequests.add(1, searchTags);
    const response = http.post(`${targetUrl}/api/v1/agent/search`, JSON.stringify({
      query: input.query, filters: {}, sort: 'score:desc,ticketNumber:desc', limit: 25,
    }), params('agent_search', searchTags, {
      'Content-Type': 'application/json', 'X-Interaction-Id': randomUuid(),
      'X-Deskseed-Search-Class': input.queryClass, 'X-Deskseed-Test-Run-Id': runId,
      'X-Deskseed-Search-Case': String(input.caseIndex ?? 0),
    }));
    const body = safeJson(response);
    const pageResponse = response.status === 200 && Array.isArray(body?.items) && usableResultCount(body?.resultCount);
    const refineResponse = response.status === 422 && body?.type === '/problems/agent-search-too-broad';
    const outcomeTags = { ...searchTags, search_outcome: pageResponse ? 'page' : refineResponse ? 'refine' : 'invalid' };
    record(response, 'agent_search', outcomeTags, pageResponse || refineResponse);
    searchOutcomes.add(1, outcomeTags);
    completed = check(response, {
      'agent search returns a usable page or refine response': () => pageResponse || refineResponse,
    }, outcomeTags);
    refineRequired.add(refineResponse, searchTags);
    if (pageResponse) emptyResults.add(body.items.length === 0, outcomeTags);
    // Observe empty results; do not compare expected counts, ticket IDs, or rank.
  } finally {
    journeysCompleted.add(completed, tags);
    searchReached.add(reached, tags);
    if (startedAt !== undefined) journeyDuration.add(Date.now() - startedAt, tags);
  }
}

function params(name, tags, extra = {}) {
  const result = { headers: staffHeaders(extra), tags: { name, ...tags }, timeout: __ENV.HTTP_TIMEOUT || '15s', redirects: 0 };
  if (name === 'agent_search') result.responseCallback = http.expectedStatuses(200, 422);
  return result;
}
function safeJson(response) {
  try { return response.json(); } catch (_) { return null; }
}
function record(response, operation, tags, expected = response.status === 200) {
  unexpectedStatus.add(!expected, tags);
  operationDuration.add(response.timings.duration, { ...tags, operation });
}
function usableResultCount(value) {
  if (!value || typeof value !== 'object') return false;
  if (value.relation === 'UNAVAILABLE') return value.value === null;
  return (value.relation === 'EXACT' || value.relation === 'LOWER_BOUND') &&
    Number.isSafeInteger(value.value) && value.value >= 0;
}
function loadWorkload() {
  if (__ENV.STAFF_SEARCH_CORPUS) {
    let corpus;
    try { corpus = JSON.parse(open(__ENV.STAFF_SEARCH_CORPUS)); } catch (_) { fail('Unable to read search corpus JSON'); }
    return createWorkload(corpus, Number(__ENV.SEARCH_SEED || 20260919));
  }
  if (loadProfile !== 'smoke') fail('STAFF_SEARCH_CORPUS is required for non-smoke agent-read');
  if (!__ENV.STAFF_SEARCH_QUERY) return null; // Allows k6 inspect without credentials or fixture data.
  const query = __ENV.STAFF_SEARCH_QUERY;
  if (!query.trim() || query.length > 500 || /[\x00-\x1f\x7f]/.test(query)) fail('Invalid STAFF_SEARCH_QUERY');
  return { datasetId: 'single-query-smoke', seed: null, groups: [{ queryClass: 'single-smoke', weight: 100, size: 1 }], select: () => ({ query, queryClass: 'single-smoke' }) };
}
function buildOptions() {
  const result = standardOptions('agent-read');
  result.noCookiesReset = true;
  // In particular, omit raw URL, error text, VU and iteration tags.
  result.systemTags = ['status', 'method', 'name', 'scenario', 'group', 'check', 'error_code', 'expected_response'];
  result.tags = { environment, service: 'deskseed', profile: loadProfile, test_run_id: runId };
  result.thresholds['agent_journeys_started{phase:measurement}'] = ['count>0'];
  result.thresholds.agent_journeys_completed = ['rate==1'];
  result.thresholds.agent_search_reached = ['rate==1'];
  const scenario = result.scenarios['agent-read'];
  if (staffAccountCount() && staffAccountCount() < (scenario.maxVUs || scenario.vus)) fail('Staff account pool is smaller than allocated VUs');
  if (loadProfile !== 'smoke') {
    scenario.duration = `${durationSeconds(scenario.duration) + warmupSeconds}s`;
    if (scenario.maxVUs !== scenario.preAllocatedVUs) fail('agent-read requires MAX_VUS=PREALLOCATED_VUS to avoid dynamic login during measurement');
    const budgets = { agent_view_tickets: [300, 600], agent_ticket_detail: [500, 1000], agent_search: [500, 1000] };
    for (const [operation, defaults] of Object.entries(budgets)) {
      if (mode === 'search-only' && operation !== 'agent_search') continue;
      const prefix = { agent_view_tickets: 'QUEUE', agent_ticket_detail: 'DETAIL', agent_search: 'SEARCH' }[operation];
      result.thresholds[`agent_operation_duration{phase:measurement,operation:${operation}}`] = budget(prefix, ...defaults);
    }
    result.thresholds['agent_journey_duration{phase:measurement}'] = budget('JOURNEY', mode === 'composite' ? 1500 : 500, mode === 'composite' ? 3000 : 1000);
    for (const group of workload.groups) {
      result.thresholds[`agent_operation_duration{phase:measurement,operation:agent_search,query_class:${group.queryClass}}`] = budget('SEARCH', 500, 1000);
    }
  }
  for (const group of workload?.groups || []) {
    result.thresholds[`agent_search_requests{phase:measurement,query_class:${group.queryClass}}`] = [loadProfile === 'smoke' ? 'count>=0' : 'count>0'];
  }
  for (const outcome of ['page', 'refine', 'invalid']) {
    result.thresholds[`agent_search_outcomes{phase:measurement,search_outcome:${outcome}}`] = ['count>=0'];
  }
  for (const outcome of ['page', 'refine']) {
    result.thresholds[`agent_operation_duration{phase:measurement,operation:agent_search,search_outcome:${outcome}}`] = ['p(95)>=0'];
  }
  return result;
}
function budget(prefix, defaultP95, defaultP99) {
  const p95 = Number(__ENV[`${prefix}_P95_MS`] || defaultP95);
  const p99 = Number(__ENV[`${prefix}_P99_MS`] || defaultP99);
  if (!Number.isFinite(p95) || p95 <= 0 || !Number.isFinite(p99) || p99 < p95) fail(`Invalid ${prefix} latency budget`);
  return [`p(95)<=${p95}`, `p(99)<=${p99}`];
}

export function handleSummary(data) {
  const directory = __ENV.RESULTS_DIRECTORY || '.';
  const groups = (workload?.groups || []).map((group) => ({ ...group,
    measurementRequests: data.metrics[`agent_search_requests{phase:measurement,query_class:${group.queryClass}}`]?.values?.count || 0,
  }));
  const manifest = {
    testRunId: runId, environment, mode, profile: loadProfile, target: targetUrl,
    datasetId: workload?.datasetId, searchSeed: workload?.seed, groups,
    warmupSeconds, measurementDuration: loadProfile === 'smoke' ? null : (__ENV.LOAD_DURATION || (loadProfile === 'soak' ? '30m' : '5m')),
    accountMode: staffAccountCount() ? 'one-per-vu' : 'single-account',
    scriptRevision: __ENV.LOAD_SCRIPT_REVISION || 'unrecorded',
    scenarios: options.scenarios, thresholds: options.thresholds,
    note: 'Input diversity, page/refine outcome, and latency measurement only; no search-result oracle. No service capacity claim from a mock or smoke run.',
  };
  return {
    [`${directory}/agent-read-summary.json`]: JSON.stringify(data, null, 2),
    [`${directory}/agent-read-manifest.json`]: JSON.stringify(manifest, null, 2),
    stdout: `agent-read ${mode}: ${runId}; evidence written to ${directory}\n`,
  };
}
