from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

from deskseed_ai.evaluation import EvaluationSnapshotCodec, build_case, build_resource


def main() -> None:
    parser = argparse.ArgumentParser(description="Encrypt approved PUBLIC evaluation cases without a plaintext file")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--ttl-days", type=int, default=30)
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args()
    key = _read_key(args.key_file) if args.key_file else os.environ.get("DESKSEED_AI_EVALUATION_ENCRYPTION_KEY", "")
    if not key:
        raise SystemExit("DESKSEED_AI_EVALUATION_ENCRYPTION_KEY is required")
    payload = json.load(sys.stdin)
    records = payload if isinstance(payload, list) else payload.get("cases")
    resource_records = [] if isinstance(payload, list) else payload.get("resources", [])
    if not isinstance(records, list) or not isinstance(resource_records, list):
        raise SystemExit("evaluation input must contain case and resource arrays")
    cases = [
        build_case(
            feature=record["feature"],
            source_identity=record["sourceIdentity"],
            source_revision=record["sourceRevision"],
            family_key=record["familyKey"],
            public_input=record["publicInput"],
            labels=record.get("labels"),
        )
        for record in records
    ]
    resources = [
        build_resource(
            source_identity=record["sourceIdentity"],
            source_revision=record["sourceRevision"],
            public_content=record["publicContent"],
        )
        for record in resource_records
    ]
    sealed = EvaluationSnapshotCodec(key).seal(cases, resources=resources, ttl_days=args.ttl_days)
    with args.output.open("xb") as output:
        output.write(sealed)
    manifest = {
        "caseCount": len(cases),
        "resourceCount": len(resources),
        "ciphertextSha256": hashlib.sha256(sealed).hexdigest(),
        "featureCounts": {
            feature: sum(case.feature == feature for case in cases)
            for feature in ("ticket.summary", "ticket.triage", "ticket.reply_draft")
        },
        "splitCounts": {
            "tune": sum(case.split == "tune" for case in cases),
            "holdout": sum(case.split == "holdout" for case in cases),
        },
    }
    print(json.dumps(manifest, sort_keys=True))


def _read_key(path: Path) -> str:
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        raise SystemExit("evaluation key file must not be readable by group or others")
    return path.read_text(encoding="ascii").strip()


if __name__ == "__main__":
    main()
