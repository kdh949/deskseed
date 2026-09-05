import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

const flowUrl = new URL("../flows/collaboration-websocket.js", import.meta.url);
// k6 modules are runtime-only. Keep the flow body intact and replace its import
// boundary with deterministic HTTP/socket doubles, without requiring a k6 server.
const flowSource = (await readFile(flowUrl, "utf8"))
  .replace(/^import .*;\r?\n/gm, "")
  .replace(/^export function /gm, "function ");

const ticketNumber = 1042;
const compatibleCookies = {
  DESKSEED_SESSION: ["synthetic-staff-session"],
  JSESSIONID: ["synthetic-legacy-session"],
};

function runFlow({ cookies = compatibleCookies, env = {}, messages = [] } = {}) {
  const checks = [];
  const unexpectedStatuses = [];
  const connections = [];
  const sentMessages = [];
  const socketEvents = new Map();
  const timeouts = [];
  const socket = {
    on(event, handler) { socketEvents.set(event, handler); },
    send(payload) { sentMessages.push(JSON.parse(payload)); },
    setInterval() {},
    setTimeout(handler) { timeouts.push(handler); },
    close() { socketEvents.get("close")?.(); },
  };

  runInNewContext(`${flowSource}\ncollaborationWebSocketFlow();`, {
    __ENV: env,
    targetUrl: "https://deskseed.example.test",
    randomUuid: () => "synthetic-request-id",
    staffSession: () => ({}),
    staffHeaders: () => ({}),
    check(value, predicates) {
      const results = Object.entries(predicates).map(([name, predicate]) => {
        const passed = Boolean(predicate(value));
        checks.push({ name, passed });
        return passed;
      });
      return results.every(Boolean);
    },
    unexpectedStatus: { add(value) { unexpectedStatuses.push(Boolean(value)); } },
    http: {
      get: () => ({
        status: 200,
        json: (path) => path === "items" ? [{ ticketNumber }] : ticketNumber,
      }),
      cookieJar: () => ({ cookiesForURL: () => cookies }),
    },
    ws: {
      connect(url, options, configure) {
        connections.push({ url, options });
        configure(socket);
        socketEvents.get("open")?.();
        for (const payload of messages) socketEvents.get("message")?.(payload);
        for (const timeout of timeouts) timeout();
        return { status: 101 };
      },
    },
  }, { filename: flowUrl.pathname });

  return { checks, unexpectedStatuses, connections, sentMessages };
}

function snapshotChecks(result) {
  return result.checks.filter(({ name }) => name.includes("presence snapshot"));
}

test("staff WebSocket forwards the default DESKSEED_SESSION cookie", () => {
  const result = runFlow({
    cookies: { DESKSEED_SESSION: ["synthetic-staff-session"] },
    messages: [JSON.stringify({ type: "presence.snapshot", ticketNumber })],
  });

  assert.equal(result.connections.length, 1, "staff session must reach the WebSocket handshake");
  assert.equal(result.connections[0].options.headers.Cookie, "DESKSEED_SESSION=synthetic-staff-session");
  assert.equal(result.sentMessages[0].ticketNumber, ticketNumber);
});

test("staff WebSocket uses an explicitly configured session cookie name", () => {
  const result = runFlow({
    cookies: { CUSTOM_STAFF_SESSION: ["synthetic-custom-session"] },
    env: { STAFF_SESSION_COOKIE_NAME: "CUSTOM_STAFF_SESSION" },
    messages: [JSON.stringify({ type: "presence.snapshot", ticketNumber })],
  });

  assert.equal(result.connections.length, 1);
  assert.equal(result.connections[0].options.headers.Cookie, "CUSTOM_STAFF_SESSION=synthetic-custom-session");
});

test("a matching presence snapshot satisfies the WebSocket flow checks", () => {
  const result = runFlow({
    messages: [JSON.stringify({ type: "presence.snapshot", ticketNumber })],
  });

  assert.ok(snapshotChecks(result).some(({ passed }) => passed));
  assert.ok(result.checks.every(({ passed }) => passed));
  assert.ok(result.unexpectedStatuses.every((value) => !value));
});

test("an upgraded connection without a snapshot fails a presence check", () => {
  const result = runFlow();

  assert.ok(result.checks.some(({ name, passed }) => name === "collaboration WebSocket upgrades" && passed));
  assert.ok(snapshotChecks(result).some(({ passed }) => !passed), "missing snapshot must make checks rate fail");
});

test("a snapshot for another ticket fails the presence check", () => {
  const result = runFlow({
    messages: [JSON.stringify({ type: "presence.snapshot", ticketNumber: ticketNumber + 1 })],
  });

  assert.ok(snapshotChecks(result).some(({ passed }) => !passed));
});

for (const [description, payload] of [["invalid JSON", "{malformed"], ["null message", "null"]]) {
  test(`a ${description} records failure without aborting the flow`, () => {
    let result;
    assert.doesNotThrow(() => { result = runFlow({ messages: [payload] }); });

    assert.ok(result.unexpectedStatuses.some(Boolean), "malformed message must fail the unexpected-status threshold");
    assert.ok(snapshotChecks(result).some(({ passed }) => !passed));
  });
}
