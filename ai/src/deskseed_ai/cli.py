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
            "restore-index-artifact",
        ],
    )
    parser.add_argument("--workspace-key", default="default")
    parser.add_argument("--artifact-generation", type=int)
    parser.add_argument("--expected-publication-epoch", type=int)
    parser.add_argument("--expected-corpus-revision", type=int)
    parser.add_argument("--reason")
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
        elif args.command == "restore-index-artifact":
            if any(
                value is None
                for value in (
                    args.artifact_generation,
                    args.expected_publication_epoch,
                    args.expected_corpus_revision,
                    args.reason,
                )
            ):
                parser.error(
                    "restore-index-artifact requires --artifact-generation, "
                    "--expected-publication-epoch, --expected-corpus-revision and --reason"
                )
            from .repository import Repository
            from .security import EnvelopeCipher

            restored = Repository(database, settings, EnvelopeCipher(settings)).restore_index_artifact(
                args.workspace_key,
                args.artifact_generation,
                args.expected_publication_epoch,
                args.expected_corpus_revision,
                args.reason,
            )
            print(
                f"publication_epoch={restored.generation} "
                f"artifact_generation={restored.artifact_generation} "
                f"canonical_corpus_revision={restored.canonical_corpus_revision}"
            )
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
            lambda: (
                runtime.repository.purge_expired_cache_entries()
                + runtime.repository.purge_expired_context_memories()
                + runtime.repository.purge_expired_results()
                + runtime.repository.purge_expired_shared_executions()
                + runtime.repository.purge_expired_metadata()
            ),
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
