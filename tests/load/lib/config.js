import { fail } from 'k6';
import { createSummaryHandler } from './evidence.js';

export const targetUrl = requiredUrl('TARGET_URL');
export const runId = (__ENV.TEST_RUN_ID || 'smoke-local').replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 80);
export const loadProfile = __ENV.LOAD_PROFILE || 'smoke';

export function requireConfirmedTarget({ writes = false } = {}) {
  const targetHost = targetUrl.replace(/^https?:\/\//, '').split('/')[0];
  if (loadProfile !== 'smoke' && __ENV.CONFIRM_DESKSEED_LOAD_TARGET !== targetHost) {
    fail(`CONFIRM_DESKSEED_LOAD_TARGET must equal ${targetHost}`);
  }
  if (writes && __ENV.CONFIRM_DESTRUCTIVE_WRITES !== 'true') {
    fail('CONFIRM_DESTRUCTIVE_WRITES=true is required for a write scenario');
  }
}

export function handleSummaryFor(scenarioName, correlationScenarios = [scenarioName]) {
  return createSummaryHandler({ scenarioName, correlationScenarios, runId, loadProfile, env: __ENV });
}

export function standardOptions(scenarioName) {
  const thresholds = httpThresholds('http_req_duration{expected_response:true}');
  if (loadProfile === 'smoke') {
    return {
      scenarios: {
        [scenarioName]: {
          executor: 'shared-iterations',
          vus: 1,
          iterations: 1,
          maxDuration: '45s',
          tags: commonTags(scenarioName),
        },
      },
      thresholds,
      summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
    };
  }

  const rate = requiredPositiveInteger('TARGET_RPS', 1000);
  const duration = __ENV.LOAD_DURATION || (loadProfile === 'soak' ? '30m' : '5m');
  const preAllocatedVUs = positiveInteger('PREALLOCATED_VUS', Math.max(10, rate), 2000);
  const maxVUs = positiveInteger('MAX_VUS', Math.max(preAllocatedVUs, rate * 2), 4000);
  return {
    scenarios: {
      [scenarioName]: {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration,
        preAllocatedVUs,
        maxVUs,
        tags: commonTags(scenarioName),
      },
    },
    thresholds,
    summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}

export function authOptions() {
  const thresholds = httpThresholds('http_req_duration{name:customer_password_session}');
  const profile = loadProfile;
  if (profile === 'smoke') return standardOptions('customer-auth-limiter');

  const profiles = {
    'auth-sustained': { rate: 20, duration: '10m', preAllocatedVUs: 40, maxVUs: 100 },
    'auth-burst': { rate: 100, duration: '60s', preAllocatedVUs: 100, maxVUs: 100 },
    'auth-safety': { rate: 200, duration: '60s', preAllocatedVUs: 200, maxVUs: 200 },
  };
  const selected = profiles[profile];
  if (!selected) fail(`unsupported customer-auth LOAD_PROFILE: ${profile}`);
  if (profile === 'auth-safety' && __ENV.CONFIRM_AUTH_SAFETY !== 'true') {
    fail('CONFIRM_AUTH_SAFETY=true is required for the 2x safety run');
  }
  return {
    scenarios: {
      'customer-auth-limiter': {
        executor: 'constant-arrival-rate',
        timeUnit: '1s',
        ...selected,
        tags: commonTags('customer-auth-limiter'),
      },
    },
    thresholds,
    summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}

export function websocketOptions() {
  const thresholds = validityThresholds();
  if (loadProfile !== 'smoke') {
    const p95 = requiredPositiveNumber('MAX_WEBSOCKET_CONNECT_P95_MS', 600000);
    const p99 = requiredPositiveNumber('MAX_WEBSOCKET_CONNECT_P99_MS', 600000);
    requireOrderedLatencyBudgets(p95, p99, 'WebSocket connection');
    requireEvidenceMetadata();
    thresholds['ws_connecting{name:collaboration_websocket}'] = [`p(95)<${p95}`, `p(99)<${p99}`];
  }
  if (loadProfile === 'smoke') {
    return {
      scenarios: {
        collaboration_websocket: {
          executor: 'shared-iterations',
          vus: 1,
          iterations: 1,
          maxDuration: '45s',
          tags: commonTags('collaboration-websocket'),
        },
      },
      thresholds,
    };
  }
  const vus = requiredPositiveInteger('TARGET_CONNECTIONS', 2000);
  return {
    scenarios: {
      collaboration_websocket: {
        executor: 'constant-vus',
        vus,
        duration: __ENV.LOAD_DURATION || '5m',
        tags: commonTags('collaboration-websocket'),
      },
    },
    thresholds,
  };
}

export function mixedOptions() {
  const flowNames = ['agent-read', 'public-request', 'customer-auth-limiter', 'collaboration-websocket'];
  const thresholds = validityThresholds();
  if (loadProfile === 'smoke') {
    return {
      scenarios: Object.fromEntries(flowNames.map((name) => [name, {
        executor: 'shared-iterations',
        exec: mixedExecName(name),
        vus: 1,
        iterations: 1,
        maxDuration: '45s',
        tags: commonTags(name),
      }])),
      thresholds,
      summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
    };
  }

  const duration = __ENV.LOAD_DURATION || (loadProfile === 'soak' ? '30m' : '5m');
  const agentRate = requiredPositiveInteger('MIXED_AGENT_RPS', 1000);
  const publicRate = requiredPositiveInteger('MIXED_PUBLIC_RPS', 1000);
  const authRate = requiredPositiveInteger('MIXED_AUTH_RPS', 1000);
  const websocketConnections = requiredPositiveInteger('MIXED_WEBSOCKET_CONNECTIONS', 2000);

  addLatencyBudget(thresholds, 'http_req_duration{scenario:agent-read,expected_response:true}', 'MIXED_AGENT_MAX_HTTP_P95_MS', 'MIXED_AGENT_MAX_HTTP_P99_MS');
  addLatencyBudget(thresholds, 'http_req_duration{scenario:public-request,expected_response:true}', 'MIXED_PUBLIC_MAX_HTTP_P95_MS', 'MIXED_PUBLIC_MAX_HTTP_P99_MS');
  addLatencyBudget(thresholds, 'http_req_duration{scenario:customer-auth-limiter,name:customer_password_session}', 'MIXED_AUTH_MAX_HTTP_P95_MS', 'MIXED_AUTH_MAX_HTTP_P99_MS');
  addLatencyBudget(thresholds, 'ws_connecting{scenario:collaboration-websocket,name:collaboration_websocket}', 'MIXED_WEBSOCKET_MAX_CONNECT_P95_MS', 'MIXED_WEBSOCKET_MAX_CONNECT_P99_MS');
  requireEvidenceMetadata();

  return {
    scenarios: {
      'agent-read': mixedArrivalScenario('agentRead', 'agent-read', agentRate, duration, 'MIXED_AGENT'),
      'public-request': mixedArrivalScenario('publicRequest', 'public-request', publicRate, duration, 'MIXED_PUBLIC'),
      'customer-auth-limiter': mixedArrivalScenario('customerAuthLimiter', 'customer-auth-limiter', authRate, duration, 'MIXED_AUTH'),
      'collaboration-websocket': {
        executor: 'constant-vus',
        exec: 'collaborationWebSocket',
        vus: websocketConnections,
        duration,
        tags: commonTags('collaboration-websocket'),
      },
    },
    thresholds,
    summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}

export function requestHeaders(extra = {}) {
  const suffix = `${__VU}-${__ITER}`;
  return {
    'X-Request-Id': `load-${runId}-${suffix}`.slice(0, 100),
    'X-Correlation-Id': `load-${runId}-${suffix}`.slice(0, 100),
    ...extra,
  };
}

export function randomUuid() {
  const hex = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx';
  return hex.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === 'x' ? value : (value & 0x3) | 0x8).toString(16);
  });
}

export function commonTags(scenarioName) {
  return { environment: 'load', service: 'deskseed', profile: loadProfile, scenario: scenarioName, test_run_id: runId };
}

function mixedExecName(scenarioName) {
  return {
    'agent-read': 'agentRead',
    'public-request': 'publicRequest',
    'customer-auth-limiter': 'customerAuthLimiter',
    'collaboration-websocket': 'collaborationWebSocket',
  }[scenarioName];
}

function mixedArrivalScenario(exec, scenarioName, rate, duration, prefix) {
  const preAllocatedVUs = positiveInteger(`${prefix}_PREALLOCATED_VUS`, Math.max(10, rate), 2000);
  const maxVUs = positiveInteger(`${prefix}_MAX_VUS`, Math.max(preAllocatedVUs, rate * 2), 4000);
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs,
    maxVUs,
    tags: commonTags(scenarioName),
  };
}

