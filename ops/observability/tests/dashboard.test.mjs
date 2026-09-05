import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../../", import.meta.url);

async function loadDashboard(name) {
  return JSON.parse(
    await readFile(
      new URL(`ops/observability/monitoring-server/grafana/${name}.json`, repositoryRoot),
      "utf8",
    ),
  );
}

function panel(dashboard, title) {
  const found = dashboard.panels.find((candidate) => candidate.title === title);
  assert.ok(found, `missing panel: ${title}`);
  return found;
}

function queries(targetPanel) {
  return (targetPanel.targets ?? [])
    .map((target) => target.expr ?? target.query ?? target.labelSelector ?? "")
    .join("\n");
}

for (const name of ["deskseed-load-overview", "deskseed-load-diagnostics"]) {
  test(`${name} has stable panel identity, descriptions, and run filters`, async () => {
    const dashboard = await loadDashboard(name);
    const ids = dashboard.panels.map((candidate) => candidate.id);
    const variables = dashboard.templating.list.map((variable) => variable.name);

    assert.equal(ids.every(Number.isInteger), true);
    assert.equal(new Set(ids).size, ids.length);
    assert.equal(dashboard.panels.every((candidate) => candidate.description), true);
    assert.ok(variables.includes("environment"));
    assert.ok(variables.includes("test_run_id"));
    assert.ok(variables.includes("scenario"));
    assert.ok(variables.includes("profile"));
    assert.ok(variables.includes("correlation_id"));
  });
}

test("overview separates arrived work, expected throttling, unexpected responses, and server errors", async () => {
  const dashboard = await loadDashboard("deskseed-load-overview");
  const arrived = queries(panel(dashboard, "Arrived request and iteration rate"));
  const outcome = queries(panel(dashboard, "Expected throttling, unexpected responses, and server 5xx"));
  const latency = queries(panel(dashboard, "Backend successful response p95 / p99"));
  const resourceLinks = JSON.stringify(
    panel(dashboard, "JVM CPU and heap utilization").fieldConfig?.defaults?.links ?? [],
  );

  assert.match(arrived, /k6_http_reqs_total/);
  assert.match(arrived, /k6_iterations_total/);
  assert.match(arrived, /test_run_id="\$test_run_id"/);
  assert.match(outcome, /k6_expected_throttles_total/);
  assert.match(outcome, /k6_unexpected_status_rate/);
  assert.match(outcome, /status=~"5\.\."/);
  assert.match(latency, /status=~"2\.\.|3\.\."/);
  assert.match(resourceLinks, /\$\{__url_time_range\}/);
  assert.match(resourceLinks, /viewPanel=319/);
});

test("diagnostics exposes statement aggregates, Nginx boundaries, delivery lag, and trace pipeline health", async () => {
  const dashboard = await loadDashboard("deskseed-load-diagnostics");
  const statements = queries(panel(dashboard, "PostgreSQL statement calls, total, and mean execution"));
  const nginx = queries(panel(dashboard, "Nginx request, upstream, and response bytes p95"));
  const workers = queries(panel(dashboard, "Mail and webhook backlog age / processing rate"));
  const receiver = queries(panel(dashboard, "Alloy accepted / refused spans"));
  const queue = queries(panel(dashboard, "Alloy exporter queue utilization"));

  assert.match(statements, /pg_stat_statements_calls_total/);
  assert.match(statements, /pg_stat_statements_seconds_total/);
  assert.doesNotMatch(statements, /query=|query_text|statement=/);
  assert.match(nginx, /requestDurationSeconds/);
  assert.match(nginx, /upstreamDurationSeconds/);
  assert.match(nginx, /responseBytes/);
  assert.match(workers, /deskseed_mail_outbox_oldest_age_seconds/);
  assert.match(workers, /deskseed_webhook_delivery_backlog_oldest_age_seconds/);
  assert.match(workers, /deskseed_mail_delivery_succeeded_total/);
  assert.match(workers, /deskseed_webhook_delivery_succeeded_total/);
  assert.match(receiver, /otelcol_receiver_accepted_spans_total/);
  assert.match(receiver, /otelcol_receiver_refused_spans_total/);
  assert.match(queue, /otelcol_exporter_queue_size/);
  assert.match(queue, /otelcol_exporter_queue_capacity/);
});

test("diagnostics has slow and error trace lists plus Loki and time-aligned Pyroscope navigation", async () => {
  const dashboard = await loadDashboard("deskseed-load-diagnostics");
  const slow = panel(dashboard, "Slow traces");
  const errors = panel(dashboard, "Error traces");
  const profile = panel(dashboard, "Time-aligned backend CPU profile");
  const datasource = await readFile(
    new URL("ops/observability/monitoring-server/grafana/datasource-correlations.yml.example", repositoryRoot),
    "utf8",
  );

  assert.equal(slow.datasource.uid, "tempo");
  assert.match(queries(slow), /duration > 500ms/);
  assert.equal(errors.datasource.uid, "tempo");
  assert.match(queries(errors), /status = error/);
  assert.equal(profile.datasource.uid, "pyroscope");
  assert.match(queries(profile), /service_name="deskseed-backend"/);
  assert.match(queries(profile), /environment=~"\$environment"/);
  assert.match(datasource, /tracesToLogsV2:/);
  assert.match(datasource, /filterByTraceID: true/);
  assert.match(datasource, /derivedFields:/);
  assert.match(datasource, /datasourceUid: tempo/);
  assert.doesNotMatch(datasource, /tracesToProfiles:/);
});
