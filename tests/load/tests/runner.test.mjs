import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
const runner = join(repositoryRoot, "scripts/load/run-k6.sh");

async function createFixture(t, environmentText) {
  const temporaryDirectory = await mkdtemp(join(tmpdir(), "deskseed-load-runner-test-"));
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const executableDirectory = join(temporaryDirectory, "bin");
  const resultsDirectory = join(temporaryDirectory, "results output");
  const environmentFile = join(temporaryDirectory, "synthetic.env");
  const dockerLog = join(temporaryDirectory, "docker-calls.log");
  await mkdir(executableDirectory);
  await mkdir(resultsDirectory);
  await writeFile(environmentFile, environmentText, { mode: 0o600 });
  await writeFile(join(executableDirectory, "docker"), [
    "#!/bin/sh",
    'printf "called\\n" >> "$FAKE_DOCKER_LOG"',
    'exit "${FAKE_DOCKER_EXIT_CODE:-0}"',
    "",
  ].join("\n"), { mode: 0o755 });

  return {
    resultsDirectory,
    dockerLog,
    run({ scenario = "agent-read", exitCode = 0 } = {}) {
      return spawnSync("sh", [runner, scenario, environmentFile, resultsDirectory], {
        cwd: repositoryRoot,
        env: {
          ...process.env,
          PATH: `${executableDirectory}:${process.env.PATH}`,
          FAKE_DOCKER_LOG: dockerLog,
          FAKE_DOCKER_EXIT_CODE: String(exitCode),
        },
        encoding: "utf8",
        timeout: 10000,
      });
    },
  };
}

for (const [description, runIdLine] of [["missing", ""], ["empty", "TEST_RUN_ID=\n"]]) {
  test(`runner rejects a ${description} TEST_RUN_ID before invoking Docker`, async (t) => {
    const fixture = await createFixture(t, `${runIdLine}TARGET_URL=https://deskseed.example.test\n`);

    const result = fixture.run();

    assert.notEqual(result.status, 0, "a run without an explicit ID cannot produce distinct evidence");
    await assert.rejects(access(fixture.dockerLog), { code: "ENOENT" });
  });
}

test("runner records normalized run identity, timestamps and exit code without copying secrets", async (t) => {
  const fixture = await createFixture(t, [
    "TEST_RUN_ID=run/with spaces",
    "TARGET_URL=https://deskseed.example.test",
    "STAFF_PASSWORD=synthetic-password-must-not-appear",
    "K6_PROMETHEUS_RW_SERVER_URL=https://synthetic-secret.example.test/write",
    "",
  ].join("\n"));

  const result = fixture.run();

  assert.equal(result.status, 0, result.stderr);
  const text = await readFile(join(fixture.resultsDirectory, "run-with-spaces-agent-read-runner.json"), "utf8");
  const artifact = JSON.parse(text);
  assert.equal(artifact.testRunId, "run-with-spaces");
  assert.equal(artifact.scenario, "agent-read");
  assert.equal(artifact.exitCode, 0);
  for (const timestamp of [artifact.startedAt, artifact.completedAt]) {
    assert.match(timestamp, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/);
    assert.ok(Number.isFinite(Date.parse(timestamp)));
  }
  assert.ok(Date.parse(artifact.startedAt) <= Date.parse(artifact.completedAt));
  assert.doesNotMatch(text, /synthetic-password|synthetic-secret|STAFF_PASSWORD|K6_PROMETHEUS_RW_SERVER_URL/);
});

test("runner truncates the normalized run ID to the same 80 characters used by k6", async (t) => {
  const fixture = await createFixture(t, `TEST_RUN_ID=${"x".repeat(90)}\n`);

  const result = fixture.run();

  assert.equal(result.status, 0, result.stderr);
  const artifact = JSON.parse(await readFile(join(fixture.resultsDirectory, `${"x".repeat(80)}-agent-read-runner.json`), "utf8"));
  assert.equal(artifact.testRunId, "x".repeat(80));
});

test("runner preserves a failed Docker exit code and records it in the run artifact", async (t) => {
  const fixture = await createFixture(t, "TEST_RUN_ID=failed-run\n");

  const result = fixture.run({ scenario: "collaboration-websocket", exitCode: 99 });

  assert.equal(result.status, 99, result.stderr);
  const artifact = JSON.parse(await readFile(join(fixture.resultsDirectory, "failed-run-collaboration-websocket-runner.json"), "utf8"));
  assert.equal(artifact.testRunId, "failed-run");
  assert.equal(artifact.scenario, "collaboration-websocket");
  assert.equal(artifact.exitCode, 99);
  assert.ok(Number.isFinite(Date.parse(artifact.completedAt)), "failed runs still need their completion time");
});

test("runner refuses to overwrite existing evidence for the same run and scenario", async (t) => {
  const fixture = await createFixture(t, "TEST_RUN_ID=existing-run\n");
  const artifactPath = join(fixture.resultsDirectory, "existing-run-agent-read-runner.json");
  const existingEvidence = '{"testRunId":"existing-run","exitCode":99}\n';
  await writeFile(artifactPath, existingEvidence);

  const result = fixture.run();

  assert.notEqual(result.status, 0, "a repeated run must not replace previous evidence");
  assert.equal(await readFile(artifactPath, "utf8"), existingEvidence);
  await assert.rejects(access(fixture.dockerLog), { code: "ENOENT" });
});
