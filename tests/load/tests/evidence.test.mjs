import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { buildEvidenceManifest, createSummaryHandler, evidenceFileStem } from "../lib/evidence.js";

const repositoryRoot = new URL("../../../", import.meta.url);

test("manifest records reproducibility metadata and failed thresholds without copying secrets", () => {
  const manifest = buildEvidenceManifest({
    scenarioName: "agent-read",
    runId: "run/with spaces",
    loadProfile: "load",
    startedAt: "2026-09-04T00:00:00.000Z",
    completedAt: "2026-09-04T00:05:00.000Z",
    env: {
      LOAD_ENVIRONMENT_ID: "deskseed-load-a",
      FIXTURE_DATASET_ID: "fixture-100k",
      FIXTURE_SIZE: "tickets=100000,comments=500000",
      TELEMETRY_MODE: "prometheus+loki+tempo+pyroscope",
      LOAD_GENERATOR_ID: "generator-a",
      APP_RESOURCE_LIMITS: "backend=4cpu/8GiB,db=4cpu/8GiB",
      DESKSEED_GIT_SHA: "0123456789abcdef0123456789abcdef01234567",
      DESKSEED_GIT_DIRTY: "false",
      TARGET_RPS: "50",
      MAX_HTTP_P95_MS: "500",
      MAX_HTTP_P99_MS: "900",
      CUSTOMER_PASSWORD: "must-never-appear",
      K6_PROMETHEUS_RW_SERVER_URL: "http://secret-host/write",
    },
    data: {
      metrics: {
        checks: { type: "rate", values: { rate: 1 }, thresholds: { "rate==1": { ok: true } } },
        dropped_iterations: { type: "counter", values: { count: 3 }, thresholds: { "count==0": { ok: false } } },
        unexpected_status: { type: "rate", values: { rate: 0 }, thresholds: { "rate==0": { ok: true } } },
      },
    },
  });

  assert.equal(manifest.result.status, "FAILED");
  assert.deepEqual(manifest.result.failedThresholds, ["dropped_iterations: count==0"]);
  assert.equal(manifest.source.commitSha, "0123456789abcdef0123456789abcdef01234567");
  assert.equal(manifest.environment.fixtureSize, "tickets=100000,comments=500000");
  assert.equal(manifest.workload.targetRps, 50);
  assert.equal(manifest.observations.droppedIterations, 3);
  assert.equal(evidenceFileStem("run/with spaces", "agent-read"), "run-with-spaces-agent-read");
  assert.doesNotMatch(JSON.stringify(manifest), /must-never-appear|secret-host|CUSTOMER_PASSWORD/);
});

test("every scenario exports a per-run summary handler", async () => {
  for (const scenario of ["agent-read", "public-request", "customer-auth-limiter", "collaboration-websocket"]) {
    const source = await readFile(new URL(`tests/load/scenarios/${scenario}.js`, repositoryRoot), "utf8");
    assert.match(source, /export const handleSummary = handleSummaryFor\(/, scenario);
  }
});

test("summary handler writes run-scoped JSON summary and manifest paths", () => {
  const handler = createSummaryHandler({
    scenarioName: "agent-read",
    runId: "run-001",
    loadProfile: "smoke",
    env: {},
  });
  const outputs = handler({ metrics: {} });

  assert.ok(outputs["/results/run-001-agent-read-summary.json"]);
  assert.ok(outputs["/results/run-001-agent-read-manifest.json"]);
  assert.equal(JSON.parse(outputs["/results/run-001-agent-read-manifest.json"]).identity.testRunId, "run-001");
});

test("non-smoke configuration requires evidence metadata and runner records source state", async () => {
  const config = await readFile(new URL("tests/load/lib/config.js", repositoryRoot), "utf8");
  const runner = await readFile(new URL("scripts/load/run-k6.sh", repositoryRoot), "utf8");

  for (const name of [
    "LOAD_ENVIRONMENT_ID",
    "FIXTURE_DATASET_ID",
    "FIXTURE_SIZE",
    "TELEMETRY_MODE",
    "LOAD_GENERATOR_ID",
    "APP_RESOURCE_LIMITS",
  ]) {
    assert.match(config, new RegExp(name));
  }
  assert.match(runner, /DESKSEED_GIT_SHA/);
  assert.match(runner, /DESKSEED_GIT_DIRTY/);
  assert.doesNotMatch(runner, /--summary-export/);
});
