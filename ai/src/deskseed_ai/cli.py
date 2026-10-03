from __future__ import annotations

import argparse
import time
from pathlib import Path

from .config import get_settings
from .db import Database, apply_migrations


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=[
            "migrate",
            "check",
            "run-dispatcher",
            "run-worker",
            "run-recovery",
            "run-indexer",
            "run-feedback",
            "run-retention",
        ],
    )
    args = parser.parse_args()
    if args.command.startswith("run-"):
        run_role(args.command.removeprefix("run-"))
        return
    settings = get_settings()
    database = Database(settings)
    database.open()
    try:
        if args.command == "migrate":
            apply_migrations(database, Path(__file__).resolve().parents[2] / "migrations")
        elif not database.ping():
            raise SystemExit("AI database check failed")
    finally:
        database.close()


def run_role(role: str) -> None:
    from .main import Runtime

    runtime = Runtime(get_settings())
    runtime.open()
    operations = {
        "dispatcher": (runtime.stream.dispatch_once, 0.2),
        "worker": (lambda: runtime.stream.consume_once(block_ms=1000), 0.05),
        "recovery": (runtime.stream.recover_once, 5.0),
        "indexer": (runtime.indexing.cycle_once, 0.5),
        "feedback": (runtime.feedback.export_once, 5.0),
        "retention": (
            lambda: runtime.repository.purge_expired_results() + runtime.repository.purge_expired_metadata(),
            3600.0,
        ),
    }
    operation, interval = operations[role]
    try:
        while True:
            operation()
            time.sleep(interval)
    except KeyboardInterrupt:
        pass
    finally:
        runtime.traces.flush()
        runtime.database.close()


if __name__ == "__main__":
    main()
