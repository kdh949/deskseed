#!/usr/bin/env node
// Parse the actual dashboard queries and evaluate representative multi-series
// cases with the same promtool version as the monitoring server.
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = new URL('../../', import.meta.url);
const promtool = process.env.PROMTOOL || 'promtool';
const dashboards = await Promise.all(['overview', 'diagnostics'].map(async name => JSON.parse(
  await readFile(new URL(`ops/observability/monitoring-server/grafana/deskseed-load-${name}.json`, root), 'utf8'),
)));
const variables = {
  __rate_interval: '1m', __range: '5m', environment: 'load', host: 'app-a',
  database: 'deskseed', pool: '.*', test_run_id: 'run.a', scenario: '.*',
  profile: '.*', route: '/api/.*', operation: '.*', compose_project: 'deskseed-load',
};
const expand = expression => expression.replace(/\$(\w+)/g, (_, name) => {
  if (!(name in variables)) throw new Error(`Unknown dashboard variable: ${name}`);
  return variables[name];
});
const expressions = dashboards.flatMap(d => d.panels.flatMap(p => p.datasource?.uid === 'prometheus'
  ? (p.targets || []).map(t => ({ record: `deskseed_contract_${d.uid.replaceAll('-', '_')}_${p.id}_${t.refId}`, expr: expand(t.expr) })) : []));
const query = (dashboard, id, ref = 'A') => expand(dashboards[dashboard].panels.find(p => p.id === id).targets.find(t => t.refId === ref).expr);
const clientLabels = (name, run = 'run.a') => `{environment="load",service="deskseed",test_run_id="${run}",scenario="agent-read",profile="load",name="${name}",status="200"}`;
const dbLabels = (host, stack = 'deskseed') => `stack="${stack}",job="deskseed-postgresql",host="${host}",environment="load",instance="${host}:9187",datname="deskseed",state="active",wait_event_type="Lock",wait_event="transactionid"`;
const fixture = {
  rule_files: [], evaluation_interval: '10s', fuzzy_compare: true,
  tests: [{ interval: '10s', input_series: [
    { series: `k6_http_req_duration_p95${clientLabels('search')}`, values: '0.1+0x6' },
    { series: `k6_http_req_duration_p95${clientLabels('detail')}`, values: '0.8+0x6' },
    { series: `k6_http_req_duration_p95${clientLabels('foreign-run', 'runXa')}`, values: '9+0x6' },
    { series: `pg_stat_activity_count{${dbLabels('app-a')}}`, values: '3+0x6' },
    { series: `pg_stat_activity_count{${dbLabels('app-b')}}`, values: '9+0x6' },
    { series: `pg_stat_activity_count{${dbLabels('app-a','other')}}`, values: '20+0x6' },
  ], promql_expr_test: [
    { expr: query(0, 2), eval_time: '1m', exp_samples: [
      { labels: `k6_http_req_duration_p95${clientLabels('search')}`, value: 0.1 },
      { labels: `k6_http_req_duration_p95${clientLabels('detail')}`, value: 0.8 },
    ] },
    { expr: query(1, 333), eval_time: '1m', exp_samples: [
      { labels: '{instance="app-a:9187",datname="deskseed",wait_event_type="Lock",wait_event="transactionid"}', value: 3 },
    ] },
    { expr: query(1, 337), eval_time: '1m', exp_samples: [] },
  ] }],
};
const directory = await mkdtemp(join(tmpdir(), 'deskseed-promql-'));
try {
  const rules = join(directory, 'rules.json');
  const tests = join(directory, 'tests.json');
  await writeFile(rules, JSON.stringify({ groups: [{ name: 'dashboard-contract', rules: expressions }] }));
  await writeFile(tests, JSON.stringify(fixture));
  for (const args of [['check', 'rules', rules], ['test', 'rules', tests]]) {
    const result = process.argv.includes('--docker')
      ? spawnSync('docker', ['run', '--rm', '--entrypoint', 'promtool', '-v', `${directory}:${directory}:ro`, 'prom/prometheus:v3.14.0', ...args], { stdio: 'inherit' })
      : spawnSync(promtool, args, { stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`promtool failed (${result.status})`);
  }
  console.log(`Parsed ${expressions.length} dashboard queries; percentile identity, host isolation, and missing-series cases passed.`);
} finally {
  await rm(directory, { recursive: true, force: true });
}
