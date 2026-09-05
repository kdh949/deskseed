import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../../", import.meta.url);

async function read(relativePath) {
  return readFile(new URL(relativePath, repositoryRoot), "utf8");
}

test("PostgreSQL statement collection keeps bounded I/O and row counters without query text", async () => {
  const compose = await read("compose.observability.yaml");
  const prometheus = await read("ops/observability/monitoring-server/prometheus.yml.example");

  assert.match(compose, /--collector\.stat_statements/);
  assert.doesNotMatch(compose, /include_query|extend\.query|queries\.ya?ml/);
  assert.doesNotMatch(prometheus, /pg_stat_statements_\(rows_total\|block_read_seconds_total\|block_write_seconds_total\)/);
  assert.match(prometheus, /target_label: host/);
});

test("Nginx exporter uses an internal status endpoint and access logs contain only bounded request fields", async () => {
  const compose = await read("compose.observability.yaml");
  const nginx = await read("frontend/nginx.conf");
  const nginxStatus = await read("ops/observability/nginx/status.conf");
  const prometheus = await read("ops/observability/monitoring-server/prometheus.yml.example");

  assert.match(compose, /nginx-exporter:/);
  assert.match(compose, /http:\/\/frontend:8080\/stub_status/);
  assert.match(compose, /DESKSEED_OBSERVABILITY_BIND_ADDRESS[^\n]*:9113:9113/);
  assert.match(prometheus, /job_name: deskseed-nginx/);
  assert.match(compose, /status\.conf:\/etc\/nginx\/conf\.d\/status\.conf:ro/);
  assert.match(nginxStatus, /listen 8080/);
  assert.match(nginxStatus, /stub_status/);
  assert.match(nginx, /\$deskseed_route/);
  assert.match(nginx, /\$status/);
  assert.match(nginx, /\$request_time/);
  assert.match(nginx, /\$upstream_response_time/);
  assert.match(nginx, /\$bytes_sent/);
  assert.doesNotMatch(nginx, /\$request_uri|\$args|\$remote_addr|\$http_cookie|\$http_authorization/);
  assert.doesNotMatch(nginxStatus, /\$request_uri|\$args|\$remote_addr|\$http_cookie|\$http_authorization/);
});

test("Alloy trace pipeline bounds memory, retries delivery, and exposes a finite queue", async () => {
  const alloy = await read("ops/observability/alloy/config.alloy");

  assert.match(alloy, /otelcol\.processor\.memory_limiter "deskseed"/);
  assert.match(alloy, /limit\s+=\s+"256MiB"/);
  assert.match(alloy, /spike_limit\s+=\s+"64MiB"/);
  assert.match(alloy, /retry_on_failure/);
  assert.match(alloy, /max_elapsed_time\s+=\s+"60s"/);
  assert.match(alloy, /sending_queue/);
  assert.match(alloy, /queue_size\s+=\s+1024/);
});
