#!/usr/bin/env python3
"""Verify pinned k6 remote-write units/tags against isolated loopback Prometheus.

Requires native k6 2.0.0 and Prometheus 3.14.0 via K6_BIN/PROMETHEUS_BIN or PATH.
Only a synthetic HTTP server is exercised; no Deskseed deployment is contacted.
"""

import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlencode
from urllib.request import urlopen


class SyntheticHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(0.02 if self.path == "/fast" else 0.12)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *_args):
        pass


def verify():
    root = Path(__file__).resolve().parents[2]
    k6 = os.environ.get("K6_BIN", "k6")
    prometheus_bin = os.environ.get("PROMETHEUS_BIN", "prometheus")
    for command, expected in [([k6, "version"], "k6 v2.0.0 "), ([prometheus_bin, "--version"], "version 3.14.0 ")]:
        version = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
        if expected not in version.stdout + version.stderr:
            raise RuntimeError(f"Expected pinned runtime: {expected.strip()}")

    with ThreadingHTTPServer(("127.0.0.1", 0), SyntheticHandler) as server, tempfile.TemporaryDirectory(prefix="deskseed-k6-export-") as temporary:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        directory = Path(temporary)
        config = directory / "prometheus.yml"
        config.write_text("global:\n  scrape_interval: 1s\nscrape_configs: []\n")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            prom_port = probe.getsockname()[1]
        endpoint = f"http://127.0.0.1:{prom_port}"
        log = directory / "prometheus.log"
        prometheus = None
        try:
            with log.open("w") as output:
                prometheus = subprocess.Popen([
                    prometheus_bin, f"--config.file={config}", f"--storage.tsdb.path={directory / 'data'}",
                    f"--web.listen-address=127.0.0.1:{prom_port}", "--web.enable-remote-write-receiver",
                ], stdout=output, stderr=output)
            for _ in range(100):
                try:
                    with urlopen(endpoint + "/-/ready", timeout=1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Local Prometheus did not become ready: " + log.read_text())

            script = directory / "export.js"
            script.write_text("""import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { standardOptions, targetUrl } from ROOT_CONFIG;
import { unexpectedStatus } from ROOT_METRICS;
export const options = standardOptions('agent-read');
const unitProbe = new Trend('synthetic_time_unit_probe', true);
export default function () {
  for (let i = 0; i < 5; i++) {
    unitProbe.add(250);
    for (const name of ['fast', 'slow']) {
      const response = http.get(`${targetUrl}/${name}`, { tags: { name } });
      check(response, { 'synthetic response ok': r => r.status === 200 });
      unexpectedStatus.add(response.status !== 200);
    }
    sleep(0.5);
  }
}
""".replace("ROOT_CONFIG", json.dumps(str(root / "tests/load/lib/config.js")))
                .replace("ROOT_METRICS", json.dumps(str(root / "tests/load/lib/metrics.js"))))
            settings = {
                "TARGET_URL": f"http://127.0.0.1:{server.server_port}", "TEST_RUN_ID": "local-export-contract", "LOAD_PROFILE": "smoke",
                "K6_PROMETHEUS_RW_SERVER_URL": endpoint + "/api/v1/write",
                "K6_PROMETHEUS_RW_TREND_STATS": "p(50),p(95),p(99),max",
                "K6_PROMETHEUS_RW_PUSH_INTERVAL": "1s", "K6_PROMETHEUS_RW_STALE_MARKERS": "true",
                "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM": "false",
            }
            args = [k6, "run", "--out", "experimental-prometheus-rw"]
            for key, value in settings.items():
                args.extend(["-e", key + "=" + value])
            started = time.time()
            run = subprocess.run(args + [str(script)], capture_output=True, text=True, timeout=30)
            if run.returncode:
                raise RuntimeError(run.stdout + "\n" + run.stderr)
            ended = time.time()
            results = {}
            for name in ["k6_http_req_duration_p95", "k6_vus", "k6_checks_rate", "k6_http_reqs_total", "k6_synthetic_time_unit_probe_p95"]:
                query = urlencode({"query": name + '{test_run_id="local-export-contract"}', "start": started, "end": ended, "step": "0.2"})
                with urlopen(endpoint + "/api/v1/query_range?" + query, timeout=5) as response:
                    payload = json.load(response)
                if payload.get("status") != "success" or not payload["data"]["result"]:
                    raise RuntimeError(f"Missing exported series: {name}")
                samples = payload["data"]["result"]
                for sample in samples:
                    if any(sample["metric"].get(key) != value for key, value in {
                        "service": "deskseed", "environment": "load", "profile": "smoke", "scenario": "agent-read",
                    }.items()):
                        raise RuntimeError(f"Missing run-wide or scenario tags: {name}")
                results[name] = [{"labels": s["metric"], "lastValue": s["values"][-1][1]} for s in samples]
            if {s["labels"]["name"] for s in results["k6_http_req_duration_p95"]} != {"fast", "slow"}:
                raise RuntimeError("HTTP operation identities were lost")
            if any(float(s["lastValue"]) != 0.25 for s in results["k6_synthetic_time_unit_probe_p95"]):
                raise RuntimeError("250ms Time-valued Trend was not exported as 0.25 seconds")
            panel = json.loads((root / "ops/observability/monitoring-server/grafana/deskseed-load-overview.json").read_text())
            if next(p for p in panel["panels"] if p["id"] == 2)["fieldConfig"]["defaults"]["unit"] != "s":
                raise RuntimeError("Client latency panel does not use the verified seconds unit")
            print(json.dumps({"status": "PASSED", "scope": "loopback synthetic export; no Deskseed app load", "k6Version": "2.0.0", "prometheusVersion": "3.14.0", "series": results}, indent=2))
        finally:
            if prometheus is not None:
                prometheus.terminate()
                try:
                    prometheus.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    prometheus.kill()
                    prometheus.wait()
            server.shutdown()


if __name__ == "__main__":
    verify()
