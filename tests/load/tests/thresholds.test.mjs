import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../../", import.meta.url);

test("non-smoke HTTP profiles require declared p95 and p99 budgets", async () => {
  const config = await readFile(new URL("tests/load/lib/config.js", repositoryRoot), "utf8");

  assert.match(config, /MAX_HTTP_P95_MS/);
  assert.match(config, /MAX_HTTP_P99_MS/);
  assert.match(config, /http_req_duration\{expected_response:true\}/);
  assert.match(config, /p\(95\)</);
  assert.match(config, /p\(99\)</);
});

test("auth latency includes every bounded auth outcome while unexpected statuses still fail", async () => {
  const config = await readFile(new URL("tests/load/lib/config.js", repositoryRoot), "utf8");

  assert.match(config, /http_req_duration\{name:customer_password_session\}/);
  assert.match(config, /unexpected_status: \['rate==0'\]/);
});

test("non-smoke WebSocket profiles require declared connection p95 and p99 budgets", async () => {
  const config = await readFile(new URL("tests/load/lib/config.js", repositoryRoot), "utf8");

  assert.match(config, /MAX_WEBSOCKET_CONNECT_P95_MS/);
  assert.match(config, /MAX_WEBSOCKET_CONNECT_P99_MS/);
  assert.match(config, /ws_connecting\{name:collaboration_websocket\}/);
  assert.match(config, /dropped_iterations: \['count==0'\]/);
});
