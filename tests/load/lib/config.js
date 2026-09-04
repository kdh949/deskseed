import { fail } from 'k6';

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

export function standardOptions(scenarioName) {
  const thresholds = {
    checks: ['rate==1'],
    unexpected_status: ['rate==0'],
    dropped_iterations: ['count==0'],
  };
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
  const thresholds = {
    checks: ['rate==1'],
    unexpected_status: ['rate==0'],
    dropped_iterations: ['count==0'],
  };
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
  const thresholds = {
    checks: ['rate==1'],
    unexpected_status: ['rate==0'],
  };
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

function commonTags(scenarioName) {
  return { environment: 'load', service: 'deskseed', profile: loadProfile, scenario: scenarioName, test_run_id: runId };
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

function positiveInteger(name, fallback, maximum) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < 1 || value > maximum) fail(`${name} must be between 1 and ${maximum}`);
  return value;
}
