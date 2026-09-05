import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../../../", import.meta.url);
const dashboard = async (name) => JSON.parse(await readFile(new URL(`ops/observability/monitoring-server/grafana/deskseed-load-${name}.json`, root), "utf8"));

test("client percentile series retain operation and outcome rather than aggregating quantiles", async () => {
  const panel = (await dashboard("overview")).panels.find((panel) => panel.id === 2);
  assert.equal(panel.fieldConfig.defaults.unit, "s", "k6 remote write normalizes Time-valued gauges to seconds");
  const runner = await readFile(new URL("scripts/load/run-k6.sh", root), "utf8");
  assert.match(runner, /-e K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=false/);
  for (const target of panel.targets) {
    assert.doesNotMatch(target.expr, /\b(max|avg|sum)\s*(by\s*\(|\()/);
    assert.match(target.legendFormat, /\{\{name\}\}/);
    assert.match(target.legendFormat, /\{\{status\}\}/);
  }
});

for (const name of ["overview", "diagnostics"]) {
  test(`${name} scopes server selectors and separates unknown from healthy zero`, async () => {
    const d = await dashboard(name);
    assert.ok(d.templating.list.some((v) => v.name === "host"));
    for (const panel of d.panels) {
      for (const target of panel.targets ?? []) {
        if (!target.expr || panel.datasource?.uid !== "prometheus") continue;
        assert.doesNotMatch(target.expr, /k6_interrupted_iterations/);
        assert.doesNotMatch(target.expr, /or\s+vector\(0\)/);
        for (const [, metric, labels] of target.expr.matchAll(/\b([a-zA-Z_:][a-zA-Z0-9_:]*)\{([^}]+)\}/g)) {
          if (metric.startsWith("k6_")) {
            assert.match(labels, /service="deskseed"/);
          } else {
            assert.match(labels, /stack="deskseed"/);
            assert.match(labels, /host=~"\$host"/);
          }
        }
      }
    }
  });
}

test("mixed-dimension panels explicitly override units per query", async () => {
  const d = await dashboard("diagnostics");
  for (const [id, expected] of [[303, {A:"s", B:"s", C:"bytes"}], [306, {A:"s", B:"Bps"}], [307, {A:"percentunit", B:"short", C:"s"}], [317, {A:"percentunit", B:"short", C:"short"}]]) {
    const panel = d.panels.find((p) => p.id === id);
    for (const [ref, unit] of Object.entries(expected)) {
      const override = panel.fieldConfig.overrides.find((o) => o.matcher.id === "byFrameRefID" && o.matcher.options === ref);
      assert.ok(override, `panel ${id} query ${ref} needs an explicit unit`);
      assert.equal(override.properties.find((p) => p.id === "unit")?.value, unit);
    }
  }
});

test("existing diagnostic dimensions are preserved instead of discarded", async () => {
  const d = await dashboard("diagnostics");
  const queries = d.panels.flatMap((p) => p.targets ?? []).map((t) => t.expr ?? "").join("\n");
  for (const metric of ["deskseed_customer_auth_limiter_duration_seconds_bucket", "deskseed_customer_auth_limiter_decisions_total", "deskseed_collaboration_websocket_connection_events_total", "pg_stat_activity_max_tx_duration", "pg_stat_statements_block_read_seconds_total", "container_cpu_cfs_throttled_periods_total", "node_disk_read_time_seconds_total", "scrape_duration_seconds"]) assert.ok(queries.includes(metric), metric);
  assert.match(queries, /sum by \([^)]*wait_event_type[^)]*wait_event[^)]*\)/);
});