function validityThresholds() {
  return {
    checks: ['rate==1'],
    unexpected_status: ['rate==0'],
    dropped_iterations: ['count==0'],
  };
}

function httpThresholds(metric) {
  const thresholds = validityThresholds();
  if (loadProfile === 'smoke') return thresholds;

  const p95 = requiredPositiveNumber('MAX_HTTP_P95_MS', 600000);
  const p99 = requiredPositiveNumber('MAX_HTTP_P99_MS', 600000);
  requireOrderedLatencyBudgets(p95, p99, 'HTTP');
  requireEvidenceMetadata();
  thresholds[metric] = [`p(95)<${p95}`, `p(99)<${p99}`];
  return thresholds;
}

function requireOrderedLatencyBudgets(p95, p99, label) {
  if (p99 < p95) fail(`${label} p99 budget must be greater than or equal to p95 budget`);
}

function addLatencyBudget(thresholds, metric, p95Name, p99Name) {
  const p95 = requiredPositiveNumber(p95Name, 600000);
  const p99 = requiredPositiveNumber(p99Name, 600000);
  requireOrderedLatencyBudgets(p95, p99, metric);
  thresholds[metric] = [`p(95)<${p95}`, `p(99)<${p99}`];
}

function requireEvidenceMetadata() {
  for (const name of [
    'LOAD_ENVIRONMENT_ID',
    'FIXTURE_DATASET_ID',
    'FIXTURE_SIZE',
    'TELEMETRY_MODE',
    'LOAD_GENERATOR_ID',
    'APP_RESOURCE_LIMITS',
    'DESKSEED_GIT_SHA',
    'DESKSEED_GIT_DIRTY',
  ]) {
    requiredBoundedText(name, 500);
  }
  if (!/^[0-9a-f]{40}([0-9a-f]{24})?$/.test(__ENV.DESKSEED_GIT_SHA)) {
    fail('DESKSEED_GIT_SHA must be a 40 or 64 character lowercase hexadecimal commit ID');
  }
  if (!['true', 'false'].includes(__ENV.DESKSEED_GIT_DIRTY)) {
    fail('DESKSEED_GIT_DIRTY must be true or false');
  }
}

