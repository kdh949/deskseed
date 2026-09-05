function safeToken(value) {
  return String(value || 'unknown').replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 80);
}

function optionalNumber(value) {
  if (value === undefined || value === null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function metricValue(metrics, metricName, valueName) {
  return metrics?.[metricName]?.values?.[valueName] ?? null;
}

function thresholdResults(metrics) {
  const defined = [];
  const failed = [];
  for (const [metricName, metric] of Object.entries(metrics || {})) {
    for (const [expression, result] of Object.entries(metric.thresholds || {})) {
      const label = `${metricName}: ${expression}`;
      defined.push({ metric: metricName, expression, passed: result.ok === true });
      if (result.ok !== true) failed.push(label);
    }
  }
  defined.sort((left, right) => `${left.metric}:${left.expression}`.localeCompare(`${right.metric}:${right.expression}`));
  failed.sort();
  return { defined, failed };
}

export function evidenceFileStem(runId, scenarioName) {
  return `${safeToken(runId)}-${safeToken(scenarioName)}`;
}

export function buildEvidenceManifest({ scenarioName, correlationScenarios = [scenarioName], runId, loadProfile, startedAt, completedAt, env, data }) {
  const thresholds = thresholdResults(data?.metrics);
  const safeScenarios = correlationScenarios.map(safeToken);
  const dashboardUids = ['deskseed-load-overview', 'deskseed-load-diagnostics'];
  const dashboardParameters = [
    ['from', Date.parse(startedAt)], ['to', Date.parse(completedAt)],
    ['var-environment', 'load'], ['var-test_run_id', safeToken(runId)],
    ['var-profile', safeToken(loadProfile)],
    ...safeScenarios.map(scenario => ['var-scenario', scenario]),
  ].map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&');
  return {
    schemaVersion: 2,
    identity: {
      testRunId: safeToken(runId),
      scenario: safeToken(scenarioName),
      profile: safeToken(loadProfile),
      startedAt,
      completedAt,
    },
    source: {
      commitSha: env.DESKSEED_GIT_SHA || null,
      dirty: env.DESKSEED_GIT_DIRTY === 'true' ? true : env.DESKSEED_GIT_DIRTY === 'false' ? false : null,
    },
    environment: {
      id: env.LOAD_ENVIRONMENT_ID || null,
      loadGeneratorId: env.LOAD_GENERATOR_ID || null,
      appResourceLimits: env.APP_RESOURCE_LIMITS || null,
      fixtureDatasetId: env.FIXTURE_DATASET_ID || null,
      fixtureSize: env.FIXTURE_SIZE || null,
      telemetryMode: env.TELEMETRY_MODE || null,
    },
    workload: {
      duration: env.LOAD_DURATION || null,
      targetRps: optionalNumber(env.TARGET_RPS),
      targetConnections: optionalNumber(env.TARGET_CONNECTIONS),
      maxHttpP95Ms: optionalNumber(env.MAX_HTTP_P95_MS),
      maxHttpP99Ms: optionalNumber(env.MAX_HTTP_P99_MS),
      maxWebSocketConnectP95Ms: optionalNumber(env.MAX_WEBSOCKET_CONNECT_P95_MS),
      maxWebSocketConnectP99Ms: optionalNumber(env.MAX_WEBSOCKET_CONNECT_P99_MS),
      mix: {
        agentRps: optionalNumber(env.MIXED_AGENT_RPS),
        publicRequestIterationsPerSecond: optionalNumber(env.MIXED_PUBLIC_RPS),
        authRps: optionalNumber(env.MIXED_AUTH_RPS),
        websocketConnections: optionalNumber(env.MIXED_WEBSOCKET_CONNECTIONS),
        agentP95Ms: optionalNumber(env.MIXED_AGENT_MAX_HTTP_P95_MS),
        agentP99Ms: optionalNumber(env.MIXED_AGENT_MAX_HTTP_P99_MS),
        publicP95Ms: optionalNumber(env.MIXED_PUBLIC_MAX_HTTP_P95_MS),
        publicP99Ms: optionalNumber(env.MIXED_PUBLIC_MAX_HTTP_P99_MS),
        authP95Ms: optionalNumber(env.MIXED_AUTH_MAX_HTTP_P95_MS),
        authP99Ms: optionalNumber(env.MIXED_AUTH_MAX_HTTP_P99_MS),
        websocketConnectP95Ms: optionalNumber(env.MIXED_WEBSOCKET_MAX_CONNECT_P95_MS),
        websocketConnectP99Ms: optionalNumber(env.MIXED_WEBSOCKET_MAX_CONNECT_P99_MS),
      },
    },
    result: {
      status: thresholds.defined.length === 0 ? 'INCOMPLETE' : thresholds.failed.length === 0 ? 'PASSED' : 'FAILED',
      failedThresholds: thresholds.failed,
      thresholds: thresholds.defined,
    },
    observations: {
      checkRate: metricValue(data?.metrics, 'checks', 'rate'),
      unexpectedStatusRate: metricValue(data?.metrics, 'unexpected_status', 'rate'),
      expectedThrottles: metricValue(data?.metrics, 'expected_throttles', 'count'),
      droppedIterations: metricValue(data?.metrics, 'dropped_iterations', 'count'),
    },
    correlation: {
      prometheusSelector: `test_run_id="${safeToken(runId)}",scenario=~"${safeScenarios.join('|')}",profile="${safeToken(loadProfile)}"`,
      scenarios: safeScenarios,
      dashboardUids,
      dashboardLinks: dashboardUids.map(uid => `/d/${uid}?${dashboardParameters}`),
      manualEvidenceStillRequired: ['Prometheus time-window export', 'pg_stat_statements snapshot', 'Grafana screenshots or links'],
    },
  };
}

export function createSummaryHandler({ scenarioName, correlationScenarios = [scenarioName], runId, loadProfile, env }) {
  const startedAt = new Date().toISOString();
  const fileStem = evidenceFileStem(runId, scenarioName);
  return (data) => {
    const manifest = buildEvidenceManifest({
      scenarioName,
      correlationScenarios,
      runId,
      loadProfile,
      startedAt,
      completedAt: new Date().toISOString(),
      env,
      data,
    });
    return {
      stdout: `${manifest.result.status} ${fileStem}; failed thresholds: ${manifest.result.failedThresholds.length}\n`,
      [`/results/${fileStem}-summary.json`]: JSON.stringify(data, null, 2),
      [`/results/${fileStem}-manifest.json`]: JSON.stringify(manifest, null, 2),
    };
  };
}
