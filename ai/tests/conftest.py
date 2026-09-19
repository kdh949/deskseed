from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from redis import Redis
from testcontainers.community.postgres import PostgresContainer
from testcontainers.community.redis import RedisContainer

from deskseed_ai.config import Settings
from deskseed_ai.db import Database, apply_migrations
from deskseed_ai.repository import Repository
from deskseed_ai.security import EnvelopeCipher


@pytest.fixture(scope="session")
def infrastructure() -> Iterator[tuple[str, str]]:
    with PostgresContainer(
        image="pgvector/pgvector:0.8.6-pg17-bookworm",
        username="deskseed_ai",
        password="test-password",
        dbname="deskseed_ai",
    ) as postgres, RedisContainer(image="redis:8.2.9-alpine") as redis:
        postgres_url = (
            f"postgresql://{postgres.username}:{postgres.password}@"
            f"{postgres.get_container_host_ip()}:{postgres.get_exposed_port(5432)}/{postgres.dbname}"
        )
        redis_url = f"redis://{redis.get_container_host_ip()}:{redis.get_exposed_port(6379)}/0"
        yield postgres_url, redis_url


@pytest.fixture
def settings(infrastructure: tuple[str, str]) -> Settings:
    postgres_url, redis_url = infrastructure
    return Settings(
        environment="test",
        database_url=postgres_url,
        redis_url=redis_url,
        migrate_on_start=False,
        run_background_workers=False,
        consumer_name="test-worker",
    )


@pytest.fixture
def repository(settings: Settings) -> Iterator[Repository]:
    database = Database(settings)
    database.open()
    apply_migrations(database, Path(__file__).resolve().parents[1] / "migrations")
    with database.transaction() as connection:
        connection.execute(
            """
            truncate table ai_reply_sent_usage, ai_reply_sent_inbox,
                ai_embedding_batch_files, ai_embedding_batch_items, ai_embedding_batch_jobs,
                ai_dead_letters, ai_operations, ai_feedback, ai_context_memories,
                ai_kb_reconciliation_runs,
                ai_kb_chunks, ai_embedding_artifacts, ai_kb_revisions, ai_kb_article_state,
                ai_kb_index_inbox, ai_cost_ledger, ai_dispatch_outbox, ai_jobs, ai_job_inbox cascade
            """
        )
        connection.execute("update ai_telemetry_counters set counter_value = 0, updated_at = clock_timestamp()")
    Redis.from_url(settings.redis_url.get_secret_value()).flushdb()
    try:
        yield Repository(database, settings, EnvelopeCipher(settings))
    finally:
        database.close()
