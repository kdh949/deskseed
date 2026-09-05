#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

// Read-only post-deployment check. No load is generated and no credentials or
// complete metric samples are printed. Missing series never become zero.
export async function verifyMetrics(baseUrl, host, request = fetch) {
  const url = new URL(baseUrl);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Use an HTTP(S) Prometheus endpoint without credentials in the URL');
  }
  const labels = `stack="deskseed",environment="load",host=${JSON.stringify(host)}`;
  const results = [];
  async function query(expression) {
    const endpoint = new URL(`${url.href.replace(/\/$/, '')}/api/v1/query`);
    endpoint.searchParams.set('query', expression);
    const response = await request(endpoint, { signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error(`Prometheus HTTP ${response.status}`);
    const body = await response.json();
    if (body.status !== 'success' || body.data?.resultType !== 'vector') throw new Error('Prometheus query did not return a successful vector');
    return body.data.result;
  }
  const targets = await query(`up{${labels}}`);
  for (const name of ['backend', 'node', 'containers', 'postgresql', 'nginx', 'redis', 'alloy']) {
    const job = `deskseed-${name}`;
    const samples = targets.filter(sample => sample.metric.job === job);
    results.push({ name: job, status: samples.length === 0 ? 'MISSING' : samples.every(sample => Number(sample.value[1]) === 1) ? 'PRESENT' : 'DOWN' });
  }
  const groups = [
    ['backend', ['http_server_requests_seconds_bucket', 'jvm_gc_pause_seconds_bucket', 'hikaricp_connections_acquire_seconds_bucket', 'hikaricp_connections_usage_seconds_bucket', 'deskseed_customer_auth_limiter_duration_seconds_bucket', 'deskseed_customer_auth_limiter_decisions_total', 'deskseed_collaboration_websocket_connection_events_total', 'tomcat_threads_busy_threads']],
    ['postgresql', ['pg_stat_activity_count', 'pg_stat_activity_max_tx_duration', 'pg_stat_statements_calls_total', 'pg_stat_statements_rows_total', 'pg_stat_statements_block_read_seconds_total']],
    ['containers', ['container_cpu_usage_seconds_total', 'container_memory_working_set_bytes']],
    ['node', ['node_disk_read_time_seconds_total', 'node_filesystem_avail_bytes', 'node_network_receive_bytes_total']],
  ];
  for (const [source, names] of groups) {
    for (const name of names) {
      const samples = await query(`count(${name}{${labels},job="deskseed-${source}"})`);
      results.push({ name, status: samples.length && Number(samples[0].value[1]) > 0 ? 'PRESENT' : 'MISSING' });
    }
  }
  return results;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 4) {
    console.error('usage: node scripts/load/verify-metrics.mjs <private-prometheus-url> <scrape-host-label>');
    process.exitCode = 2;
  } else {
    try {
      const results = await verifyMetrics(process.argv[2], process.argv[3]);
      for (const result of results) console.log(`${result.status} ${result.name}`);
      console.log('PRESENT verifies collection only. Exercise HTTP, limiter, WebSocket and GC paths before checking; it does not prove capacity or Grafana rendering.');
      process.exitCode = results.every(result => result.status === 'PRESENT') ? 0 : 1;
    } catch (_) {
      console.error('Metric verification failed: check the private endpoint, access, and Prometheus query response.');
      process.exitCode = 2;
    }
  }
}