function requiredUrl(name) {
  const value = __ENV[name];
  if (!value || !/^https?:\/\/[^/]+/.test(value)) fail(`${name} must be an absolute HTTP(S) URL`);
  return value.replace(/\/$/, '');
}

function requiredPositiveInteger(name, maximum) {
  if (!__ENV[name]) fail(`${name} is required for LOAD_PROFILE=${loadProfile}`);
  return positiveInteger(name, Number(__ENV[name]), maximum);
}

function requiredPositiveNumber(name, maximum) {
  if (!__ENV[name]) fail(`${name} is required for LOAD_PROFILE=${loadProfile}`);
  const value = Number(__ENV[name]);
  if (!Number.isFinite(value) || value <= 0 || value > maximum) fail(`${name} must be greater than 0 and at most ${maximum}`);
  return value;
}

function requiredBoundedText(name, maximum) {
  const value = __ENV[name];
  if (!value) fail(`${name} is required for LOAD_PROFILE=${loadProfile}`);
  if (value.length > maximum || /[\u0000-\u001f\u007f]/.test(value)) fail(`${name} must be at most ${maximum} characters without control characters`);
  return value;
}

function positiveInteger(name, fallback, maximum) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < 1 || value > maximum) fail(`${name} must be between 1 and ${maximum}`);
  return value;
}
